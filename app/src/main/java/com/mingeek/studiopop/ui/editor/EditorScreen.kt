package com.mingeek.studiopop.ui.editor

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.horizontalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.BlurOn
import androidx.compose.material.icons.filled.ContentCut
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Image
import androidx.compose.material.icons.filled.Movie
import androidx.compose.material.icons.filled.PushPin
import androidx.compose.material.icons.filled.RemoveCircle
import androidx.compose.material.icons.filled.MusicNote
import androidx.compose.material.icons.filled.NotificationsActive
import androidx.compose.material.icons.filled.Tune
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Subtitles
import androidx.compose.material.icons.outlined.Add
import androidx.compose.material.icons.outlined.Animation
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Card
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.foundation.focusable
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.isCtrlPressed
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.media3.common.util.UnstableApi
import com.mingeek.studiopop.StudioPopApp
import com.mingeek.studiopop.data.ai.EditSuggestion
import com.mingeek.studiopop.data.editor.TransitionKind
import com.mingeek.studiopop.data.library.LibraryAssetKind
import com.mingeek.studiopop.data.text.CaptionEffectResolver
import com.mingeek.studiopop.ui.editor.components.AiPackageSheet
import com.mingeek.studiopop.ui.text.PreviewBeatBusBinder
import com.mingeek.studiopop.ui.text.RichTextOverlay
import com.mingeek.studiopop.ui.common.ProjectQuickLoadCard
import com.mingeek.studiopop.ui.editor.components.AudioMixSheet
import com.mingeek.studiopop.ui.editor.components.CaptionEditorSheet
import com.mingeek.studiopop.ui.editor.components.ExportedVideoPickerSheet
import com.mingeek.studiopop.ui.editor.components.FixedTemplateEditorSheet
import com.mingeek.studiopop.ui.editor.components.LibraryPickerSheet
import com.mingeek.studiopop.ui.editor.components.MosaicEditorSheet
import com.mingeek.studiopop.ui.editor.components.PreviewCaptionOverlay
import com.mingeek.studiopop.ui.editor.components.PreviewFixedTemplateOverlay
import com.mingeek.studiopop.ui.editor.components.PreviewMosaicOverlay
import com.mingeek.studiopop.ui.editor.components.PreviewPlayer
import com.mingeek.studiopop.ui.editor.components.PreviewStickerOverlay
import com.mingeek.studiopop.ui.editor.components.PreviewTransitionOverlay
import com.mingeek.studiopop.ui.editor.components.SfxPreviewPlayer
import com.mingeek.studiopop.ui.editor.components.TimelineView

