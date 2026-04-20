package com.mingeek.studiopop.ui.editor

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.RangeSlider
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.media3.common.util.UnstableApi

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
    ) { uri -> viewModel.onSrtSelected(uri) }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("영상 편집 / 내보내기") },
                navigationIcon = {
                    IconButton(onClick = onNavigateBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "뒤로")
                    }
                }
            )
        }
    ) { innerPadding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding)
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            VideoSection(
                hasVideo = state.videoUri != null,
                durationMs = state.durationMs,
                display = state.videoUri?.toString().orEmpty(),
                onPick = {
                    pickVideoLauncher.launch(
                        PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.VideoOnly)
                    )
                },
            )

            if (state.durationMs > 0) {
                TrimSection(
                    startMs = state.trimStartMs,
                    endMs = state.trimEndMs,
                    durationMs = state.durationMs,
                    onChange = viewModel::onTrimChange,
                )
            }

            SrtSection(
                cueCount = state.cues.size,
                hasSrt = state.srtUri != null,
                onPick = { pickSrtLauncher.launch("*/*") },
                onClear = { viewModel.onSrtSelected(null) },
            )

            Button(
                onClick = viewModel::startExport,
                enabled = state.canExport,
                modifier = Modifier.fillMaxWidth(),
            ) { Text("내보내기 시작") }

            PhaseIndicator(state.phase, viewModel::dismissMessage)
        }
    }
}

@Composable
private fun VideoSection(
    hasVideo: Boolean,
    durationMs: Long,
    display: String,
    onPick: () -> Unit,
) {
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Text("원본 영상", style = MaterialTheme.typography.titleSmall)
            Text(
                text = if (hasVideo) "$display\n길이: ${formatMs(durationMs)}" else "영상이 선택되지 않았습니다",
                style = MaterialTheme.typography.bodySmall,
                maxLines = 3,
            )
            OutlinedButton(onClick = onPick, modifier = Modifier.fillMaxWidth()) {
                Text(if (hasVideo) "영상 변경" else "갤러리에서 선택")
            }
        }
    }
}

@Composable
private fun TrimSection(
    startMs: Long,
    endMs: Long,
    durationMs: Long,
    onChange: (Long, Long) -> Unit,
) {
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text("트림 범위", style = MaterialTheme.typography.titleSmall)
            Text(
                "${formatMs(startMs)}  →  ${formatMs(endMs)}  (길이 ${formatMs(endMs - startMs)})",
                style = MaterialTheme.typography.bodyMedium,
            )
            RangeSlider(
                value = startMs.toFloat()..endMs.toFloat(),
                onValueChange = { range ->
                    onChange(range.start.toLong(), range.endInclusive.toLong())
                },
                valueRange = 0f..durationMs.toFloat(),
            )
        }
    }
}

@Composable
private fun SrtSection(
    cueCount: Int,
    hasSrt: Boolean,
    onPick: () -> Unit,
    onClear: () -> Unit,
) {
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Text("자막 번인 (선택)", style = MaterialTheme.typography.titleSmall)
            Text(
                text = if (hasSrt) "불러온 큐: $cueCount 개" else "SRT 파일 없음",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedButton(onClick = onPick, modifier = Modifier.weight(1f)) {
                    Text(if (hasSrt) "SRT 변경" else "SRT 선택")
                }
                if (hasSrt) {
                    OutlinedButton(onClick = onClear, modifier = Modifier.weight(1f)) {
                        Text("해제")
                    }
                }
            }
        }
    }
}

@Composable
private fun PhaseIndicator(phase: ExportPhase, onDismiss: () -> Unit) {
    when (phase) {
        ExportPhase.Idle -> Unit
        is ExportPhase.Running -> {
            Card(modifier = Modifier.fillMaxWidth()) {
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
            Card(modifier = Modifier.fillMaxWidth()) {
                Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    Text("✅ 내보내기 완료", fontWeight = FontWeight.Bold)
                    Text(phase.outputPath, style = MaterialTheme.typography.bodySmall)
                    OutlinedButton(onClick = onDismiss) { Text("확인") }
                }
            }
        }
        is ExportPhase.Error -> {
            Card(modifier = Modifier.fillMaxWidth()) {
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
    val h = totalSec / 3600
    val m = (totalSec % 3600) / 60
    val s = totalSec % 60
    return if (h > 0) "%d:%02d:%02d".format(h, m, s) else "%d:%02d".format(m, s)
}
