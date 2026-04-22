package com.mingeek.studiopop.ui.thumbnail

import android.graphics.Bitmap
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Slider
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import com.mingeek.studiopop.data.thumbnail.DecorationStyle
import com.mingeek.studiopop.data.thumbnail.TextAnchor
import com.mingeek.studiopop.data.thumbnail.ThumbnailVariant
import com.mingeek.studiopop.ui.common.ProjectQuickLoadCard

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ThumbnailScreen(
    onNavigateBack: () -> Unit,
    projectId: Long? = null,
    viewModel: ThumbnailViewModel = viewModel(factory = ThumbnailViewModel.Factory),
) {
    val state by viewModel.uiState.collectAsState()
    val ctx = androidx.compose.ui.platform.LocalContext.current

    // 영상 선택 (SAF / PhotoPicker 우선)
    val pickLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.PickVisualMedia()
    ) { uri -> viewModel.onVideoSelected(uri) }

    androidx.compose.runtime.LaunchedEffect(projectId) { viewModel.bindProject(projectId) }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("썸네일 만들기") },
                navigationIcon = {
                    IconButton(onClick = onNavigateBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "뒤로")
                    }
                },
            )
        },
    ) { innerPadding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding)
                .verticalScroll(rememberScrollState())
                .imePadding()
                .navigationBarsPadding()
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            if (state.hasProject && state.latestExportVideoPath != null) {
                ProjectQuickLoadCard(
                    latestExportVideoPath = state.latestExportVideoPath,
                    latestSrtPath = null,
                    onLoadVideo = { viewModel.loadLatestExportAsInput() },
                    onLoadSrt = null,
                )
            }

            if (state.videoUri == null) {
                OutlinedButton(
                    onClick = {
                        pickLauncher.launch(
                            PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.VideoOnly)
                        )
                    },
                    modifier = Modifier.fillMaxWidth(),
                ) { Text("갤러리에서 영상 선택") }
            } else {
                FrameSelector(
                    frame = state.frameBitmap,
                    positionMs = state.framePositionMs,
                    durationMs = state.durationMs,
                    onPositionChange = viewModel::onPositionChange,
                    onExtract = viewModel::extractFrame,
                    onPickAnother = {
                        pickLauncher.launch(
                            PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.VideoOnly)
                        )
                    },
                )

                OutlinedTextField(
                    value = state.topic,
                    onValueChange = viewModel::onTopicChange,
                    label = { Text("영상 주제 (한 줄로)") },
                    placeholder = { Text("예: 2만원으로 만든 미친 김치찌개") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )

                Button(
                    onClick = viewModel::generateVariants,
                    enabled = state.frameBitmap != null &&
                        state.phase !is ThumbnailPhase.GeneratingVariants &&
                        state.phase !is ThumbnailPhase.Composing,
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    val label = when (state.phase) {
                        ThumbnailPhase.GeneratingVariants -> "AI 가 구성안 짜는 중..."
                        ThumbnailPhase.Composing -> "썸네일 합성 중..."
                        else -> "시선 끄는 썸네일 추천받기"
                    }
                    Text(label)
                }

                if (state.variants.isNotEmpty()) {
                    Text(
                        "마음에 드는 안을 골라 세부 조정하세요",
                        style = MaterialTheme.typography.titleSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    VariantGrid(
                        variants = state.variants,
                        previews = state.composedPreviews,
                        onSelect = viewModel::selectVariant,
                    )
                }

                PhaseIndicator(state.phase) { viewModel.dismissMessage() }
            }
        }
    }

    // 편집 바텀시트
    state.selectedVariantId?.let { id ->
        val variant = state.variants.firstOrNull { it.id == id } ?: return@let
        val preview = state.composedPreviews[id]
        VariantEditorSheet(
            variant = variant,
            previewBitmap = preview,
            onDismiss = viewModel::closeSelection,
            onChange = viewModel::updateVariant,
            onSave = viewModel::saveSelected,
        )
    }
}

// --- Sub-composables -----------------------------------------------------

@Composable
private fun FrameSelector(
    frame: Bitmap?,
    positionMs: Long,
    durationMs: Long,
    onPositionChange: (Long) -> Unit,
    onExtract: () -> Unit,
    onPickAnother: () -> Unit,
) {
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Card(
            modifier = Modifier.fillMaxWidth().aspectRatio(16f / 9f),
            colors = CardDefaults.cardColors(containerColor = Color.Black),
        ) {
            if (frame != null) {
                Image(
                    bitmap = frame.asImageBitmap(),
                    contentDescription = null,
                    contentScale = ContentScale.Fit,
                    modifier = Modifier.fillMaxSize(),
                )
            } else {
                Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    Text("프레임 추출 중…", color = Color.White)
                }
            }
        }
        if (durationMs > 0) {
            Slider(
                value = positionMs.toFloat(),
                onValueChange = { onPositionChange(it.toLong()) },
                onValueChangeFinished = onExtract,
                valueRange = 0f..durationMs.toFloat(),
            )
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
            ) {
                Text(formatMs(positionMs), style = MaterialTheme.typography.labelSmall)
                Text(formatMs(durationMs), style = MaterialTheme.typography.labelSmall)
            }
        }
        OutlinedButton(onClick = onPickAnother, modifier = Modifier.fillMaxWidth()) {
            Text("다른 영상 선택")
        }
    }
}