@OptIn(ExperimentalMaterial3Api::class)
@UnstableApi
@Composable
fun EditorScreen(
    onNavigateBack: () -> Unit,
    projectId: Long? = null,
    onNavigateLibrary: () -> Unit = {},
    viewModel: EditorViewModel = viewModel(factory = EditorViewModel.Factory),
) {
    val state by viewModel.uiState.collectAsState()
    LaunchedEffect(projectId) { viewModel.bindProject(projectId) }

    // R3.5: 자막 효과 시스템 컨테이너. effectRegistry / designTokens 는 앱 전역 단일 인스턴스
    // 라 매 컴포지션마다 새로 만드는 비용 없이 그냥 참조만.
    val appContext = LocalContext.current.applicationContext
    val container = (appContext as StudioPopApp).container

    // R4.5: 첫 segment 의 sourceUri 가 바뀌면 오디오 분석 자동 트리거. 캐시 hit 면 ViewModel
    // 안에서 즉시 반환.
    LaunchedEffect(state.timeline.segments.firstOrNull()?.sourceUri) {
        viewModel.analyzeFirstSegmentAudio()
    }

    val pickVideoLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.PickVisualMedia()
    ) { uri -> viewModel.onVideoSelected(uri) }

    val pickSrtLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.GetContent()
    ) { uri -> viewModel.onSrtPicked(uri) }

    val pickBgmLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.GetContent()
    ) { uri -> viewModel.onBgmPicked(uri) }

    // 프리뷰 빈 영역 탭 → TopAppBar 숨김/노출 토글. 편집 중 더 많은 화면 공간 확보용.
    var toolbarVisible by remember { mutableStateOf(true) }

    // R5c1: AI 패키지 주제 입력 다이얼로그
    var showAiTopicDialog by remember { mutableStateOf(false) }
    var aiTopicInput by remember { mutableStateOf("") }

    // DeX 키보드 shortcut — 편집기 진입 시 focus 잡아 Space/Arrow/Ctrl+E/Esc/Delete 처리.
    val keyFocus = remember { FocusRequester() }
    LaunchedEffect(Unit) { runCatching { keyFocus.requestFocus() } }
    val keyboardModifier = Modifier
        .focusRequester(keyFocus)
        .focusable()
        .onKeyEvent { keyEvent ->
            if (keyEvent.type != KeyEventType.KeyDown) return@onKeyEvent false
            // 시트·편집기 열려 있을 때는 TextField 타이핑 보호. Esc 만 닫기 용도로 통과.
            val sheetOpen = state.activeSheet != null || state.editingItem != null
            if (sheetOpen && keyEvent.key != Key.Escape) return@onKeyEvent false
            when {
                keyEvent.key == Key.Escape -> {
                    when {
                        state.editingItem != null -> { viewModel.closeEditor(); true }
                        state.activeSheet != null -> { viewModel.closeSheet(); true }
                        else -> false
                    }
                }
                keyEvent.key == Key.Spacebar -> {
                    if (state.hasVideo) viewModel.togglePlay()
                    true
                }
                keyEvent.key == Key.DirectionLeft -> {
                    val new = (state.playheadOutputMs - SEEK_STEP_MS).coerceAtLeast(0L)
                    viewModel.onPlayheadDragged(new)
                    true
                }
                keyEvent.key == Key.DirectionRight -> {
                    val new = (state.playheadOutputMs + SEEK_STEP_MS)
                        .coerceAtMost(state.timeline.outputDurationMs)
                    viewModel.onPlayheadDragged(new)
                    true
                }
                keyEvent.key == Key.E && keyEvent.isCtrlPressed -> {
                    if (state.canExport) viewModel.startExport()
                    true
                }
                keyEvent.key == Key.Delete || keyEvent.key == Key.Backspace -> {
                    when {
                        state.selectedImageLayerId != null -> {
                            viewModel.deleteImageLayer(state.selectedImageLayerId!!); true
                        }
                        state.selectedMosaicId != null -> {
                            viewModel.deleteMosaic(state.selectedMosaicId!!); true
                        }
                        else -> false
                    }
                }
                else -> false
            }
        }

    Scaffold(
        modifier = keyboardModifier,
        topBar = {
            AnimatedVisibility(
                visible = toolbarVisible,
                enter = slideInVertically(initialOffsetY = { -it }),
                exit = slideOutVertically(targetOffsetY = { -it }),
            ) {
                TopAppBar(
                    title = { Text("편집기") },
                    navigationIcon = {
                        IconButton(onClick = onNavigateBack) {
                            Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "뒤로")
                        }
                    },
                    actions = {
                        // R5c1: AI 패키지 — 영상이 있을 때만 활성. 진행 중이면 spinner.
                        val aiPhase = state.aiPackagePhase
                        val aiInProgress = aiPhase is AiPackagePhase.Analyzing ||
                            aiPhase is AiPackagePhase.Generating
                        // R5d: 효과/자막 적용 카운트를 라벨에 노출 — 사용자가 어떤 AI 변경이
                        // 적용 됐는지 한눈에 알 수 있게.
                        val appliedEffects = state.effectStack.instances.size
                        val appliedCaptionStyles = state.captionEffectIds.size
                        val appliedTotal = appliedEffects + appliedCaptionStyles
                        val appliedSuffix = if (appliedTotal > 0) " (✨$appliedTotal)" else ""
                        val aiButtonLabel = when {
                            aiPhase is AiPackagePhase.Analyzing -> "분석 중…"
                            aiPhase is AiPackagePhase.Generating -> "생성 중…"
                            state.youtubePackage != null -> "✨ AI 결과 다시 보기$appliedSuffix"
                            else -> "✨ AI 패키지$appliedSuffix"
                        }
                        OutlinedButton(
                            onClick = {
                                if (state.youtubePackage != null && !aiInProgress) {
                                    viewModel.reopenAiPackageSheet()
                                } else if (!aiInProgress) {
                                    showAiTopicDialog = true
                                }
                            },
                            enabled = state.hasVideo && !aiInProgress,
                            modifier = Modifier.padding(end = 4.dp),
                        ) {
                            if (aiInProgress) {
                                CircularProgressIndicator(
                                    modifier = Modifier.padding(end = 6.dp),
                                    strokeWidth = 2.dp,
                                )
                            }
                            Text(aiButtonLabel)
                        }
                        OutlinedButton(
                            onClick = viewModel::startExport,
                            enabled = state.canExport,
                            modifier = Modifier.padding(end = 8.dp),
                        ) { Text("내보내기") }
                    }
                )
            }
        }
    ) { innerPadding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding)
                .verticalScroll(rememberScrollState()),
        ) {
            val addVideoLauncher = rememberLauncherForActivityResult(
                contract = ActivityResultContracts.PickVisualMedia()
            ) { uri -> viewModel.addVideoToTimeline(uri) }

            // 다중 선택 launcher — 여러 영상 한 번에 append.
            val addMultipleVideosLauncher = rememberLauncherForActivityResult(
                contract = ActivityResultContracts.PickMultipleVisualMedia(MAX_APPEND_VIDEOS),
            ) { uris -> viewModel.addVideosToTimeline(uris) }

            if (state.hasProject &&
                (state.latestExportVideoPath != null || state.latestSrtPath != null)
            ) {
                ProjectQuickLoadCard(
                    latestExportVideoPath = state.latestExportVideoPath,
                    latestSrtPath = state.latestSrtPath,
                    onLoadVideo = { viewModel.loadLatestExportAsInput() },
                    onLoadSrt = { viewModel.loadLatestSrt() },
                    modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp),
                )
            }

            if (!state.hasVideo) {
                EmptyVideoPicker(
                    onPick = {
                        pickVideoLauncher.launch(
                            PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.VideoOnly)
                        )
                    },
                    onPickSrt = { pickSrtLauncher.launch("*/*") },
                )
            } else {
                val toolbarToggleSource = remember { MutableInteractionSource() }
                // 프리뷰 박스 비율 우선순위:
                //   1) ExoPlayer 가 디코더에서 인식한 runtime VideoSize (회전·PAR 반영, ground truth)
                //   2) MediaMetadataRetriever 메타데이터 (content:// 권한 등으로 실패 가능)
                //   3) 16:9 fallback (영상 막 로딩 직후 잠깐만)
                // 이 박스 안에 영상 비율을 만족하는 가장 큰 직사각형을 가운데 정렬 — letterbox 영역은
                // Scaffold 배경(검정) 으로 자연스럽게. 자막/짤/모자이크 NDC 좌표는 이 박스 = 실제 영상
                // 영역과 1:1 매칭되어 검은 띠로 새 나가지 않음.
                // remember 의 key 를 URI 로 두면 새 영상마다 MutableState 인스턴스가 교체되는데,
                // PreviewPlayer 내부 DisposableEffect 의 listener 는 한 번만 캡쳐돼 옛 인스턴스를
                // 계속 잡고 있어 새 영상의 aspect 가 반영 안 되는 버그가 발생. → MutableState 는
                // 한 번만 만들고, URI 가 바뀌면 LaunchedEffect 로 value 만 null 로 초기화.
                val runtimeAspectState = remember { mutableStateOf<Float?>(null) }
                val firstSegUri = state.timeline.segments.firstOrNull()?.sourceUri
                LaunchedEffect(firstSegUri) { runtimeAspectState.value = null }
                val previewAspect = runtimeAspectState.value
                    ?: state.previewAspectRatio ?: (16f / 9f)
                val config = androidx.compose.ui.platform.LocalConfiguration.current
                // orientation 필드가 일부 단말/DeX 에서 정확하지 않을 수 있어 폭/높이 직접 비교가 더 안전.
                val isLandscape = config.screenWidthDp > config.screenHeightDp
                // 가로: 화면 높이의 85% (top bar/툴바 등 chrome 만 제외하고 거의 다 사용 — 세로 영상이
                // 잘리지 않도록). 세로: 300dp 정도가 적당 — 그 이상이면 타임라인 영역이 좁아짐.
                val previewMaxHeight = if (isLandscape) (config.screenHeightDp * 0.85f).dp
                else 300.dp
                BoxWithConstraints(
                    modifier = Modifier
                        .fillMaxWidth()
                        .heightIn(max = previewMaxHeight)
                        .padding(horizontal = 8.dp, vertical = 4.dp),
                    contentAlignment = Alignment.Center,
                ) {
                    val cw = maxWidth
                    // verticalScroll 부모에선 maxHeight 가 Infinity 로 들어올 수 있어 방어.
                    // value.isFinite() 로 Infinity·NaN 모두 차단.
                    val ch = if (maxHeight.value.isFinite() && maxHeight > 0.dp) maxHeight
                    else previewMaxHeight
                    val containerAspect = cw / ch
                    val (boxW, boxH) = if (previewAspect >= containerAspect) {
                        cw to (cw / previewAspect)
                    } else {
                        (ch * previewAspect) to ch
                    }
                    Box(
                        modifier = Modifier
                            .width(boxW)
                            .height(boxH)
                            .clickable(
                                interactionSource = toolbarToggleSource,
                                indication = null,
                            ) { toolbarVisible = !toolbarVisible },
                    ) {
                        PreviewPlayer(
                            timeline = state.timeline,
                            onPositionChange = { output ->
                                if (state.isPlaying) viewModel.onPlayheadChange(output)
                            },
                            seekToOutputMs = state.seekRequest,
                            isPlaying = state.isPlaying,
                            // 첫 valid runtime aspect 만 채택 — 여러 영상 concat 재생 시 segment
                            // 전환마다 박스가 흔들리지 않게, 또한 export 의 frame 좌표계(첫 세그먼트
                            // 해상도) 와 일치하게. URI 가 새로 바뀌면 위 LaunchedEffect 가 null 로
                            // 리셋해 다음 영상의 첫 size 를 다시 잡음.
                            onVideoAspectChange = { newRatio ->
                                if (runtimeAspectState.value == null) {
                                    runtimeAspectState.value = newRatio
                                }
                            },
                            modifier = Modifier.fillMaxSize(),
                        )
                        if (state.timeline.transitions.enabled) {
                            PreviewTransitionOverlay(
                                boundariesMs = state.timeline.transitionBoundariesRawOutputMs(),
                                currentOutputMs = state.playheadOutputMs,
                                halfDurationMs = state.timeline.transitions.halfDurationMs,
                                peakAlpha = state.timeline.transitions.peakAlpha,
                                modifier = Modifier.fillMaxSize(),
                            )
                        }
                        // R3.5: 효과 적용된 자막은 새 RichTextOverlay 가 담당, 그 외는 기존 경로.
                        // 두 오버레이가 같은 자막을 그리지 않도록 effectIds 키 집합으로 분기.
                        val effectiveCaptionIds = state.captionEffectIds.keys
                        val richElements = remember(
                            state.timeline.captions,
                            state.captionEffectIds,
                            state.captionBeatSyncIds,
                            state.captionKaraokeIds,
                            state.captionWords,
                        ) {
                            CaptionEffectResolver.resolveEffectiveCaptions(
                                captions = state.timeline.captions,
                                captionEffectIds = state.captionEffectIds,
                                effectRegistry = container.effectRegistry,
                                designTokens = container.designTokens,
                                themeId = "studiopop.default",
                                captionBeatSyncIds = state.captionBeatSyncIds,
                                captionKaraokeIds = state.captionKaraokeIds,
                                captionWords = state.captionWords,
                            )
                        }
                        // 새 자막 효과 렌더 — currentSourceMs 기준으로 시간 매칭
                        val sourceTimeMs = state.timeline.mapOutputToSource(state.playheadOutputMs)
                            ?.second ?: state.playheadOutputMs
                        // R4.5: 비트 분석 결과가 있으면 BeatBus 에 onset 시점마다 emit.
                        // beats == null 이면 NOOP — 분석 진행 중/실패 시 펄스만 비활성화.
                        PreviewBeatBusBinder(
                            beats = state.audioAnalysis?.beats,
                            currentSourceMs = sourceTimeMs,
                            beatBus = container.beatBus,
                        )
                        RichTextOverlay(
                            elements = richElements,
                            currentSourceMs = sourceTimeMs,
                            modifier = Modifier.fillMaxSize(),
                            beatBus = container.beatBus,
                        )
                        PreviewCaptionOverlay(
                            timeline = state.timeline,
                            currentOutputMs = state.playheadOutputMs,
                            onCaptionAnchorChange = viewModel::onCaptionAnchorChange,
                            onTextLayerAnchorChange = viewModel::onTextLayerAnchorChange,
                            modifier = Modifier.fillMaxSize(),
                            excludeCaptionIds = effectiveCaptionIds,
                        )
                        PreviewStickerOverlay(
                            timeline = state.timeline,
                            currentOutputMs = state.playheadOutputMs,
                            selectedId = state.selectedImageLayerId,
                            isPlaying = state.isPlaying,
                            onSelect = viewModel::selectImageLayer,
                            onTransform = viewModel::transformImageLayer,
                            modifier = Modifier.fillMaxSize(),
                        )
                        PreviewMosaicOverlay(
                            timeline = state.timeline,
                            currentOutputMs = state.playheadOutputMs,
                            selectedId = state.selectedMosaicId,
                            isPlaying = state.isPlaying,
                            onSelect = viewModel::selectMosaic,
                            onManualRectChange = viewModel::updateManualMosaicRect,
                            modifier = Modifier.fillMaxSize(),
                        )
                        PreviewFixedTemplateOverlay(
                            timeline = state.timeline,
                            currentOutputMs = state.playheadOutputMs,
                            modifier = Modifier.fillMaxSize(),
                        )
                        if (state.seekRequest != null) {
                            LaunchedEffect(state.seekRequest) { viewModel.consumeSeekRequest() }
                        }
                    }
                }

                // SFX 프리뷰 트리거 (보이지 않는 composable).
                SfxPreviewPlayer(
                    timeline = state.timeline,
                    playheadOutputMs = state.playheadOutputMs,
                    isPlaying = state.isPlaying,
                )

                ToolbarRow(
                    isPlaying = state.isPlaying,
                    playheadMs = state.playheadOutputMs,
                    totalOutputMs = state.timeline.outputDurationMs,
                    canDelete = state.canDelete,
                    hasStickerLibrary = state.stickerLibrary.isNotEmpty(),
                    hasSfxLibrary = state.sfxLibrary.isNotEmpty(),
                    hasExportedVideos = state.exportedVideoLibrary.isNotEmpty(),
                    onTogglePlay = viewModel::togglePlay,
                    onAddCutRange = viewModel::addCutRangeAtPlayhead,
                    onDelete = viewModel::deleteCurrentSegment,
                    onAddCaption = viewModel::openCaptionEditorForNew,
                    onAddTextLayer = viewModel::openTextLayerEditorForNew,
                    onAddSticker = { viewModel.openSheet(EditorSheet.IMAGE_PICKER) },
                    onAddSfx = { viewModel.openSheet(EditorSheet.SFX_PICKER) },
                    onOpenMosaic = { viewModel.openSheet(EditorSheet.MOSAIC) },
                    onOpenFixedTemplate = { viewModel.openSheet(EditorSheet.FIXED_TEMPLATE) },
                    onOpenAudioMix = { viewModel.openSheet(EditorSheet.AUDIO_MIX) },
                    onAddMultipleVideos = {
                        addMultipleVideosLauncher.launch(
                            PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.VideoOnly)
                        )
                    },
                    onOpenExportedVideoPicker = {
                        viewModel.openSheet(EditorSheet.EXPORTED_VIDEO_PICKER)
                    },
                )

                Card(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(8.dp),
                ) {
                    TimelineView(
                        timeline = state.timeline,
                        frameStrips = state.frameStrips,
                        playheadOutputMs = state.playheadOutputMs,
                        selectedCaptionId = state.editingItem?.id,
                        selectedImageLayerId = state.selectedImageLayerId,
                        selectedMosaicId = state.selectedMosaicId,
                        onCaptionTap = viewModel::openCaptionEditorFor,
                        onTextLayerTap = viewModel::openTextLayerEditorFor,
                        onImageLayerTap = viewModel::selectImageLayer,
                        onMosaicTap = viewModel::selectMosaic,
                        onSfxTap = { /* tap 은 no-op; 시간 이동은 롱프레스 드래그로 */ },
                        onPlayheadDrag = viewModel::onPlayheadDragged,
                        onDividerDrag = viewModel::onDividerDrag,
                        onCaptionResize = viewModel::onCaptionResize,
                        onTextLayerResize = viewModel::onTextLayerResize,
                        onImageLayerResize = viewModel::onImageLayerResize,
                        onMosaicResize = viewModel::onMosaicResize,
                        onCaptionTranslate = viewModel::onCaptionTranslate,
                        onTextLayerTranslate = viewModel::onTextLayerTranslate,
                        onImageLayerTranslate = viewModel::onImageLayerTranslate,
                        onMosaicTranslate = viewModel::onMosaicTranslate,
                        onSfxTranslate = viewModel::onSfxTranslate,
                        onCutRangeTap = viewModel::deleteCutRange,
                        onCutRangeResize = viewModel::onCutRangeResize,
                        onCutRangeTranslate = viewModel::onCutRangeTranslate,
                        modifier = Modifier.fillMaxWidth(),
                    )
                }

                // Tier 2 옵션들: 전환 / BGM / 영상 교체 / SRT
                OptionsRow(
                    transitionsOn = state.timeline.transitions.enabled,
                    transitionKind = state.timeline.transitions.kind,
                    canUseTransitions = state.canUseTransitions,
                    bgmLabel = state.timeline.audioTrack?.uri?.toString()
                        ?.substringAfterLast('/')
                        ?: "없음",
                    textLayerCount = state.timeline.textLayers.size,
                    onSelectTransitionKind = viewModel::selectTransitionKind,
                    onPickBgm = { pickBgmLauncher.launch("audio/*") },
                    onRemoveBgm = viewModel::removeBgm,
                    onPickSrt = { pickSrtLauncher.launch("*/*") },
                    onPickVideo = {
                        pickVideoLauncher.launch(
                            PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.VideoOnly)
                        )
                    },
                )

                PhaseIndicator(phase = state.phase, onDismiss = viewModel::dismissMessage)
            }
        }
    }

    state.editingItem?.let { item ->
        val kind = state.editingKind ?: EditKind.CAPTION
        val title = if (kind == EditKind.CAPTION) "자막 편집" else "텍스트 레이어 편집"
        // R3.5: 자막일 때만 효과 선택 노출 — 텍스트 레이어는 이번 라운드 스코프 밖.
        val showEffectPicker = kind == EditKind.CAPTION
        val currentEffectId = if (showEffectPicker) state.captionEffectIds[item.id] else null
        val beatSyncEnabled = showEffectPicker && item.id in state.captionBeatSyncIds
        val karaokeEnabled = showEffectPicker && item.id in state.captionKaraokeIds
        CaptionEditorSheet(
            item = item,
            title = title,
            onDismiss = viewModel::closeEditor,
            onSave = viewModel::saveEditingItem,
            onDelete = if (item.existsInTimeline) { { viewModel.deleteEditingItem() } } else null,
            currentEffectId = currentEffectId,
            onEffectChange = { effectId -> viewModel.setCaptionEffect(item.id, effectId) },
            showEffectPicker = showEffectPicker,
            beatSyncEnabled = beatSyncEnabled,
            onBeatSyncChange = { enabled -> viewModel.setCaptionBeatSync(item.id, enabled) },
            audioAnalyzing = state.isAnalyzingAudio,
            karaokeEnabled = karaokeEnabled,
            onKaraokeChange = { enabled -> viewModel.setCaptionKaraoke(item.id, enabled) },
        )
    }

    // R5c1: AI 패키지 주제 입력 다이얼로그
    if (showAiTopicDialog) {
        AlertDialog(
            onDismissRequest = { showAiTopicDialog = false },
            title = { Text("✨ AI 패키지 생성") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(
                        "영상 주제를 입력하면 제목·설명·챕터·태그·썸네일·숏츠를 한 번에 만들어줘요.",
                        style = MaterialTheme.typography.bodySmall,
                    )
                    OutlinedTextField(
                        value = aiTopicInput,
                        onValueChange = { aiTopicInput = it },
                        label = { Text("주제 (예: 가챠 100연차 후기)") },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth(),
                    )
                }
            },
            confirmButton = {
                TextButton(onClick = {
                    val topic = aiTopicInput.trim()
                    showAiTopicDialog = false
                    viewModel.generateYoutubePackage(topic)
                }) { Text("생성") }
            },
            dismissButton = {
                TextButton(onClick = { showAiTopicDialog = false }) { Text("취소") }
            },
        )
    }

    // R5c1: AI 패키지 결과 시트
    if (state.showAiPackageSheet) {
        state.youtubePackage?.let { pkg ->
            val captionSuggestionCount = state.editSuggestions
                .count { it is EditSuggestion.AddCaption }
            val effectSuggestionCount = state.editSuggestions
                .count { it is EditSuggestion.AddEffect }
            AiPackageSheet(
                pkg = pkg,
                captionSuggestionCount = captionSuggestionCount,
                effectSuggestionCount = effectSuggestionCount,
                thumbnailBitmaps = state.thumbnailPreviewBitmaps,
                onDismiss = viewModel::closeAiPackageSheet,
                onApplyCaptionSuggestions = {
                    viewModel.applyCaptionSuggestions()
                    viewModel.closeAiPackageSheet()
                },
                onApplyEffectSuggestions = {
                    viewModel.applyEffectSuggestions()
                    viewModel.closeAiPackageSheet()
                },
            )
        }
    }

    // R5c1: AI 패키지 실패 시 사용자에게 안내
    val phase = state.aiPackagePhase
    if (phase is AiPackagePhase.Failed) {
        AlertDialog(
            onDismissRequest = viewModel::dismissAiPackageError,
            title = { Text("AI 패키지 생성 실패") },
            text = { Text(phase.message) },
            confirmButton = {
                TextButton(onClick = viewModel::dismissAiPackageError) { Text("확인") }
            },
        )
    }

    when (state.activeSheet) {
        EditorSheet.IMAGE_PICKER -> LibraryPickerSheet(
            title = "짤 선택",
            kind = LibraryAssetKind.STICKER,
            items = state.stickerLibrary,
            onPick = viewModel::addImageLayerFromLibrary,
            onDismiss = viewModel::closeSheet,
            onNavigateLibrary = {
                viewModel.closeSheet()
                onNavigateLibrary()
            },
        )
        EditorSheet.SFX_PICKER -> LibraryPickerSheet(
            title = "효과음 선택",
            kind = LibraryAssetKind.SFX,
            items = state.sfxLibrary,
            onPick = viewModel::addSfxFromLibrary,
            onDismiss = viewModel::closeSheet,
            onNavigateLibrary = {
                viewModel.closeSheet()
                onNavigateLibrary()
            },
        )
        EditorSheet.MOSAIC -> MosaicEditorSheet(
            regions = state.timeline.mosaicRegions,
            isDetecting = state.isDetectingFaces,
            onAddManual = viewModel::addManualMosaicAtPlayhead,
            onAddAutoFace = viewModel::addAutoFaceMosaicFromPlayhead,
            onDelete = viewModel::deleteMosaic,
            onDismiss = viewModel::closeSheet,
        )
        EditorSheet.FIXED_TEMPLATE -> FixedTemplateEditorSheet(
            timeline = state.timeline,
            presets = state.fixedTemplatePresets,
            onAdd = viewModel::addFixedTemplate,
            onUpdateDefault = viewModel::updateFixedTemplateDefault,
            onUpdateOverride = viewModel::updateFixedTemplateOverride,
            onToggle = viewModel::toggleFixedTemplate,
            onDelete = viewModel::deleteFixedTemplate,
            onSaveAsPreset = viewModel::saveFixedTemplateAsPreset,
            onAddFromPreset = viewModel::addFixedTemplateFromPreset,
            onDeletePreset = viewModel::deleteFixedTemplatePreset,
            onDismiss = viewModel::closeSheet,
        )
        EditorSheet.EXPORTED_VIDEO_PICKER -> ExportedVideoPickerSheet(
            items = state.exportedVideoLibrary,
            onConfirm = viewModel::appendExportedVideos,
            onDismiss = viewModel::closeSheet,
        )
        EditorSheet.AUDIO_MIX -> AudioMixSheet(
            timeline = state.timeline,
            vocalExtractProgress = state.vocalExtractProgress,
            vocalModelState = state.vocalModelState,
            onOriginalVolume = viewModel::setOriginalVolume,
            onBgmVolume = viewModel::setBgmVolume,
            onSfxVolume = viewModel::setSfxVolume,
            onExtractCenterChannel = viewModel::setExtractCenterChannel,
            onBgmReplaceOriginal = viewModel::setBgmReplaceOriginal,
            onExtractVocals = viewModel::extractVocalsFromFirstSegment,
            onDismiss = viewModel::closeSheet,
            autoDuckingEnabled = state.autoDuckingEnabled,
            onAutoDuckingChange = viewModel::setAutoDucking,
            audioAnalysisReady = state.audioAnalysis?.loudness != null,
        )
        null -> Unit
    }
}

