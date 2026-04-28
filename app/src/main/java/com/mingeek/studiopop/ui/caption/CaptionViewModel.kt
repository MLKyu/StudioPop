package com.mingeek.studiopop.ui.caption

import android.app.Application
import android.net.Uri
import androidx.core.net.toUri
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import com.mingeek.studiopop.AppContainer
import com.mingeek.studiopop.StudioPopApp
import com.mingeek.studiopop.data.caption.Cue
import com.mingeek.studiopop.data.caption.SpeechToText
import com.mingeek.studiopop.data.caption.Srt
import com.mingeek.studiopop.data.caption.SttEngine
import com.mingeek.studiopop.data.caption.SttRegistry
import com.mingeek.studiopop.data.caption.WhisperCppModelManager
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
    /** whisper.cpp 선택 시 사용할 모델 variant. 다른 엔진엔 영향 없음. */
    val whisperCppVariant: WhisperCppModelManager.Variant = WhisperCppModelManager.Variant.BASE_Q5,
    /** Variant → 설치 상태. 다운로드 진행 중인지 등 표시용. */
    val whisperCppVariantStates: Map<WhisperCppModelManager.Variant, WhisperCppModelManager.InstallState> = emptyMap(),
    val phase: CaptionPhase = CaptionPhase.Idle,
    val cues: List<Cue> = emptyList(),
    val savedFilePath: String? = null,
    val latestExportVideoPath: String? = null,
    val latestSrtPath: String? = null,
    val hasProject: Boolean = false,
)

class CaptionViewModel(
    application: Application,
    private val container: AppContainer,
    private val sttRegistry: SttRegistry,
    private val projectRepository: ProjectRepository,
    private val whisperCppModelManager: WhisperCppModelManager,
) : AndroidViewModel(application) {

    private val _uiState = MutableStateFlow(
        CaptionUiState(whisperCppVariant = container.whisperCppVariant)
    )
    val uiState: StateFlow<CaptionUiState> = _uiState.asStateFlow()

    private var projectId: Long? = null

    init {
        // 엔진별 가용성 평가 후 UI 옵션으로 노출
        viewModelScope.launch {
            val opts = sttRegistry.all().map { e ->
                EngineOption(e.id, e.isAvailable())
            }
            val firstReady = opts.firstOrNull { it.availability is SpeechToText.Availability.Ready }
                ?.engine
                ?: opts.firstOrNull()?.engine
                ?: SttEngine.WHISPER_API
            _uiState.update {
                it.copy(engineOptions = opts, selectedEngine = firstReady)
            }
        }
        // whisper.cpp variant 의 설치 상태를 실시간으로 UI 에 반영
        viewModelScope.launch {
            whisperCppModelManager.states.collect { states ->
                _uiState.update { it.copy(whisperCppVariantStates = states) }
                // 현재 선택된 variant 가 다운로드 완료되면 엔진 가용성 재평가
                refreshEngineAvailabilities()
            }
        }
    }

    private suspend fun refreshEngineAvailabilities() {
        val opts = sttRegistry.all().map { e -> EngineOption(e.id, e.isAvailable()) }
        _uiState.update { it.copy(engineOptions = opts) }
    }

    fun onWhisperCppVariantSelected(variant: WhisperCppModelManager.Variant) {
        container.whisperCppVariant = variant
        _uiState.update { it.copy(whisperCppVariant = variant) }
        viewModelScope.launch { refreshEngineAvailabilities() }
    }

    fun bindProject(id: Long?) {
        if (id == null || id <= 0 || id == projectId) return
        projectId = id
        _uiState.update { it.copy(hasProject = true) }
        viewModelScope.launch {
            val project = projectRepository.getProject(id) ?: return@launch
            onVideoSelected(project.sourceVideoUri.toUri())
            refreshQuickLoad()
            runCatching { container.assetBackfillPublisher.backfill(id) }
        }
    }

    private suspend fun refreshQuickLoad() {
        val pid = projectId ?: return
        val video = projectRepository.latestAsset(pid, AssetType.EXPORT_VIDEO)?.value
        val srt = projectRepository.latestAsset(pid, AssetType.CAPTION_SRT)?.value
        _uiState.update { it.copy(latestExportVideoPath = video, latestSrtPath = srt) }
    }

    /** 최신 편집본 영상을 입력 영상으로 교체 — 편집 끝난 영상에 다시 자막을 붙일 때. */
    fun loadLatestExportAsInput() {
        val path = _uiState.value.latestExportVideoPath ?: return
        onVideoSelected(java.io.File(path).toUri())
    }

    /** 최신 SRT 를 cues 로 불러와 편집 가능 상태로 — 재전사 없이 기존 자막 손보기. */
    fun loadLatestSrt() {
        val path = _uiState.value.latestSrtPath ?: return
        viewModelScope.launch {
            val text = runCatching { File(path).readText() }.getOrNull() ?: return@launch
            val cues = Srt.parse(text)
            _uiState.update {
                it.copy(
                    cues = cues,
                    savedFilePath = path,
                    phase = CaptionPhase.Ready,
                )
            }
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
                    // R5c3a 후속: word-level 정보가 있으면 세션 store 에 보관 — SRT 파일에는
                    // word 가 직렬화되지 않아 편집기에서 다시 읽을 때 손실되는 걸 보완.
                    container.captionWordStore.putFromCues(uri, cues)
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
                    // Download/StudioPop 공용 경로에도 복사 등록 → Files 앱/DocumentPicker 에서 보임.
                    runCatching {
                        container.mediaStoreSrtPublisher.publish(
                            file = outFile,
                            displayName = "StudioPop_captions_${System.currentTimeMillis()}",
                        )
                    }
                    refreshQuickLoad()
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
                    container = container,
                    sttRegistry = container.sttRegistry,
                    projectRepository = container.projectRepository,
                    whisperCppModelManager = container.whisperCppModelManager,
                )
            }
        }
    }
}
