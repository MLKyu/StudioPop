package com.mingeek.studiopop.ui.editor

import android.app.Application
import android.graphics.Bitmap
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
import com.mingeek.studiopop.data.caption.Srt
import com.mingeek.studiopop.data.editor.FrameStripGenerator
import com.mingeek.studiopop.data.editor.Timeline
import com.mingeek.studiopop.data.editor.TimelineCaption
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

sealed interface ExportPhase {
    data object Idle : ExportPhase
    data class Running(val progress: Float) : ExportPhase
    data class Success(val outputPath: String) : ExportPhase
    data class Error(val message: String) : ExportPhase
}

data class EditorUiState(
    val videoUri: Uri? = null,
    val sourceDurationMs: Long = 0L,
    val timeline: Timeline = Timeline(segments = emptyList()),
    val frameStrip: List<Bitmap> = emptyList(),
    val playheadOutputMs: Long = 0L,
    val isPlaying: Boolean = false,
    val seekRequest: Long? = null,
    val selectedSegmentId: String? = null,
    val editingCaption: TimelineCaption? = null,
    val phase: ExportPhase = ExportPhase.Idle,
) {
    val canExport: Boolean
        get() = videoUri != null &&
                timeline.segments.isNotEmpty() &&
                phase !is ExportPhase.Running
}

