package com.mingeek.studiopop.ui.caption

import android.app.Application
import android.net.Uri
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import com.mingeek.studiopop.StudioPopApp
import androidx.core.net.toUri
import com.mingeek.studiopop.data.caption.ChunkedTranscriber
import com.mingeek.studiopop.data.caption.Cue
import com.mingeek.studiopop.data.caption.Srt
import com.mingeek.studiopop.data.project.AssetType
import com.mingeek.studiopop.data.project.ProjectRepository
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import java.io.File

sealed interface CaptionPhase {
    data object Idle : CaptionPhase
    data class ExtractingAudio(val progressLabel: String = "오디오 추출 중") : CaptionPhase
    data class Transcribing(val current: Int, val total: Int) : CaptionPhase
    data object Ready : CaptionPhase
    data class Error(val message: String) : CaptionPhase
}

data class CaptionUiState(
    val videoUri: Uri? = null,
    val language: String = "ko",
    val phase: CaptionPhase = CaptionPhase.Idle,
    val cues: List<Cue> = emptyList(),
    val savedFilePath: String? = null,
)

class CaptionViewModel(
    application: Application,
    private val transcriber: ChunkedTranscriber,
    private val projectRepository: ProjectRepository,
) : AndroidViewModel(application) {

    private var projectId: Long? = null

    fun bindProject(id: Long?) {
        if (id == null || id <= 0 || id == projectId) return
        projectId = id
        viewModelScope.launch {
            val project = projectRepository.getProject(id) ?: return@launch
            onVideoSelected(project.sourceVideoUri.toUri())
        }
    }

    private val _uiState = MutableStateFlow(CaptionUiState())
    val uiState: StateFlow<CaptionUiState> = _uiState.asStateFlow()

    fun onVideoSelected(uri: Uri?) {
        _uiState.update {
            it.copy(
                videoUri = uri,
                cues = emptyList(),
                savedFilePath = null,
                phase = CaptionPhase.Idle,
            )
        }
    }

    fun onLanguageChange(code: String) {
        _uiState.update { it.copy(language = code) }
    }

    fun startTranscription() {
        val uri = _uiState.value.videoUri ?: return
        val lang = _uiState.value.language.ifBlank { null }

        viewModelScope.launch {
            _uiState.update {
                it.copy(phase = CaptionPhase.ExtractingAudio(), cues = emptyList())
            }

            transcriber.transcribe(
                videoUri = uri,
                language = lang,
                onProgress = { p ->
                    _uiState.update {
                        it.copy(
                            phase = if (p.currentChunk == 0)
                                CaptionPhase.ExtractingAudio(p.phaseLabel)
                            else CaptionPhase.Transcribing(p.currentChunk, p.totalChunks)
                        )
                    }
                },
            ).fold(
                onSuccess = { cues ->
                    _uiState.update { it.copy(phase = CaptionPhase.Ready, cues = cues) }
                },
                onFailure = { e ->
                    _uiState.update {
                        it.copy(phase = CaptionPhase.Error("전사 실패: ${e.message}"))
                    }
                },
            )
        }
    }

    fun updateCueText(index: Int, text: String) {
        _uiState.update { state ->
            val updated = state.cues.toMutableList()
            if (index in updated.indices) {
                updated[index] = updated[index].copy(text = text)
            }
            state.copy(cues = updated)
        }
    }

    fun saveSrt() {
        val cues = _uiState.value.cues
        if (cues.isEmpty()) return
        viewModelScope.launch {
            val outFile = File(
                getApplication<Application>().getExternalFilesDir(null),
                "captions_${System.currentTimeMillis()}.srt",
            )
            runCatching {
                outFile.writeText(Srt.write(cues))
            }.onSuccess {
                _uiState.update { it.copy(savedFilePath = outFile.absolutePath) }
                projectId?.let { pid ->
                    projectRepository.addAsset(
                        projectId = pid,
                        type = AssetType.CAPTION_SRT,
                        value = outFile.absolutePath,
                        label = "자동 생성 자막",
                    )
                }
            }.onFailure { e ->
                _uiState.update {
                    it.copy(phase = CaptionPhase.Error("저장 실패: ${e.message}"))
                }
            }
        }
    }

    fun dismissError() {
        _uiState.update { it.copy(phase = CaptionPhase.Idle) }
    }

    companion object {
        val Factory: ViewModelProvider.Factory = viewModelFactory {
            initializer {
                val app = this[ViewModelProvider.AndroidViewModelFactory.APPLICATION_KEY] as StudioPopApp
                val container = app.container
                CaptionViewModel(
                    application = app,
                    transcriber = container.chunkedTranscriber,
                    projectRepository = container.projectRepository,
                )
            }
        }
    }
}
