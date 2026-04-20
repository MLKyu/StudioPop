package com.mingeek.studiopop.ui.upload

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.IntentSenderRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AssistChip
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import com.mingeek.studiopop.data.youtube.PrivacyStatus

@androidx.compose.material3.ExperimentalMaterial3Api
@Composable
fun UploadScreen(
    onNavigateBack: (() -> Unit)? = null,
    projectId: Long? = null,
    viewModel: UploadViewModel = viewModel(factory = UploadViewModel.Factory),
) {
    val state by viewModel.uiState.collectAsState()
    val consentRequest by viewModel.consentRequest.collectAsState()
    androidx.compose.runtime.LaunchedEffect(projectId) { viewModel.bindProject(projectId) }

    val pickVideoLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.PickVisualMedia()
    ) { uri -> viewModel.onVideoSelected(uri) }

    val consentLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.StartIntentSenderForResult()
    ) { result ->
        viewModel.onConsentResult(result.data)
    }

    LaunchedEffect(consentRequest) {
        consentRequest?.let { pi ->
            consentLauncher.launch(IntentSenderRequest.Builder(pi.intentSender).build())
            viewModel.onConsentLaunched()
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("YouTube 업로드") },
                navigationIcon = {
                    if (onNavigateBack != null) {
                        IconButton(onClick = onNavigateBack) {
                            Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "뒤로")
                        }
                    }
                }
            )
        }
    ) { innerPadding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding)
                .padding(16.dp)
                .verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            AuthSection(
                hasAccessToken = state.hasAccessToken,
                isAuthorizing = state.phase is UploadPhase.Authorizing,
                onSignIn = viewModel::signIn,
            )

            VideoPickSection(
                hasVideo = state.videoUri != null,
                uriDisplay = state.videoUri?.toString().orEmpty(),
                onPick = {
                    pickVideoLauncher.launch(
                        androidx.activity.result.PickVisualMediaRequest(
                            ActivityResultContracts.PickVisualMedia.VideoOnly
                        )
                    )
                },
            )

            OutlinedTextField(
                value = state.title,
                onValueChange = viewModel::onTitleChange,
                label = { Text("제목") },
                singleLine = true,
                modifier = Modifier.fillMaxWidth(),
            )

            OutlinedTextField(
                value = state.description,
                onValueChange = viewModel::onDescriptionChange,
                label = { Text("설명") },
                minLines = 3,
                modifier = Modifier.fillMaxWidth(),
            )

            OutlinedTextField(
                value = state.tagsRaw,
                onValueChange = viewModel::onTagsChange,
                label = { Text("태그 (쉼표 구분)") },
                singleLine = true,
                modifier = Modifier.fillMaxWidth(),
            )

            PrivacySelector(
                selected = state.privacy,
                onSelect = viewModel::onPrivacyChange,
            )

            if (state.thumbnailPath != null) {
                Card(modifier = Modifier.fillMaxWidth()) {
                    Row(
                        modifier = Modifier.fillMaxWidth().padding(12.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.SpaceBetween,
                    ) {
                        Column(Modifier.weight(1f)) {
                            Text("썸네일 자동 첨부", fontWeight = FontWeight.Medium)
                            Text(
                                state.thumbnailPath.orEmpty(),
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                maxLines = 1,
                            )
                        }
                        Switch(
                            checked = state.attachThumbnail,
                            onCheckedChange = viewModel::onAttachThumbnailToggle,
                        )
                    }
                }
            }

            Spacer(Modifier.height(4.dp))

            Button(
                onClick = viewModel::startUpload,
                enabled = state.canUpload,
                modifier = Modifier.fillMaxWidth(),
            ) {
                Text("업로드 시작")
            }

            StatusSection(
                phase = state.phase,
                onDismiss = viewModel::dismissMessage,
            )
        }
    }
}

@Composable
private fun AuthSection(
    hasAccessToken: Boolean,
    isAuthorizing: Boolean,
    onSignIn: () -> Unit,
) {
    Card(modifier = Modifier.fillMaxWidth()) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(12.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween,
        ) {
            Text(
                text = if (hasAccessToken) "✓ 구글 계정 인증됨" else "구글 계정 인증 필요",
                style = MaterialTheme.typography.bodyLarge,
                fontWeight = FontWeight.Medium,
            )
            OutlinedButton(onClick = onSignIn, enabled = !isAuthorizing) {
                Text(if (hasAccessToken) "다시 인증" else "구글 로그인")
            }
        }
    }
}

@Composable
private fun VideoPickSection(
    hasVideo: Boolean,
    uriDisplay: String,
    onPick: () -> Unit,
) {
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(12.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Text("영상 선택", style = MaterialTheme.typography.titleSmall)
            if (hasVideo) {
                Text(
                    text = uriDisplay,
                    style = MaterialTheme.typography.bodySmall,
                    maxLines = 2,
                )
            } else {
                Text(
                    text = "선택된 영상이 없습니다",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            OutlinedButton(onClick = onPick, modifier = Modifier.fillMaxWidth()) {
                Text(if (hasVideo) "영상 변경" else "갤러리에서 선택")
            }
        }
    }
}

@Composable
private fun PrivacySelector(
    selected: PrivacyStatus,
    onSelect: (PrivacyStatus) -> Unit,
) {
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Text("공개 범위", style = MaterialTheme.typography.titleSmall)
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            PrivacyStatus.entries.forEach { option ->
                FilterChip(
                    selected = option == selected,
                    onClick = { onSelect(option) },
                    label = {
                        Text(
                            when (option) {
                                PrivacyStatus.PUBLIC -> "공개"
                                PrivacyStatus.UNLISTED -> "일부 공개"
                                PrivacyStatus.PRIVATE -> "비공개"
                            }
                        )
                    },
                )
            }
        }
    }
}

@Composable
private fun StatusSection(
    phase: UploadPhase,
    onDismiss: () -> Unit,
) {
    when (phase) {
        UploadPhase.Idle, UploadPhase.Authorizing -> Unit
        is UploadPhase.Uploading -> {
            Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Text("업로드 중... ${(phase.progress * 100).toInt()}%")
                LinearProgressIndicator(
                    progress = { phase.progress },
                    modifier = Modifier.fillMaxWidth(),
                )
            }
        }
        is UploadPhase.Success -> {
            Card(modifier = Modifier.fillMaxWidth()) {
                Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    Text("✅ 업로드 성공", fontWeight = FontWeight.Bold)
                    Text("videoId: ${phase.videoId}", style = MaterialTheme.typography.bodySmall)
                    AssistChip(onClick = onDismiss, label = { Text("확인") })
                }
            }
        }
        is UploadPhase.Error -> {
            Card(modifier = Modifier.fillMaxWidth()) {
                Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    Text("⚠️ 오류", fontWeight = FontWeight.Bold)
                    Text(phase.message, style = MaterialTheme.typography.bodySmall)
                    AssistChip(onClick = onDismiss, label = { Text("닫기") })
                }
            }
        }
    }
}
