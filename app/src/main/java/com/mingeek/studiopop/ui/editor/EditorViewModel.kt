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
import com.mingeek.studiopop.data.ai.AiAssist
import com.mingeek.studiopop.data.ai.DefaultAiAssist
import com.mingeek.studiopop.data.ai.EditSuggestion
import com.mingeek.studiopop.data.ai.YoutubePackage
import com.mingeek.studiopop.data.audio.AudioAnalysis
import com.mingeek.studiopop.data.audio.AudioAnalysisService
import com.mingeek.studiopop.data.audio.Ducking
import com.mingeek.studiopop.data.caption.CaptionWordStore
import com.mingeek.studiopop.data.caption.Cue
import com.mingeek.studiopop.data.caption.CueWord
import com.mingeek.studiopop.data.caption.Srt
import com.mingeek.studiopop.data.effects.EffectInstance
import com.mingeek.studiopop.data.effects.EffectStack
import com.mingeek.studiopop.data.thumbnail.FrameExtractor
import com.mingeek.studiopop.data.thumbnail.ThumbnailComposer
import com.mingeek.studiopop.data.editor.AudioTrack
import com.mingeek.studiopop.data.editor.CaptionStyle
import com.mingeek.studiopop.data.editor.CutRange
import com.mingeek.studiopop.data.editor.FaceTracker
import com.mingeek.studiopop.data.editor.FixedTextTemplate
import com.mingeek.studiopop.data.editor.FrameStripGenerator
import com.mingeek.studiopop.data.editor.ImageLayer
import com.mingeek.studiopop.data.editor.MosaicKeyframe
import com.mingeek.studiopop.data.editor.MosaicMode
import com.mingeek.studiopop.data.editor.MosaicRegion
import com.mingeek.studiopop.data.editor.SfxClip
import com.mingeek.studiopop.data.editor.TemplateAnchor
import com.mingeek.studiopop.data.editor.TextLayer
import com.mingeek.studiopop.data.editor.Timeline
import com.mingeek.studiopop.data.editor.TimelineCaption
import com.mingeek.studiopop.data.editor.TransitionKind
import com.mingeek.studiopop.data.editor.VideoEditor
import com.mingeek.studiopop.data.library.FixedTemplatePresetEntity
import com.mingeek.studiopop.data.library.FixedTemplatePresetRepository
import com.mingeek.studiopop.data.library.LibraryAssetEntity
import com.mingeek.studiopop.data.library.LibraryAssetKind
import com.mingeek.studiopop.data.library.LibraryAssetRepository
import com.mingeek.studiopop.data.library.TimelineSnapshotRepository
import com.mingeek.studiopop.data.project.AssetEntity
import com.mingeek.studiopop.data.project.AssetType
import com.mingeek.studiopop.data.project.ProjectRepository
import com.mingeek.studiopop.data.vocal.UvrModelManager
import com.mingeek.studiopop.data.vocal.VocalSeparator
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.flow.map
import com.mingeek.studiopop.ui.editor.components.EditableTextItem
import java.io.File
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

enum class EditKind { CAPTION, TEXT_LAYER }

/**
 * R5c1: AI 패키지 생성 진행 상태. UI 가 어느 단계의 spinner 를 보여줄지 결정.
 *  - Idle: 미시작.
 *  - Analyzing: 영상 분석 중 (얼굴/톤/하이라이트/키워드).
 *  - Generating: 분석 끝 후 패키지 생성 중 (제목/챕터/태그/썸네일).
 *  - Failed: 실패 — 사용자에게 메시지 표시.
 */
sealed class AiPackagePhase {
    data object Idle : AiPackagePhase()
    data object Analyzing : AiPackagePhase()
    data object Generating : AiPackagePhase()
    data class Failed(val message: String) : AiPackagePhase()
}

/** 편집기에서 현재 열려있는 서브시트 종류. null 이면 시트 닫힘. */
enum class EditorSheet {
    IMAGE_PICKER,
    SFX_PICKER,
    MOSAIC,
    FIXED_TEMPLATE,
    /** 내 편집본(앱 내부 EXPORT_VIDEO asset) 다중 선택해 append */
    EXPORTED_VIDEO_PICKER,
    /** 트랙별 볼륨 + 센터 채널 추출 등 오디오 믹싱 */
    AUDIO_MIX,
}

data class EditorUiState(
    val timeline: Timeline = Timeline(segments = emptyList()),
    /** 영상 Uri → (총 길이, 프레임 스트립). 세그먼트가 sourceUri 별로 조회. */
    val frameStrips: Map<Uri, Pair<Long, List<Bitmap>>> = emptyMap(),
    val playheadOutputMs: Long = 0L,
    val isPlaying: Boolean = false,
    val seekRequest: Long? = null,
    val editingItem: EditableTextItem? = null,
    val editingKind: EditKind? = null,
    /** 현재 열려있는 서브시트 (짤/효과음 피커, 모자이크 에디터 등). */
    val activeSheet: EditorSheet? = null,
    /** 라이브러리 목록 (시트에서 사용). */
    val stickerLibrary: List<LibraryAssetEntity> = emptyList(),
    val sfxLibrary: List<LibraryAssetEntity> = emptyList(),
    /** 앱 내부 저장된 편집본(EXPORT_VIDEO) 목록. "내 편집본 불러오기" 피커에서 사용. */
    val exportedVideoLibrary: List<com.mingeek.studiopop.data.project.AssetEntity> = emptyList(),
    /** 저장된 고정 텍스트 템플릿 프리셋. */
    val fixedTemplatePresets: List<com.mingeek.studiopop.data.library.FixedTemplatePresetEntity> = emptyList(),
    /** 선택된 ImageLayer/MosaicRegion/FixedTemplate id — 편집 대상. */
    val selectedImageLayerId: String? = null,
    val selectedMosaicId: String? = null,
    val selectedFixedTemplateId: String? = null,
    /** 얼굴 자동감지 중 상태. 진행 중이면 true. */
    val isDetectingFaces: Boolean = false,
    /** 보컬 분리 진행 중. null 이면 idle, 0..1 면 진행 중. */
    val vocalExtractProgress: Float? = null,
    val vocalModelState: UvrModelManager.InstallState = UvrModelManager.InstallState.NOT_INSTALLED,
    val phase: ExportPhase = ExportPhase.Idle,
    /** 프로젝트의 최신 EXPORT_VIDEO 파일 경로. 퀵 로드 카드에서 사용. */
    val latestExportVideoPath: String? = null,
    /** 프로젝트의 최신 CAPTION_SRT 파일 경로. 퀵 로드 카드에서 사용. */
    val latestSrtPath: String? = null,
    val hasProject: Boolean = false,
    /**
     * 프리뷰 박스의 가로/세로 비율 (= 첫 세그먼트 영상의 회전 반영 W/H 비). null 이면 영상 미로딩.
     * 이 값으로 프리뷰 Box 가 정확히 소스 영상 비율로 그려져, 9:16 세로 영상이 16:9 박스 안에서
     * pillarbox 되어 가운데 좁은 띠로 보이는 현상을 막고 자막·짤·모자이크 NDC 좌표가 실제 영상
     * 영역과 일치하도록 한다.
     */
    val previewAspectRatio: Float? = null,
    /**
     * R3.5: 자막 id → 자막 스타일 효과 id (예: "caption.glow_neon"). 매핑 없으면 기본 자막
     * 스타일(기존 PreviewCaptionOverlay 경로) 로 그려진다. ViewModel 메모리만 — 영속화는
     * 다음 라운드에서 TimelineSerializer 확장.
     */
    val captionEffectIds: Map<String, String> = emptyMap(),
    /**
     * R4.5: 첫 세그먼트 영상의 오디오 분석 결과. null 이면 미분석/분석 중. 비트 정보가 들어오면
     * RichTextOverlay 의 펄스가 작동한다. 영상이 바뀔 때마다 LaunchedEffect 가 갱신.
     */
    val audioAnalysis: AudioAnalysis? = null,
    val isAnalyzingAudio: Boolean = false,
    /**
     * R4.5: 비트 펄스가 활성화된 자막 id 집합. 효과 적용된 자막 + 이 집합에 속한 자막에서만
     * 비트 시점에 scale punch 가 작동.
     */
    val captionBeatSyncIds: Set<String> = emptySet(),
    /**
     * R5c3a: 카라오케(단어별 색 진행) 가 활성화된 자막 id 집합. STT word 정보가 있으면 그대로,
     * 없으면 [CaptionEffectResolver] 가 시간비례 fake word timing 을 자동 생성. 효과가 적용된
     * 자막에서만 의미가 있어 시트 토글이 효과 미적용 시 disabled.
     */
    val captionKaraokeIds: Set<String> = emptySet(),
    /**
     * R5c3a 후속: 자막 id → 실 STT word timing. CaptionWordStore 에서 첫 segment uri 매칭으로
     * 채워짐. 비어 있으면 카라오케는 fakeWordTimings fallback. SRT 직접 import / 사용자 직접
     * 입력 자막은 비어 있다.
     */
    val captionWords: Map<String, List<CueWord>> = emptyMap(),
    /**
     * R5c1: AI 패키지 생성 결과 — null 이면 미생성. 사용자가 시트를 닫아도 결과는 유지되고,
     * 다시 보기 가능.
     */
    val youtubePackage: YoutubePackage? = null,
    val editSuggestions: List<EditSuggestion> = emptyList(),
    val aiPackagePhase: AiPackagePhase = AiPackagePhase.Idle,
    val showAiPackageSheet: Boolean = false,
    /**
     * R5c2: 썸네일 변형별 합성 비트맵. variant.id → Bitmap. AiPackageSheet 가 즉시 그릴 수
     * 있도록 ViewModel 에서 미리 합성. 메모리: 1280×720 ARGB ≈ 3.5MB × 5 ≈ 18MB.
     */
    val thumbnailPreviewBitmaps: Map<String, Bitmap> = emptyMap(),
    /**
     * R5c2: AI 가 제안한 effect 들이 적용된 [EffectStack]. captionEffectIds 와 별개 — 영상 전체
     * LUT, 줌 펀치, 인트로/아웃트로 등이 여기. 실제 렌더 통합은 R5c3 의 RenderPlan 빌더에서.
     */
    val effectStack: EffectStack = EffectStack.EMPTY,
    /**
     * R5c3b: BGM 자동 더킹 활성 여부. true 면 startExport 시 audioAnalysis.loudness 로
     * Ducking.buildVolumeTrack 빌드 → DuckingAudioProcessor 적용. BGM 트랙이 없거나 라우드니스가
     * 없으면 NOOP.
     */
    val autoDuckingEnabled: Boolean = false,
    /**
     * R6 자산 라운드: 채널 톤 테마 id. CaptionEffectResolver 가 이 id 로 ThemePack 조회해
     * 자막 색·폰트·전환을 일제히 적용. 영상 교체 시 유지 — 사용자 채널 정체성은 영상별로 바뀌지
     * 않음.
     */
    val selectedThemeId: String = "studiopop.default",
    /**
     * R6 폴리시: AI 가 만든 썸네일 변형 5장 중 사용자가 메인으로 선택한 id. null 이면 미선택.
     * 새 generatePackage 시 첫 변형으로 자동 초기화. 향후 업로드 시점에 이 id 의 비트맵을 메인
     * 썸네일로 사용.
     */
    val selectedThumbnailVariantId: String? = null,
    /**
     * R6 폴리시: 사용자가 개별 적용한 [editSuggestions] 의 인덱스 집합. 시트의 각 행에서 "추가"
     * 버튼을 누른 항목들. 같은 항목 중복 적용 방지 + UI 가 disabled 표시.
     * 새 패키지 생성/영상 교체 시 자동 초기화.
     */
    val appliedSuggestionIndices: Set<Int> = emptySet(),
) {
    val hasVideo: Boolean get() = timeline.segments.isNotEmpty()
    val canExport: Boolean
        get() = hasVideo && phase !is ExportPhase.Running
    val canDelete: Boolean get() = timeline.segments.size > 1
    /** 전환은 세그먼트 경계가 존재할 때만 의미 — 여러 영상을 합쳤거나 cut 으로 경계가 생겼을 때. */
    val canUseTransitions: Boolean get() = timeline.effectiveSegments().size > 1
}

