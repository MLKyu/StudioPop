package com.mingeek.studiopop.ui.editor

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.ContentCut
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.outlined.Add
import androidx.compose.material3.Card
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledTonalButton
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
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.media3.common.util.UnstableApi
import com.mingeek.studiopop.ui.editor.components.CaptionEditorSheet
import com.mingeek.studiopop.ui.editor.components.PreviewPlayer
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

    Scaffold(
        topBar = {
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
    ) { innerPadding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding),
        ) {
            if (state.videoUri == null) {
                EmptyVideoPicker(
                    onPick = {
                        pickVideoLauncher.launch(
                            PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.VideoOnly)
                        )
                    },
                    onPickSrt = { pickSrtLauncher.launch("*/*") },
                )
            } else {
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .aspectRatio(16f / 9f)
                        .padding(horizontal = 8.dp, vertical = 4.dp),
                ) {
                    PreviewPlayer(
                        sourceUri = state.videoUri!!,
                        timeline = state.timeline,
                        onPositionChange = { output ->
                            if (state.isPlaying) viewModel.onPlayheadChange(output)
                        },
                        seekToOutputMs = state.seekRequest,
                        isPlaying = state.isPlaying,
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
                    canDelete = state.selectedSegmentId != null && state.timeline.segments.size > 1,
                    onTogglePlay = viewModel::togglePlay,
                    onSplit = viewModel::splitAtPlayhead,
                    onDelete = viewModel::deleteSelectedSegment,
                    onAddCaption = viewModel::openCaptionEditorForNew,
                )

                Card(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(8.dp),
                ) {
                    TimelineView(
                        timeline = state.timeline,
                        sourceDurationMs = state.sourceDurationMs,
                        frameStrip = state.frameStrip,
                        playheadOutputMs = state.playheadOutputMs,
                        selectedSegmentId = state.selectedSegmentId,
                        selectedCaptionId = state.editingCaption?.id,
                        onSegmentTap = { id ->
                            viewModel.selectSegment(if (id == state.selectedSegmentId) null else id)
                        },
                        onCaptionTap = viewModel::openCaptionEditorFor,
                        onPlayheadDrag = viewModel::onPlayheadDragged,
                        modifier = Modifier.fillMaxWidth(),
                    )
                }

                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 8.dp, vertical = 4.dp),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    OutlinedButton(
                        onClick = {
                            pickVideoLauncher.launch(
                                PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.VideoOnly)
                            )
                        },
                        modifier = Modifier.weight(1f),
                    ) { Text("영상 변경") }
                    OutlinedButton(
                        onClick = { pickSrtLauncher.launch("*/*") },
                        modifier = Modifier.weight(1f),
                    ) { Text("SRT 불러오기") }
                }

                PhaseIndicator(phase = state.phase, onDismiss = viewModel::dismissMessage)
            }
        }
    }

    state.editingCaption?.let { cap ->
        CaptionEditorSheet(
            caption = cap,
            onDismiss = viewModel::closeCaptionEditor,
            onSave = viewModel::saveCaption,
            onDelete = if (state.timeline.captions.any { it.id == cap.id }) {
                { viewModel.deleteEditingCaption() }
            } else null,
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
                    "이어서 분할·삭제·자막 추가를 할 수 있어요.",
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
    onSplit: () -> Unit,
    onDelete: () -> Unit,
    onAddCaption: () -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
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
        FilledTonalButton(onClick = onSplit) {
            Icon(Icons.Filled.ContentCut, contentDescription = null)
            Text(" 분할", modifier = Modifier.padding(start = 4.dp))
        }
        FilledTonalButton(onClick = onDelete, enabled = canDelete) {
            Icon(Icons.Filled.Delete, contentDescription = null)
            Text(" 삭제", modifier = Modifier.padding(start = 4.dp))
        }
        FilledTonalButton(onClick = onAddCaption) {
            Icon(Icons.Outlined.Add, contentDescription = null)
            Text(" 자막", modifier = Modifier.padding(start = 4.dp))
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
