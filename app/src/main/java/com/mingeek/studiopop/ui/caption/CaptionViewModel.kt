package com.mingeek.studiopop.ui.caption

import android.app.Application
import android.net.Uri
import androidx.core.net.toUri
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import com.mingeek.studiopop.StudioPopApp
import com.mingeek.studiopop.data.caption.Cue
import com.mingeek.studiopop.data.caption.SpeechToText
import com.mingeek.studiopop.data.caption.Srt
import com.mingeek.studiopop.data.caption.SttEngine
import com.mingeek.studiopop.data.caption.SttRegistry
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
    data class Working(val phaseLabel: String, val current: Int, val total: Int) : CaptionPhase
    data object Ready : CaptionPhase
    data class Error(val message: String) : CaptionPhase
}

data class EngineOption(
    val engine: SttEngine,
    val availability: SpeechToText.Availability,
)

data class CaptionUiState(
    val videoUri: Uri? = null,
    val language: String = "ko",
    val selectedEngine: SttEngine = SttEngine.WHISPER_API,
    val engineOptions: List<EngineOption> = emptyList(),
    val phase: CaptionPhase = CaptionPhase.Idle,
    val cues: List<Cue> = emptyList(),
    val savedFilePath: String? = null,
)

class CaptionViewModel(
    application: Application,
    private val sttRegistry: SttRegistry,
    private val projectRepository: ProjectRepository,
) : AndroidViewModel(application) {

    private val _uiState = MutableStateFlow(CaptionUiState())
    val uiState: StateFlow<CaptionUiState> = _uiState.asStateFlow()

    private var projectId: Long? = null

    init {
        // 엔진별 가용성 평가 후 UI 옵션으로 노출
        viewModelScope.launch {
            val opts = sttRegistry.all().map { e ->
                EngineOption(e.id, e.isAvailable())
            }
            // 첫 사용 가능 엔진을 기본으로
            val firstReady = opts.firstOrNull { it.availability is SpeechToText.Availability.Ready }
                ?.engine
                ?: opts.firstOrNull()?.engine
                ?: SttEngine.WHISPER_API
            _uiState.update {
                it.copy(engineOptions = opts, selectedEngine = firstReady)
            }
        }
    }

    fun bindProject(id: Long?) {
        if (id == null || id <= 0 || id == projectId) return
        projectId = id
        viewModelScope.launch {
            val project = projectRepository.getProject(id) ?: return@launch
            onVideoSelected(project.sourceVideoUri.toUri())
        }
    }

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

    fun onLanguageChange(code: String) = _uiState.update { it.copy(language = code) }

    fun onEngineSelected(engine: SttEngine) {
        _uiState.update { it.copy(selectedEngine = engine) }
    }

    fun startTranscription() {
        val state = _uiState.value
        val uri = state.videoUri ?: return
        val lang = state.language.ifBlank { null }
        val engine = sttRegistry.get(state.selectedEngine)

        viewModelScope.launch {
            _uiState.update {
                it.copy(
                    phase = CaptionPhase.Working("준비 중", 0, 1),
                    cues = emptyList(),
                )
            }
            engine.transcribe(
                videoUri = uri,
                language = lang,
                onProgress = { p ->
                    _uiState.update {
                        it.copy(
                            phase = CaptionPhase.Working(
                                phaseLabel = p.phaseLabel,
                                current = p.currentStep,
                                total = p.totalSteps.coerceAtLeast(1),
                            )
                        )
                    }
                },
            ).fold(
                onSuccess = { cues ->
                    _uiState.update { it.copy(phase = CaptionPhase.Ready, cues = cues) }
                },
                onFailure = { e ->
                    _uiState.update {
                        it.copy(phase = CaptionPhase.Error("전사 실패: ${e.message ?: e::class.simpleName}"))
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
            runCatching { outFile.writeText(Srt.write(cues)) }
                .onSuccess {
                    _uiState.update { it.copy(savedFilePath = outFile.absolutePath) }
                    projectId?.let { pid ->
                        projectRepository.addAsset(
                            projectId = pid,
                            type = AssetType.CAPTION_SRT,
                            value = outFile.absolutePath,
                            label = "자동 생성 자막",
                        )
                    }
                }
                .onFailure { e ->
                    _uiState.update {
                        it.copy(phase = CaptionPhase.Error("저장 실패: ${e.message}"))
                    }
                }
        }
    }

    fun dismissError() = _uiState.update { it.copy(phase = CaptionPhase.Idle) }

    companion object {
        val Factory: ViewModelProvider.Factory = viewModelFactory {
            initializer {
                val app = this[ViewModelProvider.AndroidViewModelFactory.APPLICATION_KEY] as StudioPopApp
                val container = app.container
                CaptionViewModel(
                    application = app,
                    sttRegistry = container.sttRegistry,
                    projectRepository = container.projectRepository,
                )
            }
        }
    }
}
