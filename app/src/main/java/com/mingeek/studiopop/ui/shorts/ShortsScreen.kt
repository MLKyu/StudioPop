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
import androidx.compose.material3.AssistChip
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Slider
import androidx.compose.material3.Switch
import androidx.compose.material3.Tab
import androidx.compose.material3.TabRow
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
import com.mingeek.studiopop.ui.common.ProjectQuickLoadCard

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
                .padding(innerPadding),
        ) {
            TabRow(selectedTabIndex = state.mode.ordinal) {
                ShortsMode.entries.forEach { m ->
                    Tab(
                        selected = state.mode == m,
                        onClick = { viewModel.setMode(m) },
                        text = { Text(m.label) },
                    )
                }
            }

            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .verticalScroll(rememberScrollState())
                    .padding(16.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                ModeHint(state.mode)

                if (state.hasProject) {
                    ProjectQuickLoadCard(
                        latestExportVideoPath = state.latestExportVideoPath,
                        latestSrtPath = state.latestSrtPath,
                        onLoadVideo = { viewModel.loadLatestExportAsClip() },
                        onLoadSrt = { viewModel.loadLatestSrt() },
                    )
                }

                ClipsCard(
                    state = state,
                    onPick = {
                        pickClipLauncher.launch(
                            PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.VideoOnly)
                        )
                    },
                    onRemove = viewModel::removeClip,
                    singleClipOnly = state.mode == ShortsMode.AI_HIGHLIGHT,
                )

                if (state.clips.isNotEmpty() && state.mode == ShortsMode.MANUAL) {
                    ManualDurationCard(state)
                }

                SrtCard(
                    state = state,
                    onPickSrt = { pickSrtLauncher.launch("*/*") },
                    onClearSrt = { viewModel.onSrtSelected(null) },
                    onToggleBurn = viewModel::onBurnCaptionsToggle,
                    showBurnSwitch = state.mode == ShortsMode.MANUAL,
                )

                when (state.mode) {
                    ShortsMode.MANUAL -> Unit
                    ShortsMode.AI_HIGHLIGHT -> AiHighlightCard(state, viewModel)
                    ShortsMode.TEMPLATE -> TemplateCard(state, viewModel)
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
}

@Composable
private fun ModeHint(mode: ShortsMode) {
    val text = when (mode) {
        ShortsMode.MANUAL ->
            "클립을 순서대로 이어붙여 60초 9:16 숏츠로 만듭니다. SRT 가 있으면 자막 번인도 가능."
        ShortsMode.AI_HIGHLIGHT ->
            "긴 영상 1개 + SRT + 주제 힌트를 Gemini 에 넣어 가장 바이럴 포텐셜이 높은 60초 구간을 자동 선별하고 상단 훅 텍스트를 생성합니다."
        ShortsMode.TEMPLATE ->
            "템플릿을 골라 클립에 자동 적용. 편집 수고 없이 5가지 스타일 중 선택하세요."
    }
    Text(
        text,
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
}

@Composable
private fun ClipsCard(
    state: ShortsUiState,
    onPick: () -> Unit,
    onRemove: (Int) -> Unit,
    singleClipOnly: Boolean,
) {
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            val title = if (singleClipOnly) "영상 (1개 선택)" else "영상 클립 (${state.clips.size}개)"
            Text(title, style = MaterialTheme.typography.titleSmall)
            if (state.clips.isEmpty()) {
                Text(
                    if (singleClipOnly) "AI 하이라이트는 긴 영상 1개를 대상으로 합니다."
                    else "아래 '영상 추가' 로 쇼츠에 이어붙일 영상을 차례로 선택하세요.",
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
                        IconButton(onClick = { onRemove(idx) }) {
                            Icon(Icons.Filled.Delete, contentDescription = "삭제")
                        }
                    }
                }
            }
            val canAdd = !singleClipOnly || state.clips.isEmpty()
            OutlinedButton(
                onClick = onPick,
                enabled = canAdd,
                modifier = Modifier.fillMaxWidth(),
            ) {
                Text(
                    when {
                        state.clips.isEmpty() -> "+ 영상 추가"
                        singleClipOnly -> "이 모드는 1개만 허용 (기존 영상 삭제 후 교체)"
                        else -> "+ 영상 더 추가"
                    }
                )
            }
        }
    }
}

@Composable
private fun ManualDurationCard(state: ShortsUiState) {
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

@Composable
private fun SrtCard(
    state: ShortsUiState,
    onPickSrt: () -> Unit,
    onClearSrt: () -> Unit,
    onToggleBurn: (Boolean) -> Unit,
    showBurnSwitch: Boolean,
) {
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Text(
                when (state.mode) {
                    ShortsMode.MANUAL -> "자막 번인 (선택)"
                    ShortsMode.AI_HIGHLIGHT -> "SRT 자막 (필수)"
                    ShortsMode.TEMPLATE -> "SRT 자막 (템플릿에 따라 필수)"
                },
                style = MaterialTheme.typography.titleSmall,
            )
            if (state.mode == ShortsMode.MANUAL) {
                Text(
                    "SRT 시각은 첫 클립 기준입니다.",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            } else if (state.mode == ShortsMode.AI_HIGHLIGHT) {
                Text(
                    "자막 cue 를 Gemini 가 분석해 하이라이트 구간을 골라냅니다.",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween,
                modifier = Modifier.fillMaxWidth(),
            ) {
                Text(if (state.cues.isEmpty()) "SRT 없음" else "불러온 큐: ${state.cues.size}개")
                if (showBurnSwitch) {
                    Switch(
                        checked = state.burnCaptions && state.cues.isNotEmpty(),
                        onCheckedChange = onToggleBurn,
                        enabled = state.cues.isNotEmpty(),
                    )
                }
            }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedButton(
                    onClick = onPickSrt,
                    modifier = Modifier.weight(1f),
                ) { Text(if (state.srtUri != null) "SRT 변경" else "SRT 선택") }
                if (state.srtUri != null) {
                    OutlinedButton(
                        onClick = onClearSrt,
                        modifier = Modifier.weight(1f),
                    ) { Text("해제") }
                }
            }
        }
    }
}

