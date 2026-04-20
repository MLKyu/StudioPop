package com.mingeek.studiopop.ui.project

import androidx.compose.foundation.clickable
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
import androidx.compose.material3.Card
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import com.mingeek.studiopop.data.project.AssetEntity
import com.mingeek.studiopop.data.project.AssetType

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ProjectDetailScreen(
    onNavigateBack: () -> Unit,
    onNavigateCaption: (Long) -> Unit,
    onNavigateEditor: (Long) -> Unit,
    onNavigateShorts: (Long) -> Unit,
    onNavigateThumbnail: (Long) -> Unit,
    onNavigateUpload: (Long) -> Unit,
    viewModel: ProjectDetailViewModel = viewModel(factory = ProjectDetailViewModel.Factory),
) {
    val project by viewModel.project.collectAsState()
    val assets by viewModel.assets.collectAsState()
    val id = viewModel.projectId

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(project?.title ?: "프로젝트") },
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
                .padding(16.dp)
                .verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            project?.let { p ->
                Card(modifier = Modifier.fillMaxWidth()) {
                    Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        Text(p.title, fontWeight = FontWeight.Bold)
                        Text(
                            "원본: ${p.sourceVideoUri}",
                            style = MaterialTheme.typography.bodySmall,
                            maxLines = 2,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
            }

            Text("단계", style = MaterialTheme.typography.titleMedium)
            StageCard(
                title = "① 자막 만들기",
                subtitle = "Whisper 전사 → SRT",
                done = assets.any { it.type == AssetType.CAPTION_SRT },
                onClick = { onNavigateCaption(id) },
            )
            StageCard(
                title = "② 영상 편집",
                subtitle = "트림 + 자막 번인 → MP4",
                done = assets.any { it.type == AssetType.EXPORT_VIDEO },
                onClick = { onNavigateEditor(id) },
            )
            StageCard(
                title = "③ 숏츠 생성",
                subtitle = "9:16 + 60초 이내",
                done = assets.any { it.type == AssetType.SHORTS },
                onClick = { onNavigateShorts(id) },
            )
            StageCard(
                title = "④ 썸네일 생성",
                subtitle = "프레임 + 카피 → 1280×720",
                done = assets.any { it.type == AssetType.THUMBNAIL },
                onClick = { onNavigateThumbnail(id) },
            )
            StageCard(
                title = "⑤ YouTube 업로드",
                subtitle = "편집본 + 썸네일 자동 첨부",
                done = assets.any { it.type == AssetType.UPLOADED_VIDEO_ID },
                onClick = { onNavigateUpload(id) },
            )

            if (assets.isNotEmpty()) {
                Text("산출물", style = MaterialTheme.typography.titleMedium)
                assets.forEach { a ->
                    AssetRow(a)
                }
            }
        }
    }
}

@Composable
private fun StageCard(
    title: String,
    subtitle: String,
    done: Boolean,
    onClick: () -> Unit,
) {
    Card(modifier = Modifier.fillMaxWidth().clickable { onClick() }) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(12.dp),
            verticalAlignment = androidx.compose.ui.Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween,
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text(title, fontWeight = FontWeight.SemiBold)
                Text(
                    subtitle,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            if (done) Text("✅", style = MaterialTheme.typography.titleMedium)
        }
    }
}

@Composable
private fun AssetRow(a: AssetEntity) {
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(Modifier.padding(10.dp)) {
            Text(a.type.name, style = MaterialTheme.typography.labelSmall)
            Text(a.value, style = MaterialTheme.typography.bodySmall, maxLines = 2)
        }
    }
}
