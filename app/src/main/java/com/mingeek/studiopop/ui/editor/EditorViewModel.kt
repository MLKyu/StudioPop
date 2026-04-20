package com.mingeek.studiopop.ui.editor

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

sealed interface ExportPhase {
    data object Idle : ExportPhase
    data class Running(val progress: Float) : ExportPhase
    data class Success(val outputPath: String) : ExportPhase
    data class Error(val message: String) : ExportPhase
}

data class EditorUiState(
    val videoUri: Uri? = null,
    val durationMs: Long = 0L,
    val trimStartMs: Long = 0L,
    val trimEndMs: Long = 0L,
    val srtUri: Uri? = null,
    val cues: List<Cue> = emptyList(),
    val phase: ExportPhase = ExportPhase.Idle,
) {
    val canExport: Boolean
        get() = videoUri != null && trimEndMs > trimStartMs && phase !is ExportPhase.Running
}

@UnstableApi
class EditorViewModel(
    application: Application,
    private val videoEditor: VideoEditor,
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
            onVideoSelected(project.sourceVideoUri.toUri())
            // 가장 최근에 만든 자막이 있으면 자동 불러오기
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
            _uiState.update {
                it.copy(
                    videoUri = uri,
                    durationMs = duration,
                    trimStartMs = 0L,
                    trimEndMs = duration,
                    phase = ExportPhase.Idle,
                )
            }
        }
    }

    fun onTrimChange(startMs: Long, endMs: Long) {
        _uiState.update {
            it.copy(
                trimStartMs = startMs.coerceIn(0L, it.durationMs),
                trimEndMs = endMs.coerceIn(startMs, it.durationMs),
            )
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

    fun startExport() {
        val state = _uiState.value
        val uri = state.videoUri ?: return

        viewModelScope.launch {
            _uiState.update { it.copy(phase = ExportPhase.Running(0f)) }

            val result = videoEditor.export(
                spec = VideoEditor.EditSpec(
                    sourceUri = uri,
                    trimStartMs = state.trimStartMs,
                    trimEndMs = state.trimEndMs,
                    captionCues = state.cues.ifEmpty { null },
                ),
                onProgress = { p ->
                    _uiState.update {
                        val current = it.phase
                        if (current is ExportPhase.Running) it.copy(phase = ExportPhase.Running(p))
                        else it
                    }
                },
            )

            result.fold(
                onSuccess = { file ->
                    _uiState.update { it.copy(phase = ExportPhase.Success(file.absolutePath)) }
                    projectId?.let { pid ->
                        projectRepository.addAsset(
                            projectId = pid,
                            type = AssetType.EXPORT_VIDEO,
                            value = file.absolutePath,
                            label = "편집본",
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

    fun dismissMessage() {
        _uiState.update { it.copy(phase = ExportPhase.Idle) }
    }

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
                EditorViewModel(
                    application = app,
                    videoEditor = app.container.videoEditor,
                    projectRepository = app.container.projectRepository,
                )
            }
        }
    }
}
