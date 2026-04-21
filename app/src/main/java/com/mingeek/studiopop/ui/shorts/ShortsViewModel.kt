package com.mingeek.studiopop.ui.shorts

import android.app.Application
import android.media.MediaMetadataRetriever
import android.net.Uri
import androidx.core.net.toUri
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import androidx.media3.common.util.UnstableApi
import com.mingeek.studiopop.StudioPopApp
import com.mingeek.studiopop.data.caption.Cue
import com.mingeek.studiopop.data.caption.Srt
import com.mingeek.studiopop.data.editor.Timeline
import com.mingeek.studiopop.data.editor.TimelineCaption
import com.mingeek.studiopop.data.editor.TimelineSegment
import com.mingeek.studiopop.data.editor.VideoEditor
import com.mingeek.studiopop.data.project.AssetType
import com.mingeek.studiopop.data.project.ProjectRepository
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

sealed interface ShortsPhase {
    data object Idle : ShortsPhase
    data class Running(val progress: Float) : ShortsPhase
    data class Success(val outputPath: String) : ShortsPhase
    data class Error(val message: String) : ShortsPhase
}

data class ShortsClip(val uri: Uri, val durationMs: Long)

data class ShortsUiState(
    val clips: List<ShortsClip> = emptyList(),
    val srtUri: Uri? = null,
    val cues: List<Cue> = emptyList(),
    val burnCaptions: Boolean = true,
    val phase: ShortsPhase = ShortsPhase.Idle,
) {
    val totalDurationMs: Long get() = clips.sumOf { it.durationMs }
    val willTruncate: Boolean get() = totalDurationMs > SHORTS_MAX_MS
    val finalDurationMs: Long get() = totalDurationMs.coerceAtMost(SHORTS_MAX_MS)
    val canExport: Boolean
        get() = clips.isNotEmpty() &&
                totalDurationMs > 0 &&
                phase !is ShortsPhase.Running

    companion object {
        const val SHORTS_MAX_MS = 60_000L
    }
}

@UnstableApi
class ShortsViewModel(
    application: Application,
    private val videoEditor: VideoEditor,
    private val projectRepository: ProjectRepository,
) : AndroidViewModel(application) {

    private val _uiState = MutableStateFlow(ShortsUiState())
    val uiState: StateFlow<ShortsUiState> = _uiState.asStateFlow()

    private var projectId: Long? = null

    fun bindProject(id: Long?) {
        if (id == null || id <= 0 || id == projectId) return
        projectId = id
        viewModelScope.launch {
            val project = projectRepository.getProject(id) ?: return@launch
            addClip(project.sourceVideoUri.toUri())
            val srtAsset = projectRepository.latestAsset(id, AssetType.CAPTION_SRT)
            if (srtAsset != null) {
                onSrtSelected(java.io.File(srtAsset.value).toUri())
            }
        }
    }

    /**
     * 클립 추가. URI 중복 체크 없이 append (같은 영상 두 번 넣기도 허용).
     */
    fun addClip(uri: Uri?) {
        if (uri == null) return
        viewModelScope.launch {
            val duration = withContext(Dispatchers.IO) { readDurationMs(uri) }
            if (duration <= 0) return@launch
            _uiState.update {
                it.copy(
                    clips = it.clips + ShortsClip(uri, duration),
                    phase = ShortsPhase.Idle,
                )
            }
        }
    }

    fun removeClip(index: Int) {
        _uiState.update {
            if (index !in it.clips.indices) it
            else it.copy(clips = it.clips.toMutableList().apply { removeAt(index) })
        }
    }

    fun onSrtSelected(uri: Uri?) {
        viewModelScope.launch {
            if (uri == null) {
                _uiState.update { it.copy(srtUri = null, cues = emptyList()) }
                return@launch
            }
            val cues = withContext(Dispatchers.IO) {
                runCatching {
                    getApplication<Application>().contentResolver.openInputStream(uri)
                        ?.bufferedReader()?.use { it.readText() }
                        ?: ""
                }.map(Srt::parse).getOrDefault(emptyList())
            }
            _uiState.update { it.copy(srtUri = uri, cues = cues) }
        }
    }

    fun onBurnCaptionsToggle(value: Boolean) {
        _uiState.update { it.copy(burnCaptions = value) }
    }

    fun startExport() {
        val state = _uiState.value
        if (state.clips.isEmpty()) return

        viewModelScope.launch {
            _uiState.update { it.copy(phase = ShortsPhase.Running(0f)) }

            // 60초 초과면 끝에서 잘라 맞춤. 클립 순차로 채우고 60초 차면 마지막 클립 trim.
            val segments = buildSegments(state.clips, ShortsUiState.SHORTS_MAX_MS)

            // 자막은 첫 클립 기준으로만 적용 (보통 clip 1 의 SRT). 나머지 클립 source-time 과
            // 겹치는 cue 는 제외. Timeline.toOutputCues 가 자동 처리.
            val timelineCaptions =
                if (state.burnCaptions) state.cues.map {
                    TimelineCaption(
                        sourceStartMs = it.startMs,
                        sourceEndMs = it.endMs,
                        text = it.text,
                    )
                } else emptyList()

            val timeline = Timeline(segments = segments, captions = timelineCaptions)

            videoEditor.exportTimeline(
                timeline = timeline,
                aspectRatio = 9f / 16f,
                onProgress = { p ->
                    _uiState.update {
                        if (it.phase is ShortsPhase.Running) it.copy(phase = ShortsPhase.Running(p))
                        else it
                    }
                },
            ).fold(
                onSuccess = { file ->
                    _uiState.update { it.copy(phase = ShortsPhase.Success(file.absolutePath)) }
                    projectId?.let { pid ->
                        projectRepository.addAsset(
                            projectId = pid,
                            type = AssetType.SHORTS,
                            value = file.absolutePath,
                            label = "숏츠 (${state.clips.size}클립)",
                        )
                    }
                },
                onFailure = { e ->
                    _uiState.update {
                        it.copy(phase = ShortsPhase.Error(e.message ?: "내보내기 실패"))
                    }
                },
            )
        }
    }

    /**
     * clips 을 차례로 TimelineSegment 로 변환. 누적 길이가 maxMs 를 넘으면 마지막
     * 세그먼트를 잘라 정확히 maxMs 에 맞춤.
     */
    private fun buildSegments(clips: List<ShortsClip>, maxMs: Long): List<TimelineSegment> {
        val result = mutableListOf<TimelineSegment>()
        var remaining = maxMs
        for (clip in clips) {
            if (remaining <= 0) break
            val take = minOf(clip.durationMs, remaining)
            result += TimelineSegment(
                sourceUri = clip.uri,
                sourceStartMs = 0L,
                sourceEndMs = take,
            )
            remaining -= take
        }
        return result
    }

    fun dismissMessage() = _uiState.update { it.copy(phase = ShortsPhase.Idle) }

    private fun readDurationMs(uri: Uri): Long {
        val retriever = MediaMetadataRetriever()
        return try {
            retriever.setDataSource(getApplication<Application>(), uri)
            retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)
                ?.toLongOrNull() ?: 0L
        } catch (_: Exception) {
            0L
        } finally {
            runCatching { retriever.release() }
        }
    }

    companion object {
        val Factory: ViewModelProvider.Factory = viewModelFactory {
            initializer {
                val app = this[ViewModelProvider.AndroidViewModelFactory.APPLICATION_KEY] as StudioPopApp
                ShortsViewModel(
                    application = app,
                    videoEditor = app.container.videoEditor,
                    projectRepository = app.container.projectRepository,
                )
            }
        }
    }
}