@Composable
private fun VariantGrid(
    variants: List<ThumbnailVariant>,
    previews: Map<String, Bitmap>,
    onSelect: (String) -> Unit,
) {
    // 컬럼 단위 grid 를 sheet 내부에 두면 scroll conflict 있어 화면 자체가 scroll 인 현 구조에선
    // LazyVerticalGrid 의 고정 높이가 필요. 아이템 당 대략 150dp 로 잡아 높이 계산.
    val rows = ((variants.size + 1) / 2).coerceAtLeast(1)
    val gridHeight = (rows * 170).dp

    LazyVerticalGrid(
        columns = GridCells.Fixed(2),
        modifier = Modifier.fillMaxWidth().height(gridHeight),
        verticalArrangement = Arrangement.spacedBy(8.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        contentPadding = androidx.compose.foundation.layout.PaddingValues(vertical = 4.dp),
        userScrollEnabled = false,
    ) {
        items(items = variants, key = { it.id }) { v ->
            VariantCard(variant = v, preview = previews[v.id], onClick = { onSelect(v.id) })
        }
    }
}

@Composable
private fun VariantCard(
    variant: ThumbnailVariant,
    preview: Bitmap?,
    onClick: () -> Unit,
) {
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .aspectRatio(16f / 9f)
            .clip(RoundedCornerShape(8.dp))
            .clickable(onClick = onClick),
        colors = CardDefaults.cardColors(containerColor = Color.DarkGray),
    ) {
        Box(Modifier.fillMaxSize()) {
            if (preview != null) {
                Image(
                    bitmap = preview.asImageBitmap(),
                    contentDescription = variant.mainText,
                    contentScale = ContentScale.Crop,
                    modifier = Modifier.fillMaxSize(),
                )
            } else {
                Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    Text("합성중…", color = Color.White, style = MaterialTheme.typography.labelSmall)
                }
            }
            variant.reasoning?.takeIf { it.isNotBlank() }?.let {
                Box(
                    modifier = Modifier
                        .align(Alignment.BottomCenter)
                        .fillMaxWidth()
                        .background(Color.Black.copy(alpha = 0.55f))
                        .padding(horizontal = 6.dp, vertical = 3.dp),
                ) {
                    Text(
                        it,
                        color = Color.White,
                        style = MaterialTheme.typography.labelSmall,
                        maxLines = 2,
                    )
                }
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun VariantEditorSheet(
    variant: ThumbnailVariant,
    previewBitmap: Bitmap?,
    onDismiss: () -> Unit,
    onChange: (ThumbnailVariant) -> Unit,
    onSave: () -> Unit,
) {
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = sheetState,
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .verticalScroll(rememberScrollState())
                .imePadding()
                .navigationBarsPadding()
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Text("세부 조정", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)

            previewBitmap?.let {
                Image(
                    bitmap = it.asImageBitmap(),
                    contentDescription = null,
                    contentScale = ContentScale.Fit,
                    modifier = Modifier
                        .fillMaxWidth()
                        .aspectRatio(16f / 9f)
                        .clip(RoundedCornerShape(8.dp)),
                )
            }

            OutlinedTextField(
                value = variant.mainText,
                onValueChange = { onChange(variant.copy(mainText = it)) },
                label = { Text("메인 카피") },
                singleLine = true,
                modifier = Modifier.fillMaxWidth(),
            )
            OutlinedTextField(
                value = variant.subText,
                onValueChange = { onChange(variant.copy(subText = it)) },
                label = { Text("보조 카피 (선택)") },
                singleLine = true,
                modifier = Modifier.fillMaxWidth(),
            )

            Text("텍스트 위치", style = MaterialTheme.typography.labelLarge)
            Row(
                modifier = Modifier.fillMaxWidth().horizontalScrollSafe(),
                horizontalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                TextAnchor.entries.forEach { a ->
                    FilterChip(
                        selected = variant.anchor == a,
                        onClick = { onChange(variant.copy(anchor = a)) },
                        label = { Text(anchorLabel(a)) },
                    )
                }
            }

            Text("데코레이션", style = MaterialTheme.typography.labelLarge)
            Row(
                modifier = Modifier.fillMaxWidth().horizontalScrollSafe(),
                horizontalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                DecorationStyle.entries.forEach { d ->
                    FilterChip(
                        selected = variant.decoration == d,
                        onClick = { onChange(variant.copy(decoration = d)) },
                        label = { Text(decorationLabel(d)) },
                    )
                }
            }

            Text("크기 ${(variant.sizeScale * 100).toInt()}%", style = MaterialTheme.typography.labelLarge)
            Slider(
                value = variant.sizeScale,
                onValueChange = { onChange(variant.copy(sizeScale = it)) },
                valueRange = 0.7f..1.4f,
            )

            Text("색상", style = MaterialTheme.typography.labelLarge)
            ColorSwatchRow(
                label = "메인",
                selected = variant.mainColor,
                onSelect = { onChange(variant.copy(mainColor = it)) },
                palette = MAIN_COLOR_PALETTE,
            )
            ColorSwatchRow(
                label = "강조",
                selected = variant.accentColor,
                onSelect = { onChange(variant.copy(accentColor = it)) },
                palette = ACCENT_COLOR_PALETTE,
            )

            Button(onClick = onSave, modifier = Modifier.fillMaxWidth()) {
                Text("이 썸네일로 PNG 저장")
            }
        }
    }
}

@Composable
private fun ColorSwatchRow(
    label: String,
    selected: Int,
    onSelect: (Int) -> Unit,
    palette: List<Int>,
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(label, modifier = Modifier.width(48.dp), style = MaterialTheme.typography.labelMedium)
        Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            palette.forEach { c ->
                Box(
                    modifier = Modifier
                        .size(32.dp)
                        .clip(RoundedCornerShape(6.dp))
                        .background(Color(c))
                        .border(
                            width = if (c == selected) 3.dp else 1.dp,
                            color = if (c == selected) MaterialTheme.colorScheme.primary else Color.Gray,
                            shape = RoundedCornerShape(6.dp),
                        )
                        .clickable { onSelect(c) }
                )
            }
        }
    }
}

