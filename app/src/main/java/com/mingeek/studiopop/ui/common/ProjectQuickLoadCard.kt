package com.mingeek.studiopop.ui.common

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp

/**
 * 프로젝트 내에 이전에 저장된 결과물 (편집본 영상 / 자막 SRT) 을 파일 선택 없이 한 번에
 * 불러올 수 있는 퀵 로드 카드.
 *
 * 앱이 export 를 `getExternalFilesDir` (앱 private 경로)에 저장하다 보니, 시스템 PhotoPicker
 * 나 DocumentPicker 에서는 보이지 않음. DB 의 asset 테이블엔 경로가 기록되어 있으므로 이를
 * 그대로 읽어 재사용할 수 있게 함.
 *
 * [latestExportVideoPath] / [latestSrtPath] 중 값이 있는 것만 버튼을 노출. 양쪽 다 null 이면
 * 카드 자체를 렌더하지 않음.
 */
@Composable
fun ProjectQuickLoadCard(
    latestExportVideoPath: String?,
    latestSrtPath: String?,
    onLoadVideo: (() -> Unit)?,
    onLoadSrt: (() -> Unit)?,
    modifier: Modifier = Modifier,
    title: String = "이 프로젝트 최근 결과",
) {
    val showVideo = latestExportVideoPath != null && onLoadVideo != null
    val showSrt = latestSrtPath != null && onLoadSrt != null
    if (!showVideo && !showSrt) return

    Card(
        modifier = modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.secondaryContainer,
        ),
    ) {
        Column(
            modifier = Modifier.padding(12.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            Text(title, style = MaterialTheme.typography.titleSmall)
            Text(
                "앱이 저장한 결과 파일은 갤러리·파일 선택기에 안 보일 수 있어요. 여기서 바로 불러오세요.",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                if (showVideo) {
                    OutlinedButton(
                        onClick = { onLoadVideo?.invoke() },
                        modifier = Modifier.weight(1f),
                    ) { Text("최신 편집본") }
                }
                if (showSrt) {
                    OutlinedButton(
                        onClick = { onLoadSrt?.invoke() },
                        modifier = Modifier.weight(1f),
                    ) { Text("최신 SRT") }
                }
            }
            val paths = buildList {
                latestExportVideoPath?.takeIf { showVideo }?.let { add("영상: ${it.substringAfterLast('/')}") }
                latestSrtPath?.takeIf { showSrt }?.let { add("SRT: ${it.substringAfterLast('/')}") }
            }
            if (paths.isNotEmpty()) {
                Text(
                    paths.joinToString(" · "),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}
