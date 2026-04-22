package com.mingeek.studiopop.ui.thumbnail

import android.app.Application
import android.graphics.Bitmap
import android.net.Uri
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import androidx.core.net.toUri
import com.mingeek.studiopop.StudioPopApp
import com.mingeek.studiopop.data.project.AssetType
import com.mingeek.studiopop.data.project.ProjectRepository
import com.mingeek.studiopop.data.thumbnail.FrameExtractor
import com.mingeek.studiopop.data.thumbnail.ThumbnailComposer
import com.mingeek.studiopop.data.thumbnail.ThumbnailVariant
import com.mingeek.studiopop.data.thumbnail.VariantGenerator
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicInteger

sealed interface ThumbnailPhase {
    data object Idle : ThumbnailPhase
    data object ExtractingFrame : ThumbnailPhase
    data object GeneratingVariants : ThumbnailPhase
    data object Composing : ThumbnailPhase
    data class Saved(val path: String) : ThumbnailPhase
    data class Error(val message: String) : ThumbnailPhase
}

/**
 * 썸네일 화면 상태.
 * - 프레임 선택 → 주제 입력 → AI 가 여러 변형 추천 → 그리드에서 선택 → 미세조정 → PNG 저장
 */
data class ThumbnailUiState(
    val videoUri: Uri? = null,
    val durationMs: Long = 0L,
    val framePositionMs: Long = 0L,
    val frameBitmap: Bitmap? = null,
    val topic: String = "",
    /** AI + preset 이 조합한 변형 후보들. */
    val variants: List<ThumbnailVariant> = emptyList(),
    /** 각 변형을 프레임에 합성한 미리보기 Bitmap. */
    val composedPreviews: Map<String, Bitmap> = emptyMap(),
    /** 현재 편집중인 변형 id. null 이면 그리드만 노출. */
    val selectedVariantId: String? = null,
    val phase: ThumbnailPhase = ThumbnailPhase.Idle,
)