@UnstableApi
class EditorViewModel(
    application: Application,
    private val videoEditor: VideoEditor,
    private val frameStripGenerator: FrameStripGenerator,
    private val projectRepository: ProjectRepository,
) : AndroidViewModel(application) {

    private val _uiState = MutableStateFlow(EditorUiState())
    val uiState: StateFlow<EditorUiState> = _uiState.asStateFlow()

    private var projectId: Long? = null

    fun bindProject(id: Long?) {
        if (id == null || id <= 0 || id == projectId) return
        projectId = id
        viewModelScope.launch {
            val project = projectRepository.getProject(id) ?: return@launch
            loadVideo(project.sourceVideoUri.toUri())
            val srtAsset = projectRepository.latestAsset(id, AssetType.CAPTION_SRT)
            srtAsset?.let { importSrtFromPath(it.value) }
        }
    }

    fun onVideoSelected(uri: Uri?) {
        if (uri == null) return
        viewModelScope.launch { loadVideo(uri) }
    }

    private suspend fun loadVideo(uri: Uri) {
        val duration = withContext(Dispatchers.IO) { readDurationMs(uri) }
        _uiState.update {
            it.copy(
                videoUri = uri,
                sourceDurationMs = duration,
                timeline = Timeline.single(duration),
                playheadOutputMs = 0L,
                isPlaying = false,
                selectedSegmentId = null,
                frameStrip = emptyList(),
                phase = ExportPhase.Idle,
            )
        }
        // 썸네일 스트립은 백그라운드에서 생성
        viewModelScope.launch {
            val strip = frameStripGenerator.generate(uri, duration)
            _uiState.update { it.copy(frameStrip = strip) }
        }
    }

    fun onSrtPicked(uri: Uri?) {
        if (uri == null) return
        viewModelScope.launch {
            val text = withContext(Dispatchers.IO) {
                runCatching {
                    getApplication<Application>().contentResolver.openInputStream(uri)
                        ?.bufferedReader()?.use { it.readText() }
                        ?: ""
                }.getOrDefault("")
            }
            if (text.isNotBlank()) importSrtText(text)
        }
    }

    private fun importSrtFromPath(path: String) {
        val text = runCatching { java.io.File(path).readText() }.getOrNull() ?: return
        importSrtText(text)
    }

    private fun importSrtText(text: String) {
        val cues = Srt.parse(text)
        val timelineCaptions = cues.map {
            TimelineCaption(
                sourceStartMs = it.startMs,
                sourceEndMs = it.endMs,
                text = it.text,
            )
        }
        _uiState.update { state ->
            state.copy(timeline = state.timeline.copy(captions = timelineCaptions))
        }
    }

    // --- 재생 제어 ---
    fun togglePlay() = _uiState.update { it.copy(isPlaying = !it.isPlaying) }

    fun onPlayheadChange(outputMs: Long) {
        _uiState.update { it.copy(playheadOutputMs = outputMs) }
    }

    fun onPlayheadDragged(outputMs: Long) {
        _uiState.update {
            it.copy(playheadOutputMs = outputMs, seekRequest = outputMs, isPlaying = false)
        }
    }

    fun consumeSeekRequest() = _uiState.update { it.copy(seekRequest = null) }

    // --- 세그먼트 조작 ---
    fun selectSegment(id: String?) = _uiState.update { it.copy(selectedSegmentId = id) }

    fun splitAtPlayhead() {
        _uiState.update {
            it.copy(timeline = it.timeline.splitAtOutputMs(it.playheadOutputMs))
        }
    }

    fun deleteSelectedSegment() {
        val id = _uiState.value.selectedSegmentId ?: return
        _uiState.update {
            val newTimeline = it.timeline.deleteSegment(id)
            val newPlayhead = it.playheadOutputMs.coerceAtMost(newTimeline.outputDurationMs)
            it.copy(
                timeline = newTimeline,
                playheadOutputMs = newPlayhead,
                seekRequest = newPlayhead,
                selectedSegmentId = null,
            )
        }
    }

    // --- 자막 조작 ---
    fun openCaptionEditorForNew() {
        val state = _uiState.value
        val (seg, sourceT) = state.timeline.mapOutputToSource(state.playheadOutputMs)
            ?: return
        val srcEnd = (sourceT + DEFAULT_NEW_CAPTION_MS).coerceAtMost(seg.sourceEndMs)
        _uiState.update {
            it.copy(
                editingCaption = TimelineCaption(
                    sourceStartMs = sourceT,
                    sourceEndMs = srcEnd,
                    text = "",
                )
            )
        }
    }

    fun openCaptionEditorFor(id: String) {
        val cap = _uiState.value.timeline.captions.firstOrNull { it.id == id } ?: return
        _uiState.update { it.copy(editingCaption = cap) }
    }

    fun closeCaptionEditor() = _uiState.update { it.copy(editingCaption = null) }

    fun saveCaption(caption: TimelineCaption) {
        _uiState.update {
            val existed = it.timeline.captions.any { c -> c.id == caption.id }
            val t = if (existed) it.timeline.updateCaption(caption)
            else it.timeline.addCaption(caption)
            it.copy(timeline = t, editingCaption = null)
        }
    }

    fun deleteEditingCaption() {
        val id = _uiState.value.editingCaption?.id ?: return
        _uiState.update {
            it.copy(timeline = it.timeline.deleteCaption(id), editingCaption = null)
        }
    }

    // --- Export ---
    fun startExport() {
        val state = _uiState.value
        val uri = state.videoUri ?: return

        viewModelScope.launch {
            _uiState.update { it.copy(phase = ExportPhase.Running(0f)) }
            videoEditor.exportTimeline(
                sourceUri = uri,
                timeline = state.timeline,
                onProgress = { p ->
                    _uiState.update { s ->
                        if (s.phase is ExportPhase.Running) s.copy(phase = ExportPhase.Running(p))
                        else s
                    }
                },
            ).fold(
                onSuccess = { file ->
                    _uiState.update { it.copy(phase = ExportPhase.Success(file.absolutePath)) }
                    projectId?.let { pid ->
                        projectRepository.addAsset(
                            projectId = pid,
                            type = AssetType.EXPORT_VIDEO,
                            value = file.absolutePath,
                            label = "편집본 (${state.timeline.segments.size}컷)",
                        )
                    }
                },
                onFailure = { e ->
                    _uiState.update {
                        it.copy(phase = ExportPhase.Error(e.message ?: "내보내기 실패"))
                    }
                },
            )
        }
    }

    fun dismissMessage() = _uiState.update { it.copy(phase = ExportPhase.Idle) }

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
        private const val DEFAULT_NEW_CAPTION_MS = 2_000L

        val Factory: ViewModelProvider.Factory = viewModelFactory {
            initializer {
                val app = this[ViewModelProvider.AndroidViewModelFactory.APPLICATION_KEY] as StudioPopApp
                EditorViewModel(
                    application = app,
                    videoEditor = app.container.videoEditor,
                    frameStripGenerator = app.container.frameStripGenerator,
                    projectRepository = app.container.projectRepository,
                )
            }
        }
    }
}
