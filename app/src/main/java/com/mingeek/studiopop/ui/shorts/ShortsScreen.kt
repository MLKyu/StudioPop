package com.mingeek.studiopop.ui.shorts

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
import androidx.compose.material3.Switch
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

@OptIn(ExperimentalMaterial3Api::class)
@UnstableApi
@Composable
fun ShortsScreen(
    onNavigateBack: () -> Unit,
    projectId: Long? = null,
    viewModel: ShortsViewModel = viewModel(factory = ShortsViewModel.Factory),
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
                title = { Text("숏츠 만들기") },
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
            Card(modifier = Modifier.fillMaxWidth()) {
                Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    Text("원본 영상", style = MaterialTheme.typography.titleSmall)
                    Text(
                        text = state.videoUri?.toString()
                            ?: "영상이 선택되지 않았습니다",
                        style = MaterialTheme.typography.bodySmall,
                        maxLines = 2,
                    )
                    OutlinedButton(
                        onClick = {
                            pickVideoLauncher.launch(
                                PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.VideoOnly)
                            )
                        },
                        modifier = Modifier.fillMaxWidth(),
                    ) {
                        Text(if (state.videoUri != null) "영상 변경" else "갤러리에서 선택")
                    }
                }
            }

            if (state.durationMs > 0) {
                Card(modifier = Modifier.fillMaxWidth()) {
                    Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        Text("구간 (최대 60초)", style = MaterialTheme.typography.titleSmall)
                        Text(
                            "${formatMs(state.trimStartMs)}  →  ${formatMs(state.trimEndMs)}" +
                                    "  (${formatMs(state.durationSelectedMs)})",
                        )
                        RangeSlider(
                            value = state.trimStartMs.toFloat()..state.trimEndMs.toFloat(),
                            onValueChange = { range ->
                                viewModel.onTrimChange(range.start.toLong(), range.endInclusive.toLong())
                            },
                            valueRange = 0f..state.durationMs.toFloat(),
                        )
                    }
                }
            }

            Card(modifier = Modifier.fillMaxWidth()) {
                Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    Text("자막 번인 (선택)", style = MaterialTheme.typography.titleSmall)
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.SpaceBetween,
                        modifier = Modifier.fillMaxWidth(),
                    ) {
                        Text(if (state.cues.isEmpty()) "SRT 없음" else "불러온 큐: ${state.cues.size}개")
                        Switch(
                            checked = state.burnCaptions && state.cues.isNotEmpty(),
                            onCheckedChange = viewModel::onBurnCaptionsToggle,
                            enabled = state.cues.isNotEmpty(),
                        )
                    }
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        OutlinedButton(
                            onClick = { pickSrtLauncher.launch("*/*") },
                            modifier = Modifier.weight(1f),
                        ) { Text(if (state.srtUri != null) "SRT 변경" else "SRT 선택") }
                        if (state.srtUri != null) {
                            OutlinedButton(
                                onClick = { viewModel.onSrtSelected(null) },
                                modifier = Modifier.weight(1f),
                            ) { Text("해제") }
                        }
                    }
                }
            }

            Button(
                onClick = viewModel::startExport,
                enabled = state.canExport,
                modifier = Modifier.fillMaxWidth(),
            ) { Text("9:16 내보내기") }

            PhaseIndicator(state.phase, viewModel::dismissMessage)
        }
    }
}

@Composable
private fun PhaseIndicator(phase: ShortsPhase, onDismiss: () -> Unit) {
    when (phase) {
        ShortsPhase.Idle -> Unit
        is ShortsPhase.Running -> {
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
        is ShortsPhase.Success -> {
            Card(modifier = Modifier.fillMaxWidth()) {
                Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    Text("✅ 숏츠 생성 완료", fontWeight = FontWeight.Bold)
                    Text(phase.outputPath, style = MaterialTheme.typography.bodySmall)
                    OutlinedButton(onClick = onDismiss) { Text("확인") }
                }
            }
        }
        is ShortsPhase.Error -> {
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
