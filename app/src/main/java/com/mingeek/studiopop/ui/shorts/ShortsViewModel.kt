package com.mingeek.studiopop.ui.shorts

import android.app.Application
import android.media.MediaMetadataRetriever
import android.net.Uri
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import androidx.core.net.toUri
import androidx.media3.common.util.UnstableApi
import com.mingeek.studiopop.StudioPopApp
import com.mingeek.studiopop.data.caption.Cue
import com.mingeek.studiopop.data.caption.Srt
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

data class ShortsUiState(
    val videoUri: Uri? = null,
    val durationMs: Long = 0L,
    val trimStartMs: Long = 0L,
    val trimEndMs: Long = 0L,
    val srtUri: Uri? = null,
    val cues: List<Cue> = emptyList(),
    val burnCaptions: Boolean = true,
    val phase: ShortsPhase = ShortsPhase.Idle,
) {
    val durationSelectedMs: Long get() = (trimEndMs - trimStartMs).coerceAtLeast(0L)
    val canExport: Boolean
        get() = videoUri != null &&
                durationSelectedMs in 1..SHORTS_MAX_MS &&
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
            onVideoSelected(project.sourceVideoUri.toUri())
            val srtAsset = projectRepository.latestAsset(id, AssetType.CAPTION_SRT)
            if (srtAsset != null) {
                onSrtSelected(java.io.File(srtAsset.value).toUri())
            }
        }
    }

    fun onVideoSelected(uri: Uri?) {
        if (uri == null) return
        viewModelScope.launch {
            val duration = withContext(Dispatchers.IO) { readDurationMs(uri) }
            val end = duration.coerceAtMost(ShortsUiState.SHORTS_MAX_MS)
            _uiState.update {
                it.copy(
                    videoUri = uri,
                    durationMs = duration,
                    trimStartMs = 0L,
                    trimEndMs = end,
                    phase = ShortsPhase.Idle,
                )
            }
        }
    }

    fun onTrimChange(startMs: Long, endMs: Long) {
        _uiState.update {
            val s = startMs.coerceIn(0L, it.durationMs)
            val e = endMs.coerceIn(s, (s + ShortsUiState.SHORTS_MAX_MS).coerceAtMost(it.durationMs))
            it.copy(trimStartMs = s, trimEndMs = e)
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
        val uri = state.videoUri ?: return

        viewModelScope.launch {
            _uiState.update { it.copy(phase = ShortsPhase.Running(0f)) }

            val cuesArg = if (state.burnCaptions) state.cues.ifEmpty { null } else null

            videoEditor.export(
                spec = VideoEditor.EditSpec(
                    sourceUri = uri,
                    trimStartMs = state.trimStartMs,
                    trimEndMs = state.trimEndMs,
                    captionCues = cuesArg,
                    aspectRatio = 9f / 16f,
                ),
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
                            label = "숏츠",
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