class ThumbnailViewModel(
    application: Application,
    private val frameExtractor: FrameExtractor,
    private val composer: ThumbnailComposer,
    private val variantGenerator: VariantGenerator,
    private val projectRepository: ProjectRepository,
) : AndroidViewModel(application) {

    private val _uiState = MutableStateFlow(ThumbnailUiState())
    val uiState: StateFlow<ThumbnailUiState> = _uiState.asStateFlow()

    private var projectId: Long? = null

    /**
     * updateVariant 의 합성 race 방지용 sequence. variant id 별로 "가장 최근 update 의 seq" 를
     * 기록하고, 합성이 끝난 뒤 그 seq 가 아직 최신인 경우에만 state 에 반영. 구버전이면 생성된
     * bitmap 은 즉시 recycle 해 leak 방지.
     *
     * 코루틴 cancel 이 아닌 이유: compose 는 withContext(Default) 안에서 비-협력적으로
     * 그려지므로 취소해도 이미 할당된 bitmap 이 중간에 버려지고 GC 지연 시 heap 압박.
     */
    private val updateSeq = AtomicInteger(0)
    private val latestSeqFor = ConcurrentHashMap<String, Int>()

    fun bindProject(id: Long?) {
        if (id == null || id <= 0 || id == projectId) return
        projectId = id
        viewModelScope.launch {
            val project = projectRepository.getProject(id) ?: return@launch
            onVideoSelected(project.sourceVideoUri.toUri())
        }
    }

    fun onVideoSelected(uri: Uri?) {
        if (uri == null) return
        viewModelScope.launch {
            val info = frameExtractor.readInfo(uri)
            _uiState.update {
                it.copy(
                    videoUri = uri,
                    durationMs = info.durationMs,
                    framePositionMs = info.durationMs / 2,
                    frameBitmap = null,
                    variants = emptyList(),
                    composedPreviews = emptyMap(),
                    selectedVariantId = null,
                    phase = ThumbnailPhase.Idle,
                )
            }
            extractFrame()
        }
    }

    fun onPositionChange(ms: Long) {
        _uiState.update { it.copy(framePositionMs = ms.coerceIn(0L, it.durationMs)) }
    }

    fun extractFrame() {
        val uri = _uiState.value.videoUri ?: return
        val pos = _uiState.value.framePositionMs
        viewModelScope.launch {
            _uiState.update { it.copy(phase = ThumbnailPhase.ExtractingFrame) }
            val bmp = frameExtractor.extractFrame(uri, pos)
            _uiState.update {
                it.copy(
                    frameBitmap = bmp,
                    phase = if (bmp == null) ThumbnailPhase.Error("프레임 추출 실패") else ThumbnailPhase.Idle,
                    // 프레임 바뀌면 기존 변형 무효
                    variants = emptyList(),
                    composedPreviews = emptyMap(),
                    selectedVariantId = null,
                )
            }
        }
    }

    fun onTopicChange(value: String) = _uiState.update { it.copy(topic = value) }

    /**
     * Gemini multimodal 로 변형 N개 추천 받고, 각 변형을 합성해 미리보기 생성.
     */
    fun generateVariants() {
        val state = _uiState.value
        val frame = state.frameBitmap ?: return
        val topic = state.topic.ifBlank { "유튜브 영상" }
        viewModelScope.launch {
            _uiState.update { it.copy(phase = ThumbnailPhase.GeneratingVariants) }
            variantGenerator.generate(frame, topic).fold(
                onSuccess = { variants ->
                    _uiState.update { it.copy(variants = variants, phase = ThumbnailPhase.Composing) }
                    val previews = composeAllPreviews(frame, variants)
                    _uiState.update {
                        it.copy(composedPreviews = previews, phase = ThumbnailPhase.Idle)
                    }
                },
                onFailure = { e ->
                    _uiState.update {
                        it.copy(phase = ThumbnailPhase.Error(e.message ?: "변형 생성 실패"))
                    }
                },
            )
        }
    }

    /** 그리드 카드 탭 → 편집 패널 열기. */
    fun selectVariant(id: String) =
        _uiState.update { it.copy(selectedVariantId = id) }

    fun closeSelection() =
        _uiState.update { it.copy(selectedVariantId = null) }

    /**
     * 편집 패널에서 수정된 변형으로 교체 + 미리보기 재합성.
     *
     * Race 처리: 같은 variant 에 대해 빠르게 연속 호출되면 각 compose 는 끝까지 실행되지만
     * 완료 시점의 seq 체크로 "이 결과가 아직 최신인가" 확인. 구버전이면 bmp 즉시 recycle.
     * 이전 preview bitmap 은 Compose 가 아직 draw 중일 수 있어 recycle 하지 않고 GC 에 맡김.
     */
    fun updateVariant(updated: ThumbnailVariant) {
        val state = _uiState.value
        val frame = state.frameBitmap ?: return
        _uiState.update {
            it.copy(variants = it.variants.map { v -> if (v.id == updated.id) updated else v })
        }
        val mySeq = updateSeq.incrementAndGet()
        latestSeqFor[updated.id] = mySeq
        viewModelScope.launch {
            val bmp = composer.compose(frame, updated)
            if (latestSeqFor[updated.id] == mySeq) {
                _uiState.update {
                    it.copy(composedPreviews = it.composedPreviews + (updated.id to bmp))
                }
            } else {
                // 더 최신 update 가 기다리고 있음 — 이 bmp 는 버려져도 됨
                if (!bmp.isRecycled) bmp.recycle()
            }
        }
    }

    /** 선택된 변형을 PNG 로 저장. */
    fun saveSelected() {
        val state = _uiState.value
        val id = state.selectedVariantId ?: return
        val bmp = state.composedPreviews[id] ?: return
        val mainText = state.variants.firstOrNull { it.id == id }?.mainText.orEmpty()
        viewModelScope.launch {
            val outDir = getApplication<Application>().getExternalFilesDir(null)
                ?: getApplication<Application>().filesDir
            val outFile = File(outDir, "thumbnail_${System.currentTimeMillis()}.png")
            runCatching {
                withContext(Dispatchers.IO) { composer.saveAsPng(bmp, outFile) }
            }.fold(
                onSuccess = {
                    _uiState.update { it.copy(phase = ThumbnailPhase.Saved(outFile.absolutePath)) }
                    projectId?.let { pid ->
                        projectRepository.addAsset(
                            projectId = pid,
                            type = AssetType.THUMBNAIL,
                            value = outFile.absolutePath,
                            label = mainText.take(30),
                        )
                    }
                },
                onFailure = { e ->
                    _uiState.update {
                        it.copy(phase = ThumbnailPhase.Error("저장 실패: ${e.message}"))
                    }
                },
            )
        }
    }

    fun dismissMessage() = _uiState.update { it.copy(phase = ThumbnailPhase.Idle) }

    override fun onCleared() {
        super.onCleared()
        // 누적된 미리보기 Bitmap 해제
        _uiState.value.composedPreviews.values.forEach {
            if (!it.isRecycled) it.recycle()
        }
        _uiState.value.frameBitmap?.let { if (!it.isRecycled) it.recycle() }
    }

    // --- 내부 -----------------------------------------------------------

    private suspend fun composeAllPreviews(
        frame: Bitmap,
        variants: List<ThumbnailVariant>,
    ): Map<String, Bitmap> {
        val result = mutableMapOf<String, Bitmap>()
        for (v in variants) {
            result[v.id] = composer.compose(frame, v)
        }
        return result
    }

    companion object {
        val Factory: ViewModelProvider.Factory = viewModelFactory {
            initializer {
                val app = this[ViewModelProvider.AndroidViewModelFactory.APPLICATION_KEY] as StudioPopApp
                val c = app.container
                ThumbnailViewModel(
                    application = app,
                    frameExtractor = c.frameExtractor,
                    composer = c.thumbnailComposer,
                    variantGenerator = c.variantGenerator,
                    projectRepository = c.projectRepository,
                )
            }
        }
    }
}