/** 갤러리 다중 선택 시 한 번에 고를 수 있는 최대 영상 수. PhotoPicker 상한 고려. */
private const val MAX_APPEND_VIDEOS = 20

/** 키보드 Arrow seek 한 번당 이동량 (ms). */
private const val SEEK_STEP_MS = 100L

@Composable
private fun EmptyVideoPicker(onPick: () -> Unit, onPickSrt: () -> Unit) {
    Column(
        modifier = Modifier.fillMaxSize().padding(24.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Text(
            "편집할 영상을 선택하세요",
            style = MaterialTheme.typography.titleMedium,
            fontWeight = FontWeight.Bold,
        )
        Text(
            "갤러리에서 영상을 선택하면 썸네일 타임라인이 생성됩니다. " +
                    "이어서 분할·삭제·자막·텍스트·전환·BGM 을 적용할 수 있어요.",
            style = MaterialTheme.typography.bodySmall,
        )
        OutlinedButton(onClick = onPick, modifier = Modifier.fillMaxWidth()) {
            Text("갤러리에서 영상 선택")
        }
        OutlinedButton(onClick = onPickSrt, modifier = Modifier.fillMaxWidth()) {
            Text("SRT 불러오기 (선택)")
        }
    }
}

@Composable
private fun ToolbarRow(
    isPlaying: Boolean,
    playheadMs: Long,
    totalOutputMs: Long,
    canDelete: Boolean,
    hasStickerLibrary: Boolean,
    hasSfxLibrary: Boolean,
    hasExportedVideos: Boolean,
    onTogglePlay: () -> Unit,
    onAddCutRange: () -> Unit,
    onDelete: () -> Unit,
    onAddCaption: () -> Unit,
    onAddTextLayer: () -> Unit,
    onAddSticker: () -> Unit,
    onAddSfx: () -> Unit,
    onOpenMosaic: () -> Unit,
    onOpenFixedTemplate: () -> Unit,
    onOpenAudioMix: () -> Unit,
    onAddMultipleVideos: () -> Unit,
    onOpenExportedVideoPicker: () -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .horizontalScroll(rememberScrollState())
            .padding(horizontal = 8.dp, vertical = 4.dp),
        horizontalArrangement = Arrangement.spacedBy(6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        IconButton(onClick = onTogglePlay) {
            Icon(
                if (isPlaying) Icons.Filled.Pause else Icons.Filled.PlayArrow,
                contentDescription = if (isPlaying) "일시정지" else "재생",
            )
        }
        Text(
            text = "${formatMs(playheadMs)} / ${formatMs(totalOutputMs)}",
            style = MaterialTheme.typography.labelLarge,
            modifier = Modifier.padding(start = 4.dp, end = 12.dp),
        )
        FilledTonalButton(onClick = onAddCutRange) {
            Icon(Icons.Filled.ContentCut, contentDescription = null)
            Text(" 범위 삭제", modifier = Modifier.padding(start = 4.dp))
        }
        FilledTonalButton(onClick = onDelete, enabled = canDelete) {
            Icon(Icons.Filled.RemoveCircle, contentDescription = null)
            Text(" 영상 제거", modifier = Modifier.padding(start = 4.dp))
        }
        FilledTonalButton(onClick = onAddCaption) {
            Icon(Icons.Filled.Subtitles, contentDescription = null)
            Text(" 자막", modifier = Modifier.padding(start = 4.dp))
        }
        FilledTonalButton(onClick = onAddTextLayer) {
            Icon(Icons.Outlined.Add, contentDescription = null)
            Text(" 텍스트", modifier = Modifier.padding(start = 4.dp))
        }
        // 짤/효과음 은 라이브러리가 비어있으면 disabled (의미 없을 때 명확히)
        FilledTonalButton(onClick = onAddSticker, enabled = hasStickerLibrary) {
            Icon(Icons.Filled.Image, contentDescription = null)
            Text(" 짤", modifier = Modifier.padding(start = 4.dp))
        }
        FilledTonalButton(onClick = onAddSfx, enabled = hasSfxLibrary) {
            Icon(Icons.Filled.NotificationsActive, contentDescription = null)
            Text(" 효과음", modifier = Modifier.padding(start = 4.dp))
        }
        FilledTonalButton(onClick = onOpenMosaic) {
            Icon(Icons.Filled.BlurOn, contentDescription = null)
            Text(" 모자이크", modifier = Modifier.padding(start = 4.dp))
        }
        FilledTonalButton(onClick = onOpenFixedTemplate) {
            Icon(Icons.Filled.PushPin, contentDescription = null)
            Text(" 고정 템플릿", modifier = Modifier.padding(start = 4.dp))
        }
        FilledTonalButton(onClick = onOpenAudioMix) {
            Icon(Icons.Filled.Tune, contentDescription = null)
            Text(" 오디오", modifier = Modifier.padding(start = 4.dp))
        }
        FilledTonalButton(onClick = onAddMultipleVideos) {
            Icon(Icons.Outlined.Add, contentDescription = null)
            Text(" 영상(여러개)", modifier = Modifier.padding(start = 4.dp))
        }
        FilledTonalButton(
            onClick = onOpenExportedVideoPicker,
            enabled = hasExportedVideos,
        ) {
            Icon(Icons.Filled.Movie, contentDescription = null)
            Text(" 내 편집본", modifier = Modifier.padding(start = 4.dp))
        }
    }
}

@Composable
private fun OptionsRow(
    transitionsOn: Boolean,
    transitionKind: TransitionKind,
    canUseTransitions: Boolean,
    bgmLabel: String,
    textLayerCount: Int,
    onSelectTransitionKind: (TransitionKind?) -> Unit,
    onPickBgm: () -> Unit,
    onRemoveBgm: () -> Unit,
    onPickSrt: () -> Unit,
    onPickVideo: () -> Unit,
) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 8.dp, vertical = 4.dp),
        verticalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            // 페이드: 경계에서 완전히 검정으로 빠짐
            val fadeSelected = transitionsOn && transitionKind == TransitionKind.FADE_TO_BLACK
            FilterChip(
                selected = fadeSelected,
                enabled = canUseTransitions,
                onClick = {
                    onSelectTransitionKind(if (fadeSelected) null else TransitionKind.FADE_TO_BLACK)
                },
                label = { Text("페이드") },
                leadingIcon = { Icon(Icons.Outlined.Animation, contentDescription = null) },
            )
            // 자연스럽게: 얕은 디졸브로 한 영상처럼 이어지는 느낌
            val dissolveSelected = transitionsOn && transitionKind == TransitionKind.DISSOLVE
            FilterChip(
                selected = dissolveSelected,
                enabled = canUseTransitions,
                onClick = {
                    onSelectTransitionKind(if (dissolveSelected) null else TransitionKind.DISSOLVE)
                },
                label = { Text("자연스럽게") },
                leadingIcon = { Icon(Icons.Outlined.Animation, contentDescription = null) },
            )
            FilterChip(
                selected = bgmLabel != "없음",
                onClick = onPickBgm,
                label = { Text("BGM: $bgmLabel") },
                leadingIcon = { Icon(Icons.Filled.MusicNote, contentDescription = null) },
            )
            if (bgmLabel != "없음") {
                OutlinedButton(onClick = onRemoveBgm) { Text("해제") }
            }
            if (textLayerCount > 0) {
                FilterChip(
                    selected = true,
                    onClick = {},
                    label = { Text("텍스트 레이어 $textLayerCount") },
                )
            }
        }
        Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            OutlinedButton(onClick = onPickVideo, modifier = Modifier.weight(1f)) {
                Text("영상 변경")
            }
            OutlinedButton(onClick = onPickSrt, modifier = Modifier.weight(1f)) {
                Text("SRT 불러오기")
            }
        }
    }
}

