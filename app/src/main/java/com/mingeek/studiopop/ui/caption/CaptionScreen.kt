package com.mingeek.studiopop.ui.caption

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.selection.selectable
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import com.mingeek.studiopop.data.caption.Cue
import com.mingeek.studiopop.data.caption.SpeechToText
import com.mingeek.studiopop.data.caption.SttEngine

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun CaptionScreen(
    onNavigateBack: () -> Unit,
    projectId: Long? = null,
    viewModel: CaptionViewModel = viewModel(factory = CaptionViewModel.Factory),
) {
    val state by viewModel.uiState.collectAsState()
    LaunchedEffect(projectId) { viewModel.bindProject(projectId) }

    val pickVideoLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.PickVisualMedia()
    ) { uri -> viewModel.onVideoSelected(uri) }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("자막 만들기") },
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
                .padding(horizontal = 16.dp, vertical = 8.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            VideoSection(
                hasVideo = state.videoUri != null,
                display = state.videoUri?.toString().orEmpty(),
                onPick = {
                    pickVideoLauncher.launch(
                        PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.VideoOnly)
                    )
                },
            )

            EngineSelector(
                options = state.engineOptions,
                selected = state.selectedEngine,
                onSelect = viewModel::onEngineSelected,
            )

            OutlinedTextField(
                value = state.language,
                onValueChange = viewModel::onLanguageChange,
                label = { Text("언어 코드 (예: ko, en, ja)") },
                singleLine = true,
                modifier = Modifier.fillMaxWidth(),
            )

            Row(
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                modifier = Modifier.fillMaxWidth(),
            ) {
                val busy = state.phase is CaptionPhase.Working
                Button(
                    onClick = viewModel::startTranscription,
                    enabled = state.videoUri != null && !busy,
                    modifier = Modifier.weight(1f),
                ) { Text("자막 생성") }

                OutlinedButton(
                    onClick = viewModel::saveSrt,
                    enabled = state.cues.isNotEmpty(),
                    modifier = Modifier.weight(1f),
                ) { Text("SRT 저장") }
            }

            PhaseIndicator(state.phase, state.savedFilePath, viewModel::dismissError)

            if (state.cues.isNotEmpty()) {
                CueList(
                    cues = state.cues,
                    onCueEdit = viewModel::updateCueText,
                )
            }
        }
    }
}

@Composable
private fun VideoSection(hasVideo: Boolean, display: String, onPick: () -> Unit) {
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Text("영상", style = MaterialTheme.typography.titleSmall)
            Text(
                text = if (hasVideo) display else "선택된 영상이 없습니다",
                style = MaterialTheme.typography.bodySmall,
                maxLines = 2,
                color = if (hasVideo) MaterialTheme.colorScheme.onSurface
                else MaterialTheme.colorScheme.onSurfaceVariant,
            )
            OutlinedButton(onClick = onPick, modifier = Modifier.fillMaxWidth()) {
                Text(if (hasVideo) "영상 변경" else "갤러리에서 선택")
            }
        }
    }
}

@Composable
private fun EngineSelector(
    options: List<EngineOption>,
    selected: SttEngine,
    onSelect: (SttEngine) -> Unit,
) {
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text("엔진", style = MaterialTheme.typography.titleSmall)
            options.forEach { opt ->
                val ready = opt.availability is SpeechToText.Availability.Ready
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .selectable(
                            selected = opt.engine == selected,
                            enabled = opt.availability !is SpeechToText.Availability.Unsupported &&
                                    !(opt.availability is SpeechToText.Availability.NeedsSetup &&
                                            !(opt.availability as SpeechToText.Availability.NeedsSetup).actionable),
                            role = Role.RadioButton,
                            onClick = { onSelect(opt.engine) },
                        )
                        .padding(vertical = 4.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    RadioButton(
                        selected = opt.engine == selected,
                        onClick = null,
                    )
                    Column(modifier = Modifier.padding(start = 8.dp)) {
                        Text(
                            opt.engine.displayName,
                            style = MaterialTheme.typography.bodyMedium,
                            fontWeight = if (opt.engine == selected) FontWeight.Bold else FontWeight.Normal,
                        )
                        val sub = when (val av = opt.availability) {
                            SpeechToText.Availability.Ready -> opt.engine.subtitle
                            is SpeechToText.Availability.NeedsSetup -> "⚙ ${av.reason}"
                            is SpeechToText.Availability.Unsupported -> "✕ ${av.reason}"
                        }
                        Text(
                            sub,
                            style = MaterialTheme.typography.bodySmall,
                            color = if (ready) MaterialTheme.colorScheme.onSurfaceVariant
                            else MaterialTheme.colorScheme.error,
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun PhaseIndicator(
    phase: CaptionPhase,
    savedPath: String?,
    onDismiss: () -> Unit,
) {
    when (phase) {
        CaptionPhase.Idle, CaptionPhase.Ready -> {
            if (savedPath != null) {
                Card(modifier = Modifier.fillMaxWidth()) {
                    Column(Modifier.padding(12.dp)) {
                        Text("✅ 저장됨", fontWeight = FontWeight.Bold)
                        Text(savedPath, style = MaterialTheme.typography.bodySmall)
                    }
                }
            }
        }
        is CaptionPhase.Working -> {
            val label = if (phase.total > 1) "${phase.phaseLabel} (${phase.current}/${phase.total})"
            else phase.phaseLabel
            BusyCard(label)
        }
        is CaptionPhase.Error -> {
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
private fun BusyCard(label: String) {
    Card(modifier = Modifier.fillMaxWidth()) {
        Row(
            Modifier.padding(12.dp).fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            CircularProgressIndicator(modifier = Modifier.padding(end = 4.dp))
            Text(label)
        }
    }
}

@Composable
private fun CueList(
    cues: List<Cue>,
    onCueEdit: (Int, String) -> Unit,
) {
    LazyColumn(
        verticalArrangement = Arrangement.spacedBy(8.dp),
        modifier = Modifier.fillMaxWidth(),
    ) {
        itemsIndexed(cues, key = { _, c -> "${c.startMs}-${c.endMs}-${c.index}" }) { idx, cue ->
            CueItem(cue = cue, onChange = { onCueEdit(idx, it) })
        }
    }
}

@Composable
private fun CueItem(cue: Cue, onChange: (String) -> Unit) {
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(Modifier.padding(10.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text(
                "${formatMs(cue.startMs)} → ${formatMs(cue.endMs)}",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            OutlinedTextField(
                value = cue.text,
                onValueChange = onChange,
                modifier = Modifier.fillMaxWidth(),
                minLines = 1,
            )
        }
    }
}

private fun formatMs(ms: Long): String {
    val totalSec = ms / 1000
    val h = totalSec / 3600
    val m = (totalSec % 3600) / 60
    val s = totalSec % 60
    val millis = ms % 1000
    return if (h > 0) "%d:%02d:%02d.%03d".format(h, m, s, millis)
    else "%02d:%02d.%03d".format(m, s, millis)
}
