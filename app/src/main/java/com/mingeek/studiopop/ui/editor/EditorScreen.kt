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
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.horizontalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.ContentCut
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.RemoveCircle
import androidx.compose.material.icons.filled.MusicNote
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Subtitles
import androidx.compose.material.icons.outlined.Add
import androidx.compose.material.icons.outlined.Animation
import androidx.compose.material3.Card
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
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
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.media3.common.util.UnstableApi
import com.mingeek.studiopop.data.editor.TransitionKind
import com.mingeek.studiopop.ui.common.ProjectQuickLoadCard
import com.mingeek.studiopop.ui.editor.components.CaptionEditorSheet
import com.mingeek.studiopop.ui.editor.components.PreviewCaptionOverlay
import com.mingeek.studiopop.ui.editor.components.PreviewPlayer
import com.mingeek.studiopop.ui.editor.components.PreviewTransitionOverlay
import com.mingeek.studiopop.ui.editor.components.TimelineView

@OptIn(ExperimentalMaterial3Api::class)
@UnstableApi
@Composable
fun EditorScreen(
    onNavigateBack: () -> Unit,
    projectId: Long? = null,
    viewModel: EditorViewModel = viewModel(factory = EditorViewModel.Factory),
) {
    val state by viewModel.uiState.collectAsState()
    LaunchedEffect(projectId) { viewModel.bindProject(projectId) }

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

    Scaffold(
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
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .heightIn(max = 300.dp)
                        .aspectRatio(16f / 9f)
                        .padding(horizontal = 8.dp, vertical = 4.dp)
                        // 빈 영역 탭 → TopAppBar 토글. 자막/텍스트 오버레이는 자기 pointerInput
                        // 에서 이벤트 먼저 소비하므로 드래그/탭이 여기 내려오지 않아 무해.
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
                        modifier = Modifier.fillMaxSize(),
                    )
                    // 전환 켜져 있으면 경계 근처에서 페이드 오버레이 (export 와 동일 공식).
                    if (state.timeline.transitions.enabled) {
                        PreviewTransitionOverlay(
                            boundariesMs = state.timeline.transitionBoundariesRawOutputMs(),
                            currentOutputMs = state.playheadOutputMs,
                            halfDurationMs = state.timeline.transitions.halfDurationMs,
                            peakAlpha = state.timeline.transitions.peakAlpha,
                            modifier = Modifier.fillMaxSize(),
                        )
                    }
                    // 프리뷰 위에 자막/텍스트 레이어 Compose 오버레이. 세로 드래그로 anchorY 조정.
                    PreviewCaptionOverlay(
                        timeline = state.timeline,
                        currentOutputMs = state.playheadOutputMs,
                        onCaptionAnchorChange = viewModel::onCaptionAnchorChange,
                        onTextLayerAnchorChange = viewModel::onTextLayerAnchorChange,
                        modifier = Modifier.fillMaxSize(),
                    )
                    if (state.seekRequest != null) {
                        LaunchedEffect(state.seekRequest) { viewModel.consumeSeekRequest() }
                    }
                }

                ToolbarRow(
                    isPlaying = state.isPlaying,
                    playheadMs = state.playheadOutputMs,
                    totalOutputMs = state.timeline.outputDurationMs,
                    canDelete = state.canDelete,
                    onTogglePlay = viewModel::togglePlay,
                    onAddCutRange = viewModel::addCutRangeAtPlayhead,
                    onDelete = viewModel::deleteCurrentSegment,
                    onAddCaption = viewModel::openCaptionEditorForNew,
                    onAddTextLayer = viewModel::openTextLayerEditorForNew,
                    onAddVideo = {
                        addVideoLauncher.launch(
                            PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.VideoOnly)
                        )
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
                        onCaptionTap = viewModel::openCaptionEditorFor,
                        onTextLayerTap = viewModel::openTextLayerEditorFor,
                        onPlayheadDrag = viewModel::onPlayheadDragged,
                        onDividerDrag = viewModel::onDividerDrag,
                        onCaptionResize = viewModel::onCaptionResize,
                        onTextLayerResize = viewModel::onTextLayerResize,
                        onCaptionTranslate = viewModel::onCaptionTranslate,
                        onTextLayerTranslate = viewModel::onTextLayerTranslate,
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
        CaptionEditorSheet(
            item = item,
            title = title,
            onDismiss = viewModel::closeEditor,
            onSave = viewModel::saveEditingItem,
            onDelete = if (item.existsInTimeline) { { viewModel.deleteEditingItem() } } else null,
        )
    }
}

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
    onTogglePlay: () -> Unit,
    onAddCutRange: () -> Unit,
    onDelete: () -> Unit,
    onAddCaption: () -> Unit,
    onAddTextLayer: () -> Unit,
    onAddVideo: () -> Unit,
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
        FilledTonalButton(onClick = onAddVideo) {
            Icon(Icons.Outlined.Add, contentDescription = null)
            Text(" 영상", modifier = Modifier.padding(start = 4.dp))
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
