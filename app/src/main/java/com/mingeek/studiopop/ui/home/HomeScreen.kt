package com.mingeek.studiopop.ui.home

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Card
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.foundation.clickable
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun HomeScreen(
    onNavigateUpload: () -> Unit,
    onNavigateCaption: () -> Unit,
    onNavigateEditor: () -> Unit,
    onNavigateThumbnail: () -> Unit,
    onNavigateShorts: () -> Unit,
    onNavigateProjects: () -> Unit,
    onNavigateExports: () -> Unit,
) {
    Scaffold(
        topBar = { TopAppBar(title = { Text("YT Creator SuperApp") }) }
    ) { innerPadding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding)
                .verticalScroll(rememberScrollState())
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Text("원스톱", style = MaterialTheme.typography.titleMedium)
            MenuCard(
                title = "🎬 프로젝트",
                subtitle = "자막 → 편집 → 숏츠 → 썸네일 → 업로드 자동 연결",
                onClick = onNavigateProjects,
            )

            Text(
                "빠른 도구 (독립 실행)",
                style = MaterialTheme.typography.titleMedium,
            )
            MenuCard(
                title = "영상 편집 / 내보내기",
                subtitle = "트림 + 자막 번인 → MP4",
                onClick = onNavigateEditor,
            )
            MenuCard(
                title = "숏츠 만들기",
                subtitle = "9:16 크롭 + 60초 이내",
                onClick = onNavigateShorts,
            )
            MenuCard(
                title = "썸네일 만들기",
                subtitle = "프레임 + Claude 카피 → 1280×720 PNG",
                onClick = onNavigateThumbnail,
            )
            MenuCard(
                title = "자막 만들기",
                subtitle = "Whisper 자동 전사 → SRT",
                onClick = onNavigateCaption,
            )
            MenuCard(
                title = "YouTube 업로드",
                subtitle = "단일 영상 바로 올리기",
                onClick = onNavigateUpload,
            )

            Text("관리", style = MaterialTheme.typography.titleMedium)
            MenuCard(
                title = "📁 내보낸 파일",
                subtitle = "편집·숏츠·썸네일·자막 결과물 관리 (열기·공유·삭제)",
                onClick = onNavigateExports,
            )
        }
    }
}

@Composable
private fun MenuCard(title: String, subtitle: String, onClick: () -> Unit) {
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .clickable { onClick() }
    ) {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            Text(title, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
            Text(
                subtitle,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}
