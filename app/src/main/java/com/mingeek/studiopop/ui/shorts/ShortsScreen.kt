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
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
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

    val pickClipLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.PickVisualMedia()
    ) { uri -> viewModel.addClip(uri) }

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
                .verticalScroll(rememberScrollState())
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Card(modifier = Modifier.fillMaxWidth()) {
                Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    Text("영상 클립 (${state.clips.size}개)", style = MaterialTheme.typography.titleSmall)
                    if (state.clips.isEmpty()) {
                        Text(
                            "아래 '영상 추가' 로 쇼츠에 이어붙일 영상을 차례로 선택하세요.",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    } else {
                        state.clips.forEachIndexed { idx, clip ->
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                verticalAlignment = Alignment.CenterVertically,
                            ) {
                                Column(modifier = Modifier.weight(1f)) {
                                    Text(
                                        "${idx + 1}. ${clip.uri.toString().substringAfterLast('/')}",
                                        style = MaterialTheme.typography.bodyMedium,
                                        maxLines = 1,
                                    )
                                    Text(
                                        formatMs(clip.durationMs),
                                        style = MaterialTheme.typography.labelSmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    )
                                }
                                IconButton(onClick = { viewModel.removeClip(idx) }) {
                                    Icon(Icons.Filled.Delete, contentDescription = "삭제")
                                }
                            }
                        }
                    }
                    OutlinedButton(
                        onClick = {
                            pickClipLauncher.launch(
                                PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.VideoOnly)
                            )
                        },
                        modifier = Modifier.fillMaxWidth(),
                    ) { Text(if (state.clips.isEmpty()) "+ 영상 추가" else "+ 영상 더 추가") }
                }
            }

            if (state.clips.isNotEmpty()) {
                Card(modifier = Modifier.fillMaxWidth()) {
                    Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        Text(
                            "총 길이: ${formatMs(state.totalDurationMs)}",
                            style = MaterialTheme.typography.titleSmall,
                        )
                        if (state.willTruncate) {
                            Text(
                                "⚠ 60초 초과 — 끝부분은 자동으로 잘려 ${formatMs(ShortsUiState.SHORTS_MAX_MS)} 로 맞춤",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.tertiary,
                            )
                        } else {
                            Text(
                                "최종 숏츠 길이: ${formatMs(state.finalDurationMs)}",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    }
                }
            }

            Card(modifier = Modifier.fillMaxWidth()) {
                Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    Text("자막 번인 (선택)", style = MaterialTheme.typography.titleSmall)
                    Text(
                        "SRT 시각은 첫 클립 기준입니다. 두 번째 이상 클립 구간의 자막은 export 되지 않아요.",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
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
    val m = totalSec / 60
    val s = totalSec % 60
    return "%d:%02d".format(m, s)
}