@Composable
private fun PhaseIndicator(phase: ThumbnailPhase, onDismiss: () -> Unit) {
    when (phase) {
        ThumbnailPhase.Idle,
        ThumbnailPhase.ExtractingFrame -> Unit
        ThumbnailPhase.GeneratingVariants,
        ThumbnailPhase.Composing -> {
            LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
        }
        is ThumbnailPhase.Saved -> {
            Card(modifier = Modifier.fillMaxWidth()) {
                Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Text("✅ 저장됨", fontWeight = FontWeight.Bold)
                    Text(phase.path, style = MaterialTheme.typography.bodySmall)
                    OutlinedButton(onClick = onDismiss) { Text("확인") }
                }
            }
        }
        is ThumbnailPhase.Error -> {
            Card(modifier = Modifier.fillMaxWidth()) {
                Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Text("⚠️ 오류", fontWeight = FontWeight.Bold)
                    Text(phase.message, style = MaterialTheme.typography.bodySmall)
                    OutlinedButton(onClick = onDismiss) { Text("닫기") }
                }
            }
        }
    }
}

// --- helpers -------------------------------------------------------------

private fun formatMs(ms: Long): String {
    if (ms <= 0) return "0:00"
    val s = ms / 1000
    return "%d:%02d".format(s / 60, s % 60)
}

private fun anchorLabel(a: TextAnchor): String = when (a) {
    TextAnchor.TOP_LEFT -> "좌상"
    TextAnchor.TOP_RIGHT -> "우상"
    TextAnchor.BOTTOM_LEFT -> "좌하"
    TextAnchor.BOTTOM_RIGHT -> "우하"
    TextAnchor.CENTER -> "중앙"
}

private fun decorationLabel(d: DecorationStyle): String = when (d) {
    DecorationStyle.NONE -> "없음"
    DecorationStyle.BOX -> "박스"
    DecorationStyle.OUTLINE -> "두꺼운 외곽선"
    DecorationStyle.ARROW -> "화살표"
    DecorationStyle.GLOW -> "글로우"
}

private val MAIN_COLOR_PALETTE: List<Int> = listOf(
    android.graphics.Color.WHITE,
    android.graphics.Color.parseColor("#FFEB00"),
    android.graphics.Color.parseColor("#FF1744"),
    android.graphics.Color.parseColor("#000000"),
    android.graphics.Color.parseColor("#00E5FF"),
)

private val ACCENT_COLOR_PALETTE: List<Int> = listOf(
    android.graphics.Color.parseColor("#FFEB00"),
    android.graphics.Color.parseColor("#FF1744"),
    android.graphics.Color.parseColor("#00E5FF"),
    android.graphics.Color.parseColor("#76FF03"),
    android.graphics.Color.parseColor("#000000"),
    android.graphics.Color.WHITE,
)

private fun Modifier.horizontalScrollSafe(): Modifier = this // chip row 고정 너비 → scroll 미필요