@Composable
private fun PhaseIndicator(phase: ExportPhase, onDismiss: () -> Unit) {
    when (phase) {
        ExportPhase.Idle -> Unit
        is ExportPhase.Running -> {
            Card(modifier = Modifier.fillMaxWidth().padding(8.dp)) {
                Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    Text("내보내는 중... ${(phase.progress * 100).toInt()}%")
                    LinearProgressIndicator(
                        progress = { phase.progress },
                        modifier = Modifier.fillMaxWidth(),
                    )
                }
            }
        }
        is ExportPhase.Success -> {
            Card(modifier = Modifier.fillMaxWidth().padding(8.dp)) {
                Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    Text("✅ 내보내기 완료", fontWeight = FontWeight.Bold)
                    Text(phase.outputPath, style = MaterialTheme.typography.bodySmall)
                    OutlinedButton(onClick = onDismiss) { Text("확인") }
                }
            }
        }
        is ExportPhase.Error -> {
            Card(modifier = Modifier.fillMaxWidth().padding(8.dp)) {
                Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    Text("⚠️ 오류", fontWeight = FontWeight.Bold)
                    Text(phase.message, style = MaterialTheme.typography.bodySmall)
                    OutlinedButton(onClick = onDismiss) { Text("닫기") }
                }
            }
        }
    }
}

private fun formatMs(ms: Long): String {
    if (ms <= 0) return "0:00"
    val totalSec = ms / 1000
    val m = totalSec / 60
    val s = totalSec % 60
    return "%d:%02d".format(m, s)
}
