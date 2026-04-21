package com.mingeek.studiopop.ui.thumbnail

import android.graphics.Bitmap
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.AssistChip
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Slider
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel

@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
fun ThumbnailScreen(
    onNavigateBack: () -> Unit,
    projectId: Long? = null,
    viewModel: ThumbnailViewModel = viewModel(factory = ThumbnailViewModel.Factory),
) {
    val state by viewModel.uiState.collectAsState()
    LaunchedEffect(projectId) { viewModel.bindProject(projectId) }

    val pickVideoLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.PickVisualMedia()
    ) { uri -> viewModel.onVideoSelected(uri) }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("썸네일 만들기") },
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
                .imePadding()
                .verticalScroll(rememberScrollState())
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Card(modifier = Modifier.fillMaxWidth()) {
                Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text("영상", style = MaterialTheme.typography.titleSmall)
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
                        Text("프레임 위치: ${formatMs(state.framePositionMs)} / ${formatMs(state.durationMs)}")
                        Slider(
                            value = state.framePositionMs.toFloat(),
                            onValueChange = { viewModel.onPositionChange(it.toLong()) },
                            valueRange = 0f..state.durationMs.toFloat(),
                            onValueChangeFinished = { viewModel.extractFrame() },
                        )
                    }
                }
            }

            state.frameBitmap?.let { bmp ->
                Card(modifier = Modifier.fillMaxWidth()) {
                    Column(Modifier.padding(8.dp)) {
                        Text(
                            "원본 프레임",
                            style = MaterialTheme.typography.labelSmall,
                            modifier = Modifier.padding(bottom = 4.dp),
                        )
                        BitmapPreview(bitmap = bmp)
                    }
                }
            }

            OutlinedTextField(
                value = state.mainText,
                onValueChange = viewModel::onMainTextChange,
                label = { Text("메인 카피") },
                singleLine = false,
                modifier = Modifier.fillMaxWidth(),
            )
            OutlinedTextField(
                value = state.subText,
                onValueChange = viewModel::onSubTextChange,
                label = { Text("서브 카피 (상단, 선택)") },
                singleLine = true,
                modifier = Modifier.fillMaxWidth(),
            )
            OutlinedTextField(
                value = state.topic,
                onValueChange = viewModel::onTopicChange,
                label = { Text("영상 주제 (Claude 제안용)") },
                singleLine = true,
                modifier = Modifier.fillMaxWidth(),
            )

            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedButton(
                    onClick = viewModel::suggestCopies,
                    enabled = state.phase !is ThumbnailPhase.SuggestingCopies,
                    modifier = Modifier.weight(1f),
                ) { Text("Claude 카피 제안") }
                Button(
                    onClick = viewModel::composePreview,
                    enabled = state.frameBitmap != null,
                    modifier = Modifier.weight(1f),
                ) { Text("합성 미리보기") }
            }

            if (state.copyCandidates.isNotEmpty()) {
                Card(modifier = Modifier.fillMaxWidth()) {
                    Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                        Text("제안", style = MaterialTheme.typography.titleSmall)
                        FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                            state.copyCandidates.forEach { c ->
                                AssistChip(
                                    onClick = { viewModel.adoptCopy(c) },
                                    label = { Text(c) },
                                )
                            }
                        }
                    }
                }
            }

            state.composedBitmap?.let { bmp ->
                Card(modifier = Modifier.fillMaxWidth()) {
                    Column(Modifier.padding(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        Text(
                            "합성 결과 (1280×720)",
                            style = MaterialTheme.typography.labelSmall,
                        )
                        BitmapPreview(bitmap = bmp)
                        Button(
                            onClick = viewModel::savePng,
                            modifier = Modifier.fillMaxWidth(),
                        ) { Text("PNG 저장") }
                    }
                }
            }

            PhaseIndicator(state.phase, viewModel::dismissMessage)
        }
    }
}

@Composable
private fun BitmapPreview(bitmap: Bitmap) {
    val imageBitmap = remember(bitmap) { bitmap.asImageBitmap() }
    Image(
        bitmap = imageBitmap,
        contentDescription = null,
        modifier = Modifier
            .fillMaxWidth()
            .aspectRatio(16f / 9f),
    )
}

@Composable
private fun PhaseIndicator(phase: ThumbnailPhase, onDismiss: () -> Unit) {
    when (phase) {
        ThumbnailPhase.Idle -> Unit
        ThumbnailPhase.ExtractingFrame -> StatusCard("프레임 추출 중...")
        ThumbnailPhase.Composing -> StatusCard("합성 중...")
        ThumbnailPhase.SuggestingCopies -> StatusCard("Claude 카피 제안 중...")
        is ThumbnailPhase.Saved -> {
            Card(modifier = Modifier.fillMaxWidth()) {
                Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    Text("✅ 저장 완료", fontWeight = FontWeight.Bold)
                    Text(phase.path, style = MaterialTheme.typography.bodySmall)
                    OutlinedButton(onClick = onDismiss) { Text("확인") }
                }
            }
        }
        is ThumbnailPhase.Error -> {
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

@Composable
private fun StatusCard(label: String) {
    Card(modifier = Modifier.fillMaxWidth()) {
        Text(label, modifier = Modifier.padding(12.dp))
    }
}

private fun formatMs(ms: Long): String {
    val totalSec = ms / 1000
    val h = totalSec / 3600
    val m = (totalSec % 3600) / 60
    val s = totalSec % 60
    return if (h > 0) "%d:%02d:%02d".format(h, m, s) else "%d:%02d".format(m, s)
}