@UnstableApi
@Composable
private fun AiHighlightCard(state: ShortsUiState, viewModel: ShortsViewModel) {
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text("AI 하이라이트 설정", style = MaterialTheme.typography.titleSmall)
            OutlinedTextField(
                value = state.aiTopic,
                onValueChange = viewModel::setAiTopic,
                label = { Text("주제 / 분위기 힌트") },
                placeholder = { Text("예: 이 영상의 가장 웃긴 순간") },
                modifier = Modifier.fillMaxWidth(),
                singleLine = true,
            )
            val hint = when {
                state.aiSingleClip == null -> "먼저 영상 1개를 선택하세요."
                !state.hasCues -> "SRT 자막이 필요합니다. 자막 화면에서 먼저 생성하거나 위에서 파일을 불러오세요."
                state.aiTopic.isBlank() -> "주제 힌트를 한 줄 입력하면 분석이 가능합니다."
                else -> null
            }
            if (hint != null) {
                Text(
                    hint,
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            Button(
                onClick = viewModel::analyzeAiHighlight,
                enabled = state.canAnalyzeAi,
                modifier = Modifier.fillMaxWidth(),
            ) {
                Text(if (state.aiAnalyzing) "분석 중…" else "Gemini 로 하이라이트 찾기")
            }
            state.aiError?.let {
                Text(
                    "⚠ $it",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.error,
                )
            }
            state.aiSuggestion?.let { s ->
                Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Text("✅ 추천 구간", fontWeight = FontWeight.Bold)
                    Text(
                        "${formatMs(s.startMs)} → ${formatMs(s.endMs)}  (${formatMs(s.endMs - s.startMs)})",
                        style = MaterialTheme.typography.bodyMedium,
                    )
                    s.hookText?.takeIf { it.isNotBlank() }?.let {
                        Text("훅: $it", style = MaterialTheme.typography.bodySmall)
                    }
                    s.subText?.takeIf { it.isNotBlank() }?.let {
                        Text("보조: $it", style = MaterialTheme.typography.bodySmall)
                    }
                    s.reason?.takeIf { it.isNotBlank() }?.let {
                        Text(
                            "이유: $it",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
            }
        }
    }
}

@UnstableApi
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun TemplateCard(state: ShortsUiState, viewModel: ShortsViewModel) {
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text("템플릿 선택", style = MaterialTheme.typography.titleSmall)
            // 5개라 2줄로 분리 (FilterChip FlowRow 대신 고정 레이아웃)
            Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                ShortsTemplate.entries.chunked(3).forEach { rowItems ->
                    Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        rowItems.forEach { t ->
                            FilterChip(
                                selected = state.template == t,
                                onClick = { viewModel.setTemplate(t) },
                                label = { Text(t.label) },
                            )
                        }
                    }
                }
            }
            Text(
                state.template.description,
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )

            when (state.template) {
                ShortsTemplate.HOOK_INTRO -> HookIntroConfig(state, viewModel)
                ShortsTemplate.JUMP_CUT -> JumpCutConfig(state, viewModel)
                ShortsTemplate.BIG_CAPTION, ShortsTemplate.TYPING_CAPTION -> {
                    if (!state.hasCues) {
                        AssistChip(
                            onClick = {},
                            enabled = false,
                            label = { Text("SRT 필요 — 위에서 자막 파일을 불러오세요") },
                        )
                    }
                }
                ShortsTemplate.BEST_CUTS -> {
                    Text(
                        "각 클립의 중간 구간을 자동 추출합니다. 클립 수에 따라 60초를 균등 분할.",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        }
    }
}

@UnstableApi
@Composable
private fun HookIntroConfig(state: ShortsUiState, viewModel: ShortsViewModel) {
    OutlinedTextField(
        value = state.hookIntroText,
        onValueChange = viewModel::setHookIntroText,
        label = { Text("훅 텍스트 (첫 3초 상단 대형)") },
        placeholder = { Text("예: 이거 꼭 보세요") },
        modifier = Modifier.fillMaxWidth(),
        singleLine = true,
    )
}

@UnstableApi
@Composable
private fun JumpCutConfig(state: ShortsUiState, viewModel: ShortsViewModel) {
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Text(
            "각 클립에서 앞 ${formatCutSeconds(state.jumpCutDurationMs)} 만큼 추출",
            style = MaterialTheme.typography.bodyMedium,
        )
        Slider(
            value = state.jumpCutDurationMs.toFloat(),
            onValueChange = { viewModel.setJumpCutDurationMs(it.toLong()) },
            valueRange = ShortsUiState.JUMP_CUT_MIN_MS.toFloat()..ShortsUiState.JUMP_CUT_MAX_MS.toFloat(),
            steps = 8, // 500ms 단위로 9 스텝 (500, 1000, ..., 5000)
        )
        Text(
            "${formatCutSeconds(ShortsUiState.JUMP_CUT_MIN_MS)} ~ ${formatCutSeconds(ShortsUiState.JUMP_CUT_MAX_MS)}",
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
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

private fun formatCutSeconds(ms: Long): String {
    val sec = ms / 1000.0
    return "%.1f초".format(sec)
}