@OptIn(FlowPreview::class)
@UnstableApi
class EditorViewModel(
    application: Application,
    private val videoEditor: VideoEditor,
    private val frameStripGenerator: FrameStripGenerator,
    private val projectRepository: ProjectRepository,
    private val libraryAssetRepository: LibraryAssetRepository,
    private val timelineSnapshotRepository: TimelineSnapshotRepository,
    private val fixedTemplatePresetRepository: FixedTemplatePresetRepository,
    private val faceTracker: FaceTracker,
    private val vocalSeparator: VocalSeparator,
    private val uvrModelManager: UvrModelManager,
    private val audioAnalysisService: AudioAnalysisService,
    private val aiAssist: AiAssist,
    private val frameExtractor: FrameExtractor,
    private val thumbnailComposer: ThumbnailComposer,
    private val captionWordStore: CaptionWordStore,
) : AndroidViewModel(application) {

    private val _uiState = MutableStateFlow(EditorUiState())
    val uiState: StateFlow<EditorUiState> = _uiState.asStateFlow()

    private var projectId: Long? = null
    /**
     * timeline 자동 저장 활성화 여부. bindProject(id, …) 로 프로젝트 바운드 이후 true.
     * 프로젝트 없이 편집기만 여는 경우엔 저장할 대상이 없어 no-op.
     */
    private var autoSaveEnabled: Boolean = false

    init {
        viewModelScope.launch {
            libraryAssetRepository.observe(LibraryAssetKind.STICKER).collect { list ->
                _uiState.update { it.copy(stickerLibrary = list) }
            }
        }
        viewModelScope.launch {
            libraryAssetRepository.observe(LibraryAssetKind.SFX).collect { list ->
                _uiState.update { it.copy(sfxLibrary = list) }
            }
        }
        viewModelScope.launch {
            projectRepository.observeAllAssetsOfType(AssetType.EXPORT_VIDEO).collect { list ->
                _uiState.update { it.copy(exportedVideoLibrary = list) }
            }
        }
        viewModelScope.launch {
            fixedTemplatePresetRepository.observe().collect { list ->
                _uiState.update { it.copy(fixedTemplatePresets = list) }
            }
        }
        viewModelScope.launch {
            uvrModelManager.state.collect { s ->
                _uiState.update { it.copy(vocalModelState = s) }
            }
        }

        // 타임라인 자동 저장: timeline 변경 시 디바운스 후 DB 에 스냅샷 저장.
        // drop(1) 로 초기 빈 상태 저장 방지. autoSaveEnabled 아닐 땐 skip.
        viewModelScope.launch {
            _uiState
                .map { it.timeline }
                .distinctUntilChanged()
                .drop(1)
                .debounce(TIMELINE_SAVE_DEBOUNCE_MS)
                .collect { tl ->
                    val pid = projectId ?: return@collect
                    if (!autoSaveEnabled) return@collect
                    runCatching { timelineSnapshotRepository.save(pid, tl) }
                }
        }

        // 프리뷰 박스 비율: 첫 세그먼트의 회전-반영 해상도로 갱신. 여러 영상을 이어붙인 경우라도
        // 첫 영상의 frame 좌표계가 export 의 setScale 기준과 동일해 자막·모자이크가 일관되게 매핑.
        // ExoPlayer runtime VideoSize 가 ground truth 라 메타데이터 실패해도 OK — 여기 값은 첫 프레임
        // 직전 잠깐 fallback 으로만 활용됨.
        viewModelScope.launch {
            _uiState
                .map { it.timeline.segments.firstOrNull()?.sourceUri }
                .distinctUntilChanged()
                .collect { uri ->
                    // URI 가 바뀌자마자 옛 ratio 가 새 영상에 잠깐 적용되지 않게 즉시 null 로 리셋.
                    _uiState.update { it.copy(previewAspectRatio = null) }
                    if (uri == null) return@collect
                    val ratio = withContext(Dispatchers.IO) {
                        readFrameSize(uri)?.let { (w, h) ->
                            if (w > 0 && h > 0) w.toFloat() / h.toFloat() else null
                        }
                    }
                    _uiState.update { it.copy(previewAspectRatio = ratio) }
                }
        }
    }

    fun bindProject(id: Long?) {
        if (id == null || id <= 0 || id == projectId) return
        projectId = id
        _uiState.update { it.copy(hasProject = true) }
        viewModelScope.launch {
            val project = projectRepository.getProject(id) ?: return@launch

            // 저장된 편집 중 스냅샷이 있으면 그것으로 복구 (자막·짤·모자이크 등 작업 내용 유지).
            // 없으면 기존 경로: 원본 영상 로드 + 최신 SRT 자동 로드.
            val snapshot = runCatching { timelineSnapshotRepository.load(id) }.getOrNull()
            val restored = if (snapshot != null && snapshot.segments.isNotEmpty()) {
                restoreFromSnapshot(snapshot)
            } else false
            if (!restored) {
                replaceWithVideo(project.sourceVideoUri.toUri())
                val srtAsset = projectRepository.latestAsset(id, AssetType.CAPTION_SRT)
                srtAsset?.let { importSrtFromPath(it.value) }
            }
            refreshQuickLoad()
            backfillOldAssetsSilently(id)

            // 복구·초기 세팅이 끝난 이후부터 자동 저장 활성화 — 복구 중 save 로 덮어쓰지 않게.
            autoSaveEnabled = true
        }
    }

    /**
     * 스냅샷으로부터 타임라인·프리뷰 상태를 복구.
     * PhotoPicker 가 발급하는 content:// URI 는 프로세스 재시작 시 read 권한이 유지되지 않아
     * [MediaMetadataRetriever] 가 실패할 수 있음 — 먼저 각 세그먼트의 유효성을 검사하고,
     * 접근 불가능한 세그먼트는 drop. 모두 불가능하면 false 반환해 상위에서 기본 경로로 fallback.
     *
     * 이미지/오디오/모자이크 등 오버레이 참조 URI 도 일부 무효화될 수 있지만 프리뷰·export 시점에
     * 해당 오버레이만 조용히 실패하므로 편집 자체를 막지는 않아 그대로 유지.
     *
     * @return true 면 복구 성공 — 유효한 세그먼트가 1개 이상 남음. false 면 복구 포기 — 상위 fallback.
     */
    private suspend fun restoreFromSnapshot(snapshot: Timeline): Boolean {
        val uniqueUris = snapshot.segments.map { it.sourceUri }.distinct()
        val uriDurations = mutableMapOf<Uri, Long>()
        for (uri in uniqueUris) {
            uriDurations[uri] = withContext(Dispatchers.IO) { readDurationMs(uri) }
        }
        val validSegments = snapshot.segments.filter { (uriDurations[it.sourceUri] ?: 0L) > 0L }
        if (validSegments.isEmpty()) return false

        // audioTrack 의 file://WAV(추출된 보컬·BGM 등) 가 삭제됐을 수 있음 — 검증 후 null sanitize.
        // 없으면 export·preview 에서 조용히 실패하고 사용자 혼란을 일으키므로 제거하는 게 안전.
        // File.exists() 는 disk stat 이라 main 에서 호출하면 StrictMode 경고 → IO 로 wrap.
        val sanitizedTrack = snapshot.audioTrack?.let { t ->
            if (t.uri.scheme == "file") {
                val path = t.uri.path
                val exists = path != null && withContext(Dispatchers.IO) { File(path).exists() }
                if (exists) t else null
            } else {
                t // content://, http:// 등은 런타임 재생/export 시점에 판단
            }
        }
        val trackChanged = sanitizedTrack != snapshot.audioTrack
        val segmentsChanged = validSegments.size != snapshot.segments.size
        val sanitized = if (!trackChanged && !segmentsChanged) snapshot
            else snapshot.copy(
                segments = if (segmentsChanged) validSegments else snapshot.segments,
                audioTrack = sanitizedTrack,
            )

        _uiState.update {
            it.copy(
                timeline = sanitized,
                playheadOutputMs = 0L,
                isPlaying = false,
                frameStrips = emptyMap(),
                phase = ExportPhase.Idle,
            )
        }
        for ((uri, duration) in uriDurations) {
            if (duration > 0) ensureFrameStrip(uri, duration)
        }
        return true
    }

    /**
     * MediaStore 퍼블리시 도입 이전에 만들어진 기존 asset 을 갤러리/Downloads 에 소급 등록.
     * SharedPreferences 기반 idempotent — 이미 등록된 것은 스킵. 결과는 silently 무시
     * (사용자는 갤러리에서 파일이 보이기 시작하는 것으로 인지).
     */
    private suspend fun backfillOldAssetsSilently(projectId: Long) {
        val app = getApplication<Application>() as? StudioPopApp ?: return
        runCatching { app.container.assetBackfillPublisher.backfill(projectId) }
    }

    /**
     * DB 의 최신 EXPORT_VIDEO / CAPTION_SRT asset 경로를 UI state 에 반영.
     * bindProject + 매 export 성공 후 호출.
     */
    private suspend fun refreshQuickLoad() {
        val pid = projectId ?: return
        val video = projectRepository.latestAsset(pid, AssetType.EXPORT_VIDEO)?.value
        val srt = projectRepository.latestAsset(pid, AssetType.CAPTION_SRT)?.value
        _uiState.update { it.copy(latestExportVideoPath = video, latestSrtPath = srt) }
    }

    /** DB 의 최신 편집 결과 영상을 타임라인 단일 클립으로 재로딩. "두 번째 편집" 진입점. */
    fun loadLatestExportAsInput() {
        val path = _uiState.value.latestExportVideoPath ?: return
        val uri = java.io.File(path).toUri()
        viewModelScope.launch { replaceWithVideo(uri) }
    }

    /** DB 의 최신 SRT 를 현재 타임라인 captions 으로 덮어씀. */
    fun loadLatestSrt() {
        val path = _uiState.value.latestSrtPath ?: return
        importSrtFromPath(path)
    }

    /**
     * 타임라인을 이 영상 단독으로 리셋. 홈 "영상 변경" 에서 사용.
     */
    fun onVideoSelected(uri: Uri?) {
        if (uri == null) return
        viewModelScope.launch { replaceWithVideo(uri) }
    }

    /**
     * 현재 타임라인 끝에 이 영상을 통째로 추가. 여러 영상을 이어붙일 때.
     */
    fun addVideoToTimeline(uri: Uri?) {
        if (uri == null) return
        viewModelScope.launch {
            val duration = withContext(Dispatchers.IO) { readDurationMs(uri) }
            if (duration <= 0) return@launch
            _uiState.update {
                it.copy(
                    timeline = it.timeline.appendVideo(uri, duration),
                    phase = ExportPhase.Idle,
                )
            }
            ensureFrameStrip(uri, duration)
        }
    }

    /**
     * 여러 영상을 **선택한 순서대로** 한 번에 타임라인 끝에 추가.
     * 갤러리 다중 선택(PickMultipleVisualMedia) 결과를 그대로 넘김.
     * 중간에 길이 읽기 실패한 항목은 skip. 모든 유효 항목은 **단일 state update** 로 append
     * 되어 auto-save 디바운스가 N번 트리거되지 않고 한 번만 저장됨.
     */
    fun addVideosToTimeline(uris: List<Uri>) {
        if (uris.isEmpty()) return
        viewModelScope.launch {
            val valid = uris.mapNotNull { uri ->
                val duration = withContext(Dispatchers.IO) { readDurationMs(uri) }
                if (duration > 0) uri to duration else null
            }
            if (valid.isEmpty()) return@launch
            _uiState.update { state ->
                var tl = state.timeline
                for ((uri, dur) in valid) tl = tl.appendVideo(uri, dur)
                state.copy(timeline = tl, phase = ExportPhase.Idle)
            }
            for ((uri, dur) in valid) ensureFrameStrip(uri, dur)
        }
    }

    /**
     * 내 편집본(EXPORT_VIDEO asset) 들을 선택한 순서대로 현재 타임라인 끝에 추가.
     * 파일이 실재하지 않는 항목은 자동 skip. 단일 state update 로 batch append.
     */
    fun appendExportedVideos(assets: List<AssetEntity>) {
        if (assets.isEmpty()) return
        viewModelScope.launch {
            val valid = assets.mapNotNull { asset ->
                val file = java.io.File(asset.value)
                if (!file.exists()) return@mapNotNull null
                val uri = file.toUri()
                val duration = withContext(Dispatchers.IO) { readDurationMs(uri) }
                if (duration > 0) uri to duration else null
            }
            _uiState.update { state ->
                val newTimeline = if (valid.isEmpty()) state.timeline else {
                    var tl = state.timeline
                    for ((uri, dur) in valid) tl = tl.appendVideo(uri, dur)
                    tl
                }
                state.copy(
                    timeline = newTimeline,
                    phase = ExportPhase.Idle,
                    activeSheet = null,
                )
            }
            for ((uri, dur) in valid) ensureFrameStrip(uri, dur)
        }
    }

    private suspend fun replaceWithVideo(uri: Uri) {
        val duration = withContext(Dispatchers.IO) { readDurationMs(uri) }
        // 새 영상 로드 — 이전 영상에 묶여 있던 효과/AI/오디오 분석 결과를 모두 정리.
        // 캡션 효과 매핑은 이전 자막 UUID 키라 새 영상에선 dangling 이고, audioAnalysis 의
        // loudness 곡선은 이전 영상 시간축이라 그대로 더킹에 쓰면 잘못된 적용.
        clearDerivedStateForVideoChange()
        if (duration <= 0) {
            // content:// URI 권한 만료·파일 이동 등으로 영상을 열 수 없는 경우.
            _uiState.update {
                it.copy(
                    timeline = Timeline(segments = emptyList()),
                    playheadOutputMs = 0L,
                    isPlaying = false,
                    frameStrips = emptyMap(),
                    phase = ExportPhase.Error("영상에 접근할 수 없어요. 갤러리에서 다시 선택해주세요."),
                )
            }
            return
        }
        _uiState.update {
            it.copy(
                timeline = Timeline.single(uri, duration),
                playheadOutputMs = 0L,
                isPlaying = false,
                frameStrips = emptyMap(),
                phase = ExportPhase.Idle,
            )
        }
        ensureFrameStrip(uri, duration)
    }

    /**
     * 영상 교체 시 이전 영상에 종속된 파생 state 들을 한 번에 정리. 비트맵은 회수하고,
     * 효과/AI/카라오케/더킹 매핑은 새 자막에 매칭되지 않으므로 비움. 사용자 입력 토글
     * (autoDuckingEnabled) 은 유지 — 새 영상 분석 후 자동 활성화될 수 있음.
     */
    private fun clearDerivedStateForVideoChange() {
        val current = _uiState.value
        current.thumbnailPreviewBitmaps.values.forEach { bmp ->
            if (!bmp.isRecycled) bmp.recycle()
        }
        _uiState.update {
            it.copy(
                captionEffectIds = emptyMap(),
                captionBeatSyncIds = emptySet(),
                captionKaraokeIds = emptySet(),
                captionWords = emptyMap(),
                audioAnalysis = null,
                isAnalyzingAudio = false,
                youtubePackage = null,
                editSuggestions = emptyList(),
                aiPackagePhase = AiPackagePhase.Idle,
                showAiPackageSheet = false,
                thumbnailPreviewBitmaps = emptyMap(),
                effectStack = com.mingeek.studiopop.data.effects.EffectStack.EMPTY,
                selectedThumbnailVariantId = null,
                appliedSuggestionIndices = emptySet(),
            )
        }
    }

    private fun ensureFrameStrip(uri: Uri, duration: Long) {
        if (_uiState.value.frameStrips.containsKey(uri)) return
        viewModelScope.launch {
            val strip = frameStripGenerator.generate(uri, duration)
            _uiState.update { s ->
                s.copy(frameStrips = s.frameStrips + (uri to (duration to strip)))
            }
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
        // 첫 segment uri 가 있으면 CaptionWordStore 에서 매칭되는 word 를 자막별로 추출 — 카라오케
        // 가 실제 발화 속도로 동작하도록. store 가 비어 있거나 다른 영상이면 빈 맵 → fakeWordTimings
        // fallback.
        val firstUri = _uiState.value.timeline.segments.firstOrNull()?.sourceUri
        val captionWords: Map<String, List<CueWord>> = if (firstUri != null) {
            timelineCaptions.associate { c ->
                c.id to captionWordStore.wordsInRange(firstUri, c.sourceStartMs, c.sourceEndMs)
            }.filterValues { it.isNotEmpty() }
        } else emptyMap()

        // 새 자막 UUID 가 발급되므로 이전 캡션에 매핑된 효과/펄스/카라오케는 dangling — 정리.
        _uiState.update { state ->
            state.copy(
                timeline = state.timeline.copy(captions = timelineCaptions),
                captionEffectIds = emptyMap(),
                captionBeatSyncIds = emptySet(),
                captionKaraokeIds = emptySet(),
                captionWords = captionWords,
            )
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
    fun onDividerDrag(prevSegId: String, nextSegId: String, sourceDeltaMs: Long) {
        _uiState.update {
            val newTimeline = it.timeline.moveBoundary(prevSegId, nextSegId, sourceDeltaMs)
            // 경계 이동으로 출력 길이가 바뀌었을 수 있으므로 플레이헤드 보정
            val newPlayhead = it.playheadOutputMs.coerceAtMost(newTimeline.outputDurationMs)
            it.copy(timeline = newTimeline, playheadOutputMs = newPlayhead)
        }
    }

    // --- 범위 삭제 (CutRange) ---
    /**
     * 현재 플레이헤드 위치에 기본 duration 의 CutRange 생성.
     */
    fun addCutRangeAtPlayhead() {
        val state = _uiState.value
        val (seg, sourceT) = state.timeline.mapOutputToSource(state.playheadOutputMs) ?: return
        val srcEnd = (sourceT + DEFAULT_CUT_DURATION_MS).coerceAtMost(seg.sourceEndMs)
        val cut = CutRange(
            sourceUri = seg.sourceUri,
            sourceStartMs = sourceT,
            sourceEndMs = srcEnd,
        )
        _uiState.update { it.copy(timeline = it.timeline.addCutRange(cut)) }
    }

    fun onCutRangeResize(id: String, startDeltaMs: Long, endDeltaMs: Long) {
        _uiState.update { state ->
            val cut = state.timeline.cutRanges.firstOrNull { it.id == id } ?: return@update state
            val (minStart, maxEnd) = sourceBoundsFor(state.timeline, cut.sourceUri)
                ?: return@update state
            val newStart = (cut.sourceStartMs + startDeltaMs)
                .coerceIn(minStart, maxEnd - MIN_OVERLAY_DURATION_MS)
            val newEnd = (cut.sourceEndMs + endDeltaMs)
                .coerceIn(newStart + MIN_OVERLAY_DURATION_MS, maxEnd)
            state.copy(timeline = state.timeline.updateCutRange(cut.copy(sourceStartMs = newStart, sourceEndMs = newEnd)))
        }
    }

    fun onCutRangeTranslate(id: String, deltaMs: Long) {
        _uiState.update { state ->
            val cut = state.timeline.cutRanges.firstOrNull { it.id == id } ?: return@update state
            val (minStart, maxEnd) = sourceBoundsFor(state.timeline, cut.sourceUri)
                ?: return@update state
            val duration = cut.sourceEndMs - cut.sourceStartMs
            val newStart = (cut.sourceStartMs + deltaMs)
                .coerceIn(minStart, maxEnd - duration)
            state.copy(timeline = state.timeline.updateCutRange(
                cut.copy(sourceStartMs = newStart, sourceEndMs = newStart + duration)
            ))
        }
    }

    /** 주어진 sourceUri 를 참조하는 segment 들의 source 범위 합집합 [min, max]. */
    private fun sourceBoundsFor(timeline: Timeline, uri: Uri): Pair<Long, Long>? {
        val matching = timeline.segments.filter { it.sourceUri == uri }
        if (matching.isEmpty()) return null
        return matching.minOf { it.sourceStartMs } to matching.maxOf { it.sourceEndMs }
    }

    fun deleteCutRange(id: String) {
        _uiState.update { it.copy(timeline = it.timeline.deleteCutRange(id)) }
    }

    /**
     * 현재 플레이헤드가 위치한 세그먼트를 삭제. 여러 영상 추가한 상태에서 특정 영상 통째로 빼기.
     */
    fun deleteCurrentSegment() {
        val state = _uiState.value
        if (state.timeline.segments.size <= 1) return
        val (seg, _) = state.timeline.mapOutputToSource(state.playheadOutputMs) ?: return
        _uiState.update {
            val newTimeline = it.timeline.deleteSegment(seg.id)
            val newPlayhead = it.playheadOutputMs.coerceAtMost(newTimeline.outputDurationMs)
            it.copy(
                timeline = newTimeline,
                playheadOutputMs = newPlayhead,
                seekRequest = newPlayhead,
            )
        }
    }

    // --- 자막 (CAPTION) ---
    fun openCaptionEditorForNew() {
        val state = _uiState.value
        val (seg, sourceT) = state.timeline.mapOutputToSource(state.playheadOutputMs)
            ?: return
        val srcEnd = (sourceT + DEFAULT_NEW_DURATION_MS).coerceAtMost(seg.sourceEndMs)
        val cap = TimelineCaption(
            sourceStartMs = sourceT,
            sourceEndMs = srcEnd,
            text = "",
        )
        _uiState.update {
            it.copy(
                editingItem = cap.toEditable(existsInTimeline = false),
                editingKind = EditKind.CAPTION,
            )
        }
    }

    fun openCaptionEditorFor(id: String) {
        val cap = _uiState.value.timeline.captions.firstOrNull { it.id == id } ?: return
        _uiState.update {
            it.copy(
                editingItem = cap.toEditable(existsInTimeline = true),
                editingKind = EditKind.CAPTION,
            )
        }
    }

    // --- 텍스트 레이어 ---
    fun openTextLayerEditorForNew() {
        val state = _uiState.value
        val (seg, sourceT) = state.timeline.mapOutputToSource(state.playheadOutputMs)
            ?: return
        val srcEnd = (sourceT + DEFAULT_NEW_DURATION_MS).coerceAtMost(seg.sourceEndMs)
        val layer = TextLayer(
            sourceStartMs = sourceT,
            sourceEndMs = srcEnd,
            text = "",
        )
        _uiState.update {
            it.copy(
                editingItem = layer.toEditable(existsInTimeline = false),
                editingKind = EditKind.TEXT_LAYER,
            )
        }
    }

    fun openTextLayerEditorFor(id: String) {
        val layer = _uiState.value.timeline.textLayers.firstOrNull { it.id == id } ?: return
        _uiState.update {
            it.copy(
                editingItem = layer.toEditable(existsInTimeline = true),
                editingKind = EditKind.TEXT_LAYER,
            )
        }
    }

    fun closeEditor() = _uiState.update { it.copy(editingItem = null, editingKind = null) }

    fun saveEditingItem(updated: EditableTextItem) {
        val state = _uiState.value
        val kind = state.editingKind ?: return
        val newTimeline = when (kind) {
            EditKind.CAPTION -> {
                val caption = TimelineCaption(
                    id = updated.id,
                    sourceStartMs = updated.sourceStartMs,
                    sourceEndMs = updated.sourceEndMs,
                    text = updated.text,
                    style = updated.style,
                )
                if (updated.existsInTimeline) state.timeline.updateCaption(caption)
                else state.timeline.addCaption(caption)
            }
            EditKind.TEXT_LAYER -> {
                val layer = TextLayer(
                    id = updated.id,
                    sourceStartMs = updated.sourceStartMs,
                    sourceEndMs = updated.sourceEndMs,
                    text = updated.text,
                    style = updated.style,
                )
                if (updated.existsInTimeline) state.timeline.updateTextLayer(layer)
                else state.timeline.addTextLayer(layer)
            }
        }
        _uiState.update {
            it.copy(timeline = newTimeline, editingItem = null, editingKind = null)
        }
    }

    /**
     * 타임라인에서 자막 막대 양끝 드래그 → 시각 범위 조정.
     * @param startDeltaMs sourceStartMs 에 더할 값 (왼쪽 핸들)
     * @param endDeltaMs sourceEndMs 에 더할 값 (오른쪽 핸들)
     */
    fun onCaptionResize(id: String, startDeltaMs: Long, endDeltaMs: Long) {
        _uiState.update { state ->
            val cap = state.timeline.captions.firstOrNull { it.id == id } ?: return@update state
            val newStart = (cap.sourceStartMs + startDeltaMs).coerceAtLeast(0L)
            val newEnd = (cap.sourceEndMs + endDeltaMs).coerceAtLeast(newStart + MIN_OVERLAY_DURATION_MS)
            state.copy(timeline = state.timeline.updateCaption(cap.copy(sourceStartMs = newStart, sourceEndMs = newEnd)))
        }
    }

    fun onTextLayerResize(id: String, startDeltaMs: Long, endDeltaMs: Long) {
        _uiState.update { state ->
            val layer = state.timeline.textLayers.firstOrNull { it.id == id } ?: return@update state
            val newStart = (layer.sourceStartMs + startDeltaMs).coerceAtLeast(0L)
            val newEnd = (layer.sourceEndMs + endDeltaMs).coerceAtLeast(newStart + MIN_OVERLAY_DURATION_MS)
            state.copy(timeline = state.timeline.updateTextLayer(layer.copy(sourceStartMs = newStart, sourceEndMs = newEnd)))
        }
    }

    /**
     * 타임라인 막대 롱프레스 후 드래그 → 전체 평행이동 (duration 유지).
     */
    fun onCaptionTranslate(id: String, deltaMs: Long) {
        _uiState.update { state ->
            val cap = state.timeline.captions.firstOrNull { it.id == id } ?: return@update state
            val newStart = (cap.sourceStartMs + deltaMs).coerceAtLeast(0L)
            val duration = cap.sourceEndMs - cap.sourceStartMs
            state.copy(timeline = state.timeline.updateCaption(
                cap.copy(sourceStartMs = newStart, sourceEndMs = newStart + duration)
            ))
        }
    }

    fun onTextLayerTranslate(id: String, deltaMs: Long) {
        _uiState.update { state ->
            val layer = state.timeline.textLayers.firstOrNull { it.id == id } ?: return@update state
            val newStart = (layer.sourceStartMs + deltaMs).coerceAtLeast(0L)
            val duration = layer.sourceEndMs - layer.sourceStartMs
            state.copy(timeline = state.timeline.updateTextLayer(
                layer.copy(sourceStartMs = newStart, sourceEndMs = newStart + duration)
            ))
        }
    }

    /**
     * 미리보기 세로 드래그 → anchorY 조정 (NDC -1..1).
     */
    fun onCaptionAnchorChange(id: String, newAnchorY: Float) {
        _uiState.update { state ->
            val cap = state.timeline.captions.firstOrNull { it.id == id } ?: return@update state
            val clipped = newAnchorY.coerceIn(-1f, 1f)
            state.copy(timeline = state.timeline.updateCaption(cap.copy(style = cap.style.copy(anchorY = clipped))))
        }
    }

    fun onTextLayerAnchorChange(id: String, newAnchorY: Float) {
        _uiState.update { state ->
            val layer = state.timeline.textLayers.firstOrNull { it.id == id } ?: return@update state
            val clipped = newAnchorY.coerceIn(-1f, 1f)
            state.copy(timeline = state.timeline.updateTextLayer(layer.copy(style = layer.style.copy(anchorY = clipped))))
        }
    }

    fun deleteEditingItem() {
        val state = _uiState.value
        val id = state.editingItem?.id ?: return
        val kind = state.editingKind ?: return
        val newTimeline = when (kind) {
            EditKind.CAPTION -> state.timeline.deleteCaption(id)
            EditKind.TEXT_LAYER -> state.timeline.deleteTextLayer(id)
        }
        _uiState.update {
            it.copy(
                timeline = newTimeline,
                editingItem = null,
                editingKind = null,
                // 삭제된 자막의 효과/비트 펄스 매핑도 정리 — 댕글링 키 누적 방지
                captionEffectIds = if (kind == EditKind.CAPTION)
                    it.captionEffectIds - id
                else it.captionEffectIds,
                captionBeatSyncIds = if (kind == EditKind.CAPTION)
                    it.captionBeatSyncIds - id
                else it.captionBeatSyncIds,
                captionKaraokeIds = if (kind == EditKind.CAPTION)
                    it.captionKaraokeIds - id
                else it.captionKaraokeIds,
                captionWords = if (kind == EditKind.CAPTION)
                    it.captionWords - id
                else it.captionWords,
            )
        }
    }

    /**
     * 자막에 자막 스타일 효과 적용. effectId == null 이면 효과 제거(기본 스타일로 복원).
     * 효과 id 는 [com.mingeek.studiopop.data.effects.builtins.CaptionStylePresets] 의 상수 사용.
     */
    fun setCaptionEffect(captionId: String, effectId: String?) {
        _uiState.update { state ->
            val current = state.captionEffectIds
            val updated = if (effectId == null) current - captionId
                else current + (captionId to effectId)
            state.copy(captionEffectIds = updated)
        }
    }

    /**
     * R4.5: 자막에 비트 펄스 적용 여부. enabled=false 면 집합에서 제거. 효과 미적용 자막에선
     * 의미 없으므로 시트 UI 가 disabled 상태 처리.
     */
    fun setCaptionBeatSync(captionId: String, enabled: Boolean) {
        _uiState.update { state ->
            val updated = if (enabled) state.captionBeatSyncIds + captionId
                else state.captionBeatSyncIds - captionId
            state.copy(captionBeatSyncIds = updated)
        }
    }

    /**
     * R5c3a: 자막에 카라오케 모드 적용 여부.
     */
    fun setCaptionKaraoke(captionId: String, enabled: Boolean) {
        _uiState.update { state ->
            val updated = if (enabled) state.captionKaraokeIds + captionId
                else state.captionKaraokeIds - captionId
            state.copy(captionKaraokeIds = updated)
        }
    }

    /**
     * R5c1: 한 번 호출로 영상 분석 + AI YouTube 패키지 생성 (제목/설명/챕터/태그/썸네일/숏츠).
     * 결과는 [EditorUiState.youtubePackage] 와 [editSuggestions] 에 저장되고
     * [showAiPackageSheet] 가 true 가 되어 UI 가 시트를 띄운다.
     *
     * SRT 큐는 timeline.captions 를 변환해 사용 — 자동 자막을 미리 생성한 사용자만 챕터/하이라이트
     * 가 풍부해진다 (큐 없으면 키워드/하이라이트 없이 진행).
     *
     * 실패 시 [AiPackagePhase.Failed] 로 전환되며 시트는 열리지 않음. 사용자에게 별도 토스트 표시는
     * 호출 측 UI 가 결정.
     */
    fun generateYoutubePackage(topic: String) {
        val state = _uiState.value
        val firstSeg = state.timeline.segments.firstOrNull() ?: return
        if (state.aiPackagePhase is AiPackagePhase.Analyzing ||
            state.aiPackagePhase is AiPackagePhase.Generating
        ) return // 이미 진행 중

        viewModelScope.launch {
            _uiState.update { it.copy(aiPackagePhase = AiPackagePhase.Analyzing) }
            val cues = state.timeline.captions
                .sortedBy { it.sourceStartMs }
                .mapIndexed { idx, c ->
                    Cue(
                        index = idx + 1,
                        startMs = c.sourceStartMs,
                        endMs = c.sourceEndMs,
                        text = c.text,
                    )
                }
            val durationMs = state.timeline.outputDurationMs
                .takeIf { it > 0L } ?: firstSeg.durationMs

            // 1) 분석
            val assist = aiAssist as? DefaultAiAssist
            val analysisResult = if (assist != null) {
                assist.analyzeVideoWithContext(
                    uri = firstSeg.sourceUri,
                    srtCues = cues,
                    topic = topic.ifBlank { null },
                )
            } else {
                aiAssist.analyzeVideo(firstSeg.sourceUri)
            }
            val analysis = analysisResult.getOrElse {
                _uiState.update { s ->
                    s.copy(aiPackagePhase = AiPackagePhase.Failed(it.message ?: "영상 분석 실패"))
                }
                return@launch
            }

            _uiState.update { it.copy(aiPackagePhase = AiPackagePhase.Generating) }

            // 2) 패키지 생성 + 편집 제안 — 둘 다 한 번 시도. 어느 한쪽 실패는 흡수.
            val packageResult = aiAssist.generatePackage(analysis, topic.ifBlank { null })
            val suggestionsResult = aiAssist.suggestEdits(analysis)

            val ytPackage = packageResult.getOrElse {
                _uiState.update { s ->
                    s.copy(aiPackagePhase = AiPackagePhase.Failed(it.message ?: "패키지 생성 실패"))
                }
                return@launch
            }
            val suggestions = suggestionsResult.getOrDefault(emptyList())

            // 3) 썸네일 비트맵 합성 — 첫 키프레임을 source 로 각 variant 합성. 실패 시 빈 맵.
            val thumbBitmaps = composeThumbnailBitmaps(
                uri = firstSeg.sourceUri,
                durationMs = durationMs,
                variants = ytPackage.thumbnailVariants,
            )

            // 이전 합성 결과(있다면) 회수 — 같은 영상 재생성/주제 변경 시 누적 방지.
            val previousBitmaps = _uiState.value.thumbnailPreviewBitmaps
            previousBitmaps.values.forEach { bmp ->
                if (!bmp.isRecycled) bmp.recycle()
            }

            _uiState.update {
                it.copy(
                    youtubePackage = ytPackage,
                    editSuggestions = suggestions,
                    thumbnailPreviewBitmaps = thumbBitmaps,
                    aiPackagePhase = AiPackagePhase.Idle,
                    showAiPackageSheet = true,
                    // 첫 합성 변형을 기본 메인 썸네일로 — 사용자가 시트에서 다른 걸 누르면 갱신
                    selectedThumbnailVariantId = ytPackage.thumbnailVariants.firstOrNull()?.id
                        ?: it.selectedThumbnailVariantId,
                    // 새 패키지의 제안은 미적용 상태로 시작
                    appliedSuggestionIndices = emptySet(),
                )
            }
        }
    }

    /**
     * 첫 키프레임 1장을 source 로, 각 [com.mingeek.studiopop.data.thumbnail.ThumbnailVariant] 를
     * 1280×720 비트맵으로 합성. 실패한 항목은 결과 맵에 빠짐 — 일부 실패가 전체를 막지 않게.
     */
    private suspend fun composeThumbnailBitmaps(
        uri: android.net.Uri,
        durationMs: Long,
        variants: List<com.mingeek.studiopop.data.thumbnail.ThumbnailVariant>,
    ): Map<String, Bitmap> {
        if (variants.isEmpty() || durationMs <= 0L) return emptyMap()
        // 영상의 1/3 지점을 source — 첫 프레임이 검정인 경우가 많아 살짝 안쪽이 안전.
        val sourceFrame = frameExtractor.extractFrame(uri, durationMs / 3)
            ?: return emptyMap()
        val out = mutableMapOf<String, Bitmap>()
        for (v in variants) {
            runCatching { thumbnailComposer.compose(sourceFrame, v) }
                .onSuccess { out[v.id] = it }
        }
        // source 는 합성 함수가 소비/recycle 하지 않으므로 우리가 관리. 합성 후 더 이상 필요 없음.
        if (!sourceFrame.isRecycled) sourceFrame.recycle()
        return out
    }

    fun closeAiPackageSheet() {
        _uiState.update { it.copy(showAiPackageSheet = false) }
    }

    /** 실패 다이얼로그를 닫고 phase 를 Idle 로 되돌린다. */
    fun dismissAiPackageError() {
        _uiState.update { it.copy(aiPackagePhase = AiPackagePhase.Idle) }
    }

    fun reopenAiPackageSheet() {
        if (_uiState.value.youtubePackage != null) {
            _uiState.update { it.copy(showAiPackageSheet = true) }
        }
    }

    /**
     * R5c1: editSuggestions 의 AddCaption 제안만 자동 적용 (가장 안전한 부분).
     * AddCaption(ML Kit 위치 회피용) 은 새 TimelineCaption 으로 추가.
     */
    fun applyCaptionSuggestions() {
        val state = _uiState.value
        val captionIndices = state.editSuggestions
            .withIndex()
            .filter { it.value is EditSuggestion.AddCaption }
            .map { it.index }
        if (captionIndices.isEmpty()) return
        var newTimeline = state.timeline
        for (idx in captionIndices) {
            val s = state.editSuggestions[idx] as EditSuggestion.AddCaption
            val cap = TimelineCaption(
                sourceStartMs = s.sourceStartMs,
                sourceEndMs = s.sourceEndMs,
                text = s.text,
                style = CaptionStyle.DEFAULT,
            )
            newTimeline = newTimeline.addCaption(cap)
        }
        _uiState.update {
            it.copy(
                timeline = newTimeline,
                appliedSuggestionIndices = it.appliedSuggestionIndices + captionIndices,
            )
        }
    }

    /**
     * R6 폴리시: 단일 제안을 인덱스로 적용. 같은 인덱스 재호출은 무시 (이미 적용됨).
     * 적용 종류는 [EditSuggestion] 종류에 따라 분기 — AddCaption 은 Timeline 에 자막 추가,
     * AddEffect 는 effectStack 에 instance 추가. 다른 종류(AddCut/AddSfx/ApplyTheme) 는 R6+
     * 에서 처리.
     */
    fun applySuggestionAt(index: Int) {
        val state = _uiState.value
        if (index in state.appliedSuggestionIndices) return
        val s = state.editSuggestions.getOrNull(index) ?: return
        when (s) {
            is EditSuggestion.AddCaption -> {
                val cap = TimelineCaption(
                    sourceStartMs = s.sourceStartMs,
                    sourceEndMs = s.sourceEndMs,
                    text = s.text,
                    style = CaptionStyle.DEFAULT,
                )
                _uiState.update {
                    it.copy(
                        timeline = it.timeline.addCaption(cap),
                        appliedSuggestionIndices = it.appliedSuggestionIndices + index,
                    )
                }
            }
            is EditSuggestion.AddEffect -> {
                val instance = EffectInstance(
                    definitionId = s.effectDefinitionId,
                    sourceStartMs = s.sourceStartMs,
                    sourceEndMs = s.sourceEndMs,
                    params = com.mingeek.studiopop.data.effects.EffectParamValues(s.params),
                )
                _uiState.update {
                    it.copy(
                        effectStack = it.effectStack.add(instance),
                        appliedSuggestionIndices = it.appliedSuggestionIndices + index,
                    )
                }
            }
            // AddCut/AddSfx/ApplyTheme 는 R6+ 미지원 — 적용 무시. UI 가 사전에 disabled 라
            // 호출 자체가 안 들어와야 정상이지만 방어적으로 noop.
            else -> Unit
        }
    }

    /**
     * R6 폴리시 보조: 시트 UI 가 미지원 종류를 "예정" 으로 표시할 수 있게 헬퍼.
     */
    fun isSuggestionSupported(suggestion: EditSuggestion): Boolean = when (suggestion) {
        is EditSuggestion.AddCaption -> true
        is EditSuggestion.AddEffect -> true
        else -> false
    }

    /**
     * R5c2: editSuggestions 의 AddEffect 제안을 [effectStack] 에 일괄 등록. 실제 렌더 적용은
     * R5c3 의 RenderPlan 빌더 통합에서 — 여기서는 데이터만 보관해서 사용자가 적용했음을
     * UI 가 인지할 수 있게 한다. 같은 정의 id 가 이미 적용돼 있어도 시각적 충돌이 없으면
     * 중복 추가는 허용 (사용자가 명시적으로 다시 적용).
     */
    fun applyEffectSuggestions() {
        val state = _uiState.value
        val effectIndices = state.editSuggestions
            .withIndex()
            .filter { it.value is EditSuggestion.AddEffect }
            .map { it.index }
        if (effectIndices.isEmpty()) return
        var stack = state.effectStack
        for (idx in effectIndices) {
            val s = state.editSuggestions[idx] as EditSuggestion.AddEffect
            stack = stack.add(
                EffectInstance(
                    definitionId = s.effectDefinitionId,
                    sourceStartMs = s.sourceStartMs,
                    sourceEndMs = s.sourceEndMs,
                    // 제안의 파라미터(예: LUT id) 를 인스턴스로 보존 — 손실 시 효과 정체성 사라짐.
                    params = com.mingeek.studiopop.data.effects.EffectParamValues(s.params),
                )
            )
        }
        _uiState.update {
            it.copy(
                effectStack = stack,
                appliedSuggestionIndices = it.appliedSuggestionIndices + effectIndices,
            )
        }
    }

    /** 진행 중인 오디오 분석 Job — 영상 교체 시 cancel 해 stale 결과로 덮이는 race 방지. */
    private var audioAnalysisJob: kotlinx.coroutines.Job? = null

    /**
     * R4.5: 영상의 오디오 분석을 시작 (비트/라우드니스/파형). 결과는 [AnalysisCache] 에 저장돼
     * 같은 Uri 재호출 시 즉시 반환된다. 영상 첫 로드/교체 시 EditorScreen 의 LaunchedEffect 가
     * 자동 트리거.
     *
     * 사용자가 빠르게 영상을 교체하면 이전 분석 Job 을 즉시 cancel — 늦게 도착한 stale 결과가
     * 새 영상의 분석을 덮어쓰지 않게.
     */
    fun analyzeFirstSegmentAudio() {
        val state = _uiState.value
        val firstSeg = state.timeline.segments.firstOrNull() ?: return
        val targetUri = firstSeg.sourceUri
        if (state.audioAnalysis?.sourceUri == targetUri) return // 캐시 hit

        audioAnalysisJob?.cancel()
        audioAnalysisJob = viewModelScope.launch {
            _uiState.update { it.copy(isAnalyzingAudio = true) }
            val durationMs = firstSeg.durationMs
            val analysis = runCatching {
                audioAnalysisService.analyze(targetUri, durationMs)
            }.getOrNull()
            // 결과 적용 직전에 영상이 또 바뀌었으면 무시 — defensive
            val currentUri = _uiState.value.timeline.segments.firstOrNull()?.sourceUri
            if (currentUri == targetUri) {
                _uiState.update { it.copy(audioAnalysis = analysis, isAnalyzingAudio = false) }
            } else {
                _uiState.update { it.copy(isAnalyzingAudio = false) }
            }
        }
    }

    // --- 전환 ---
    /**
     * null → 전환 끔. 그 외 kind → 해당 효과로 켬.
     */
    fun selectTransitionKind(
        kind: TransitionKind?,
    ) {
        _uiState.update {
            val base = it.timeline.transitions
            val t = if (kind == null) {
                base.copy(enabled = false)
            } else {
                base.copy(enabled = true, kind = kind)
            }
            it.copy(timeline = it.timeline.withTransitions(t))
        }
    }

    // --- BGM ---
    fun onBgmPicked(uri: Uri?) {
        if (uri == null) return
        _uiState.update {
            it.copy(
                timeline = it.timeline.withAudioTrack(
                    AudioTrack(uri = uri, replaceOriginal = true)
                )
            )
        }
    }

    fun removeBgm() {
        _uiState.update { it.copy(timeline = it.timeline.withAudioTrack(null)) }
    }

    // --- 오디오 믹싱 (볼륨/센터추출) ---
    fun setOriginalVolume(v: Float) {
        _uiState.update { it.copy(timeline = it.timeline.withOriginalVolume(v)) }
    }

    fun setBgmVolume(v: Float) {
        _uiState.update { it.copy(timeline = it.timeline.withBgmVolume(v)) }
    }

    fun setSfxVolume(id: String, v: Float) {
        _uiState.update { state ->
            val clip = state.timeline.sfxClips.firstOrNull { it.id == id } ?: return@update state
            state.copy(timeline = state.timeline.updateSfxClip(clip.copy(volume = v.coerceIn(0f, 4f))))
        }
    }

    fun setExtractCenterChannel(enabled: Boolean) {
        _uiState.update { it.copy(timeline = it.timeline.withExtractCenterChannel(enabled)) }
    }

    /**
     * 원본 영상에서 보컬(사람 소리) 만 추출해 AudioTrack 으로 교체.
     * UVR MDX-Net ONNX 모델 기반. 최초 1회 모델 다운로드(~30MB) 필요.
     *
     * 전제: **단일 세그먼트 타임라인** 에서만 동작. 세그먼트 여러 개면 vocals WAV 가 어느
     * 영상에 매핑되는지 모호해 export 품질이 깨짐 — UI 에서 버튼 비활성화.
     *
     * 세그먼트의 [sourceStartMs, sourceEndMs] trim 범위만 분리해 길이 정확히 매칭.
     *
     * 성공 시:
     *  - [Timeline.audioTrack] = 추출된 vocals WAV (replaceOriginal=true)
     *  - [Timeline.extractCenterChannel] = false (ML 분리 쓰므로 DSP 트릭 불필요)
     *  - 기존 vocals WAV 파일들은 정리 (C1).
     */
    fun extractVocalsFromFirstSegment() {
        val state = _uiState.value
        if (state.timeline.segments.size != 1) return  // 단일 세그먼트 전용
        val segment = state.timeline.segments.first()
        if (state.vocalExtractProgress != null) return

        viewModelScope.launch {
            _uiState.update { it.copy(vocalExtractProgress = 0f, phase = ExportPhase.Idle) }
            val progressJob = launch {
                vocalSeparator.progress.collect { p ->
                    _uiState.update { it.copy(vocalExtractProgress = p) }
                }
            }
            val result = vocalSeparator.extractVocals(
                videoUri = segment.sourceUri,
                sourceStartMs = segment.sourceStartMs,
                sourceEndMs = segment.sourceEndMs,
            )
            progressJob.cancel()

            result.fold(
                onSuccess = { wav ->
                    // 이전 vocals WAV 들 정리 (새 것 제외). 레지던트 앱 용량 누수 방지.
                    pruneOldVocalWavs(keep = wav)
                    _uiState.update { s ->
                        s.copy(
                            vocalExtractProgress = null,
                            timeline = s.timeline
                                .withAudioTrack(
                                    AudioTrack(
                                        uri = wav.toUri(),
                                        replaceOriginal = true,
                                        volume = 1f,
                                        isVocalExtraction = true,
                                    )
                                )
                                .withExtractCenterChannel(false),
                            phase = ExportPhase.Success("사람 소리 추출 완료"),
                        )
                    }
                },
                onFailure = { e ->
                    _uiState.update {
                        it.copy(
                            vocalExtractProgress = null,
                            phase = ExportPhase.Error("추출 실패: ${e.message ?: "알 수 없는 오류"}"),
                        )
                    }
                },
            )
        }
    }

    /** filesDir/vocals/ 아래 WAV 중 [keep] 과 다른 것은 삭제. silent failure. */
    private fun pruneOldVocalWavs(keep: File) {
        runCatching {
            keep.parentFile?.listFiles()?.forEach { f ->
                if (f.isFile && f.name.endsWith(".wav") && f.absolutePath != keep.absolutePath) {
                    f.delete()
                }
            }
        }
    }

    fun cancelVocalExtract() {
        // MVP: 진행 중 취소는 viewModelScope 종료 외엔 따로 handle 안 함.
        // 필요 시 VocalSeparator 에 CancellationToken 추가.
    }

    /** BGM 이 원본과 함께 믹스될지 / 원본 대신 쓸지 토글. */
    fun setBgmReplaceOriginal(replace: Boolean) {
        _uiState.update { state ->
            val track = state.timeline.audioTrack ?: return@update state
            state.copy(timeline = state.timeline.withAudioTrack(track.copy(replaceOriginal = replace)))
        }
    }

    // --- 서브시트 토글 ---
    fun openSheet(sheet: EditorSheet) = _uiState.update { it.copy(activeSheet = sheet) }
    fun closeSheet() = _uiState.update { it.copy(activeSheet = null) }

    // --- 짤(ImageLayer) ---
    /**
     * 라이브러리에서 선택한 짤을 현재 플레이헤드 위치에 추가. 기본 2초 지속.
     */
    fun addImageLayerFromLibrary(asset: LibraryAssetEntity) {
        val state = _uiState.value
        val (seg, sourceT) = state.timeline.mapOutputToSource(state.playheadOutputMs) ?: return
        val srcEnd = (sourceT + DEFAULT_NEW_DURATION_MS).coerceAtMost(seg.sourceEndMs)
        val uri = File(asset.filePath).toUri()
        val layer = ImageLayer(
            sourceStartMs = sourceT,
            sourceEndMs = srcEnd,
            imageUri = uri,
        )
        _uiState.update {
            it.copy(
                timeline = it.timeline.addImageLayer(layer),
                selectedImageLayerId = layer.id,
                activeSheet = null,
            )
        }
    }

    fun updateImageLayer(layer: ImageLayer) {
        _uiState.update { it.copy(timeline = it.timeline.updateImageLayer(layer)) }
    }

    /**
     * 프리뷰 제스처(드래그 + 핀치 + 회전) 통합 업데이트.
     * pan/zoom/rotation 을 각각 delta 형태로 받아 scale·rotation 누적, centerX/Y 는 새 절대값.
     */
    fun transformImageLayer(
        id: String,
        newCenterX: Float,
        newCenterY: Float,
        zoomDelta: Float,
        rotationDelta: Float,
    ) {
        _uiState.update { state ->
            val layer = state.timeline.imageLayers.firstOrNull { it.id == id } ?: return@update state
            val newScale = (layer.scale * zoomDelta).coerceIn(0.05f, 1f)
            state.copy(timeline = state.timeline.updateImageLayer(
                layer.copy(
                    centerX = newCenterX.coerceIn(-1f, 1f),
                    centerY = newCenterY.coerceIn(-1f, 1f),
                    scale = newScale,
                    rotationDeg = layer.rotationDeg + rotationDelta,
                )
            ))
        }
    }

    fun deleteImageLayer(id: String) {
        _uiState.update {
            it.copy(
                timeline = it.timeline.deleteImageLayer(id),
                selectedImageLayerId = if (it.selectedImageLayerId == id) null else it.selectedImageLayerId,
            )
        }
    }

    fun selectImageLayer(id: String?) = _uiState.update { it.copy(selectedImageLayerId = id) }

    fun onImageLayerResize(id: String, startDeltaMs: Long, endDeltaMs: Long) {
        _uiState.update { state ->
            val layer = state.timeline.imageLayers.firstOrNull { it.id == id } ?: return@update state
            val newStart = (layer.sourceStartMs + startDeltaMs).coerceAtLeast(0L)
            val newEnd = (layer.sourceEndMs + endDeltaMs).coerceAtLeast(newStart + MIN_OVERLAY_DURATION_MS)
            state.copy(timeline = state.timeline.updateImageLayer(layer.copy(sourceStartMs = newStart, sourceEndMs = newEnd)))
        }
    }

    fun onImageLayerTranslate(id: String, deltaMs: Long) {
        _uiState.update { state ->
            val layer = state.timeline.imageLayers.firstOrNull { it.id == id } ?: return@update state
            val newStart = (layer.sourceStartMs + deltaMs).coerceAtLeast(0L)
            val duration = layer.sourceEndMs - layer.sourceStartMs
            state.copy(timeline = state.timeline.updateImageLayer(
                layer.copy(sourceStartMs = newStart, sourceEndMs = newStart + duration)
            ))
        }
    }

    // --- 효과음(SfxClip) ---
    /**
     * 라이브러리에서 선택한 효과음을 현재 플레이헤드 지점에 삽입.
     * SFX 자체 길이가 있으면 그만큼, 없으면 기본값 사용.
     */
    fun addSfxFromLibrary(asset: LibraryAssetEntity) {
        val state = _uiState.value
        val (seg, sourceT) = state.timeline.mapOutputToSource(state.playheadOutputMs) ?: return
        val sfxDuration = if (asset.durationMs > 0) asset.durationMs else DEFAULT_SFX_DURATION_MS
        val srcEnd = (sourceT + sfxDuration).coerceAtMost(seg.sourceEndMs)
        val uri = File(asset.filePath).toUri()
        val clip = SfxClip(
            sourceStartMs = sourceT,
            sourceEndMs = srcEnd,
            audioUri = uri,
            label = asset.label,
        )
        _uiState.update {
            it.copy(
                timeline = it.timeline.addSfxClip(clip),
                activeSheet = null,
            )
        }
    }

    fun deleteSfxClip(id: String) {
        _uiState.update { it.copy(timeline = it.timeline.deleteSfxClip(id)) }
    }

    fun onSfxTranslate(id: String, deltaMs: Long) {
        _uiState.update { state ->
            val clip = state.timeline.sfxClips.firstOrNull { it.id == id } ?: return@update state
            val newStart = (clip.sourceStartMs + deltaMs).coerceAtLeast(0L)
            val duration = clip.sourceEndMs - clip.sourceStartMs
            state.copy(timeline = state.timeline.updateSfxClip(
                clip.copy(sourceStartMs = newStart, sourceEndMs = newStart + duration)
            ))
        }
    }

    // --- 모자이크(MosaicRegion) ---
    /**
     * 현재 플레이헤드 위치에 수동 모자이크 영역 추가. 기본 중앙 사각형.
     * 추후 프리뷰에서 드래그로 rect 조정.
     */
    fun addManualMosaicAtPlayhead() {
        val state = _uiState.value
        val (seg, sourceT) = state.timeline.mapOutputToSource(state.playheadOutputMs) ?: return
        val srcEnd = (sourceT + DEFAULT_MOSAIC_DURATION_MS).coerceAtMost(seg.sourceEndMs)
        val region = MosaicRegion(
            sourceStartMs = sourceT,
            sourceEndMs = srcEnd,
            mode = MosaicMode.MANUAL,
            keyframes = listOf(
                MosaicKeyframe(sourceTimeMs = sourceT, cx = 0f, cy = 0.2f, w = 0.4f, h = 0.4f),
            ),
        )
        _uiState.update {
            it.copy(
                timeline = it.timeline.addMosaicRegion(region),
                selectedMosaicId = region.id,
            )
        }
    }

    /**
     * 현재 플레이헤드가 속한 세그먼트의 [sourceStart, sourceEnd] 구간에서 얼굴을 자동 탐지해
     * MosaicRegion 을 추가. 범위는 기본 세그먼트 끝까지 (최대 10초).
     */
    fun addAutoFaceMosaicFromPlayhead() {
        val state = _uiState.value
        val (seg, sourceT) = state.timeline.mapOutputToSource(state.playheadOutputMs) ?: return
        val srcEnd = (sourceT + AUTO_FACE_DEFAULT_RANGE_MS).coerceAtMost(seg.sourceEndMs)
        if (srcEnd <= sourceT) return

        viewModelScope.launch {
            _uiState.update { it.copy(isDetectingFaces = true) }
            val keyframes = faceTracker.track(seg.sourceUri, sourceT, srcEnd)
            if (keyframes.isEmpty()) {
                _uiState.update {
                    it.copy(
                        isDetectingFaces = false,
                        phase = ExportPhase.Error("얼굴을 찾지 못했어요. 수동 모자이크를 써보세요."),
                    )
                }
                return@launch
            }
            val region = MosaicRegion(
                sourceStartMs = sourceT,
                sourceEndMs = srcEnd,
                mode = MosaicMode.AUTO_FACE,
                keyframes = keyframes,
            )
            _uiState.update {
                it.copy(
                    isDetectingFaces = false,
                    timeline = it.timeline.addMosaicRegion(region),
                    selectedMosaicId = region.id,
                )
            }
        }
    }

    fun selectMosaic(id: String?) = _uiState.update { it.copy(selectedMosaicId = id) }

    fun deleteMosaic(id: String) {
        _uiState.update {
            it.copy(
                timeline = it.timeline.deleteMosaicRegion(id),
                selectedMosaicId = if (it.selectedMosaicId == id) null else it.selectedMosaicId,
            )
        }
    }

    /**
     * 수동 모자이크의 단일 키프레임 rect 를 업데이트. AUTO_FACE 모드는 변경 안 함.
     */
    fun updateManualMosaicRect(id: String, cx: Float, cy: Float, w: Float, h: Float) {
        _uiState.update { state ->
            val region = state.timeline.mosaicRegions.firstOrNull { it.id == id } ?: return@update state
            if (region.mode != MosaicMode.MANUAL) return@update state
            val kf = region.keyframes.firstOrNull() ?: MosaicKeyframe(region.sourceStartMs, cx, cy, w, h)
            val updated = region.copy(
                keyframes = listOf(
                    kf.copy(cx = cx, cy = cy, w = w, h = h),
                )
            )
            state.copy(timeline = state.timeline.updateMosaicRegion(updated))
        }
    }

    fun onMosaicResize(id: String, startDeltaMs: Long, endDeltaMs: Long) {
        _uiState.update { state ->
            val region = state.timeline.mosaicRegions.firstOrNull { it.id == id } ?: return@update state
            val newStart = (region.sourceStartMs + startDeltaMs).coerceAtLeast(0L)
            val newEnd = (region.sourceEndMs + endDeltaMs).coerceAtLeast(newStart + MIN_OVERLAY_DURATION_MS)
            state.copy(timeline = state.timeline.updateMosaicRegion(region.copy(sourceStartMs = newStart, sourceEndMs = newEnd)))
        }
    }

    fun onMosaicTranslate(id: String, deltaMs: Long) {
        _uiState.update { state ->
            val region = state.timeline.mosaicRegions.firstOrNull { it.id == id } ?: return@update state
            val newStart = (region.sourceStartMs + deltaMs).coerceAtLeast(0L)
            val duration = region.sourceEndMs - region.sourceStartMs
            state.copy(timeline = state.timeline.updateMosaicRegion(
                region.copy(sourceStartMs = newStart, sourceEndMs = newStart + duration)
            ))
        }
    }

    // --- 고정 텍스트 템플릿 ---
    fun addFixedTemplate(anchor: TemplateAnchor, defaultText: String) {
        val label = when (anchor) {
            TemplateAnchor.TOP_LEFT -> "좌상단"
            TemplateAnchor.TOP_CENTER -> "상단"
            TemplateAnchor.TOP_RIGHT -> "우상단"
            TemplateAnchor.BOTTOM_LEFT -> "좌하단"
            TemplateAnchor.BOTTOM_CENTER -> "하단"
            TemplateAnchor.BOTTOM_RIGHT -> "우하단"
        }
        val template = FixedTextTemplate(
            label = label,
            anchor = anchor,
            defaultText = defaultText,
            style = CaptionStyle.DEFAULT.copy(sizeScale = 0.7f),
        )
        _uiState.update {
            it.copy(
                timeline = it.timeline.addFixedTemplate(template),
                selectedFixedTemplateId = template.id,
            )
        }
    }

    fun updateFixedTemplateDefault(id: String, newDefault: String) {
        _uiState.update { state ->
            val t = state.timeline.fixedTemplates.firstOrNull { it.id == id } ?: return@update state
            state.copy(timeline = state.timeline.updateFixedTemplate(t.copy(defaultText = newDefault)))
        }
    }

    fun updateFixedTemplateOverride(id: String, segmentId: String, text: String) {
        _uiState.update { state ->
            val t = state.timeline.fixedTemplates.firstOrNull { it.id == id } ?: return@update state
            val newMap = if (text.isBlank()) t.perSegmentText - segmentId
                         else t.perSegmentText + (segmentId to text)
            state.copy(timeline = state.timeline.updateFixedTemplate(t.copy(perSegmentText = newMap)))
        }
    }

    fun toggleFixedTemplate(id: String) {
        _uiState.update { state ->
            val t = state.timeline.fixedTemplates.firstOrNull { it.id == id } ?: return@update state
            state.copy(timeline = state.timeline.updateFixedTemplate(t.copy(enabled = !t.enabled)))
        }
    }

    fun deleteFixedTemplate(id: String) {
        _uiState.update {
            it.copy(
                timeline = it.timeline.deleteFixedTemplate(id),
                selectedFixedTemplateId = if (it.selectedFixedTemplateId == id) null else it.selectedFixedTemplateId,
            )
        }
    }

    fun selectFixedTemplate(id: String?) = _uiState.update { it.copy(selectedFixedTemplateId = id) }

    /** 현재 타임라인의 고정 템플릿을 프리셋(라이브러리) 로 저장 — 다른 영상에서 재사용. */
    fun saveFixedTemplateAsPreset(id: String) {
        val template = _uiState.value.timeline.fixedTemplates.firstOrNull { it.id == id } ?: return
        viewModelScope.launch {
            runCatching {
                fixedTemplatePresetRepository.save(
                    label = template.label,
                    anchor = template.anchor,
                    defaultText = template.defaultText,
                    style = template.style,
                )
            }
        }
    }

    /** 라이브러리의 프리셋을 현재 타임라인에 새 고정 템플릿으로 추가. */
    fun addFixedTemplateFromPreset(preset: FixedTemplatePresetEntity) {
        val template = fixedTemplatePresetRepository.instantiate(preset)
        _uiState.update {
            it.copy(
                timeline = it.timeline.addFixedTemplate(template),
                selectedFixedTemplateId = template.id,
            )
        }
    }

    /** 라이브러리에서 프리셋 영구 삭제. 기존 타임라인에 추가된 템플릿 인스턴스는 영향 없음. */
    fun deleteFixedTemplatePreset(preset: FixedTemplatePresetEntity) {
        viewModelScope.launch {
            runCatching { fixedTemplatePresetRepository.delete(preset) }
        }
    }

    // --- Export ---
    /**
     * R5c3b: BGM 자동 더킹 토글. 켜면 startExport 시 라우드니스로 KeyframeTrack 빌드.
     */
    fun setAutoDucking(enabled: Boolean) {
        _uiState.update { it.copy(autoDuckingEnabled = enabled) }
    }

    /**
     * R6 자산 라운드: 채널 톤 테마 변경. 즉시 자막·썸네일 톤이 일제히 갱신.
     */
    fun setSelectedTheme(themeId: String) {
        _uiState.update { it.copy(selectedThemeId = themeId) }
    }

    /** R6 폴리시: AI 썸네일 5장 중 메인으로 사용할 변형 선택. */
    fun setSelectedThumbnail(variantId: String?) {
        _uiState.update { it.copy(selectedThumbnailVariantId = variantId) }
    }

    fun startExport() {
        val state = _uiState.value
        if (!state.hasVideo) return

        // R5c3b: 자동 더킹 — 활성 + BGM 있음 + 라우드니스 분석 결과 있을 때만 트랙 빌드.
        val duckingTrack = if (state.autoDuckingEnabled &&
            state.timeline.audioTrack != null
        ) {
            state.audioAnalysis?.loudness?.let { loudness ->
                Ducking.buildVolumeTrack(
                    loudness = loudness,
                    bgmBaseVolume = 1f,
                )
            }
        } else null

        viewModelScope.launch {
            _uiState.update { it.copy(phase = ExportPhase.Running(0f)) }
            videoEditor.exportTimeline(
                timeline = state.timeline,
                bgmDuckingTrack = duckingTrack,
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
                    refreshQuickLoad()
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

    /**
     * ViewModel 종료 시 디바운스 중이었을 수 있는 최신 타임라인을 동기 저장 — 앱 백그라운드 진입·프로세스
     * 종료 직전의 마지막 몇 초 편집이 날아가는 것 방지. Room save 는 짧은 JSON write 라 main 블로킹 영향
     * 실제로 미미하지만 안전을 위해 IO 디스패처로.
     */
    override fun onCleared() {
        super.onCleared()
        // R5c2: 썸네일 비트맵 메모리 회수 — 1280×720 ARGB × 5 ≈ 18MB.
        _uiState.value.thumbnailPreviewBitmaps.values.forEach { bmp ->
            if (!bmp.isRecycled) bmp.recycle()
        }
        val pid = projectId ?: return
        if (!autoSaveEnabled) return
        val tl = _uiState.value.timeline
        if (tl.segments.isEmpty()) return
        kotlinx.coroutines.runBlocking(Dispatchers.IO) {
            runCatching { timelineSnapshotRepository.save(pid, tl) }
        }
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

    /**
     * 영상 URI 의 회전-반영 해상도. rotation==90/270 이면 W/H 를 swap 해 화면에 보일 모양 그대로 반환.
     */
    private fun readFrameSize(uri: Uri): Pair<Int, Int>? {
        val retriever = MediaMetadataRetriever()
        return try {
            retriever.setDataSource(getApplication<Application>(), uri)
            val w = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_WIDTH)
                ?.toIntOrNull()
            val h = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_HEIGHT)
                ?.toIntOrNull()
            val rotation = retriever
                .extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_ROTATION)
                ?.toIntOrNull() ?: 0
            if (w == null || h == null || w <= 0 || h <= 0) null
            else if (rotation == 90 || rotation == 270) h to w
            else w to h
        } catch (_: Exception) {
            null
        } finally {
            runCatching { retriever.release() }
        }
    }


    private fun TimelineCaption.toEditable(existsInTimeline: Boolean) = EditableTextItem(
        id = id,
        text = text,
        sourceStartMs = sourceStartMs,
        sourceEndMs = sourceEndMs,
        style = style,
        existsInTimeline = existsInTimeline,
    )

    private fun TextLayer.toEditable(existsInTimeline: Boolean) = EditableTextItem(
        id = id,
        text = text,
        sourceStartMs = sourceStartMs,
        sourceEndMs = sourceEndMs,
        style = style,
        existsInTimeline = existsInTimeline,
    )

    companion object {
        private const val DEFAULT_NEW_DURATION_MS = 2_000L
        private const val DEFAULT_CUT_DURATION_MS = 2_000L
        private const val DEFAULT_SFX_DURATION_MS = 1_500L
        private const val DEFAULT_MOSAIC_DURATION_MS = 3_000L
        private const val AUTO_FACE_DEFAULT_RANGE_MS = 10_000L
        private const val MIN_OVERLAY_DURATION_MS = 200L
        /** 편집 중 타임라인 자동 저장 디바운스. 사용자가 드래그·타이핑을 마치는 경계 정도. */
        private const val TIMELINE_SAVE_DEBOUNCE_MS = 800L

        val Factory: ViewModelProvider.Factory = viewModelFactory {
            initializer {
                val app = this[ViewModelProvider.AndroidViewModelFactory.APPLICATION_KEY] as StudioPopApp
                EditorViewModel(
                    application = app,
                    videoEditor = app.container.videoEditor,
                    frameStripGenerator = app.container.frameStripGenerator,
                    projectRepository = app.container.projectRepository,
                    libraryAssetRepository = app.container.libraryAssetRepository,
                    timelineSnapshotRepository = app.container.timelineSnapshotRepository,
                    fixedTemplatePresetRepository = app.container.fixedTemplatePresetRepository,
                    faceTracker = app.container.faceTracker,
                    vocalSeparator = app.container.vocalSeparator,
                    uvrModelManager = app.container.uvrModelManager,
                    audioAnalysisService = app.container.audioAnalysisService,
                    aiAssist = app.container.aiAssist,
                    frameExtractor = app.container.frameExtractor,
                    thumbnailComposer = app.container.thumbnailComposer,
                    captionWordStore = app.container.captionWordStore,
                )
            }
        }
    }
}
