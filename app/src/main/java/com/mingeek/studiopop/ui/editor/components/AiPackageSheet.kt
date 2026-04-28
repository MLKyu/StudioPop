package com.mingeek.studiopop.ui.editor.components

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.graphics.Bitmap
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AssistChip
import androidx.compose.material3.Card
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import com.mingeek.studiopop.data.ai.YoutubePackage

/**
 * R5c1: AI 패키지 생성 결과를 표시하는 시트. 사용자가 항목별로 클립보드 복사·"제안 적용" 등을
 * 할 수 있다. 모든 정보는 ViewModel state 에 보존되어 시트 재오픈 시 그대로 유지.
 *
 * 표시 영역:
 *  - 제목 5개 (각 칩 클릭 → 클립보드)
 *  - 챕터 목록 (timestamp + title)
 *  - 설명 본문 (전체 복사 버튼)
 *  - 태그/해시태그 (전체 복사 버튼)
 *  - 썸네일 변형 개수 안내 (실제 미리보기는 R5c2 에서 ThumbnailComposer 합성 후)
 *  - 숏츠 하이라이트 (timestamp + 이유)
 *  - "자막 제안 한 번에 적용" 버튼
 */
@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
fun AiPackageSheet(
    pkg: YoutubePackage,
    captionSuggestionCount: Int,
    effectSuggestionCount: Int,
    thumbnailBitmaps: Map<String, Bitmap>,
    onDismiss: () -> Unit,
    onApplyCaptionSuggestions: () -> Unit,
    onApplyEffectSuggestions: () -> Unit,
) {
    val context = LocalContext.current
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)

    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = sheetState) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .navigationBarsPadding()
                .verticalScroll(rememberScrollState())
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            Text("✨ AI 패키지", style = MaterialTheme.typography.titleLarge)

            // 제목
            SectionTitle("제목 후보 (탭 → 복사)")
            FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                pkg.titles.forEach { title ->
                    AssistChip(
                        onClick = { copyToClipboard(context, "title", title) },
                        label = { Text(title.take(80)) },
                    )
                }
                if (pkg.titles.isEmpty()) {
                    Text(
                        "(빈 응답)",
                        style = MaterialTheme.typography.bodySmall,
                    )
                }
            }

            // 챕터
            SectionTitle("챕터 (${pkg.chapters.size}개)")
            if (pkg.chapters.isEmpty()) {
                Text("(자막이 있어야 챕터가 생성됩니다)", style = MaterialTheme.typography.bodySmall)
            } else {
                Card(modifier = Modifier.fillMaxWidth()) {
                    Column(modifier = Modifier.padding(12.dp)) {
                        pkg.chapters.forEach { c ->
                            Text(
                                "${formatMs(c.startMs)}  ${c.title}",
                                style = MaterialTheme.typography.bodyMedium,
                            )
                        }
                    }
                }
                OutlinedButton(
                    onClick = {
                        copyToClipboard(
                            context,
                            "chapters",
                            pkg.chapters.joinToString("\n") { "${formatMs(it.startMs)} ${it.title}" },
                        )
                    },
                ) { Text("📋 챕터 전체 복사") }
            }

            // 설명
            SectionTitle("설명")
            Card(modifier = Modifier.fillMaxWidth()) {
                Text(
                    pkg.description.take(2000),
                    style = MaterialTheme.typography.bodyMedium,
                    modifier = Modifier.padding(12.dp),
                )
            }
            OutlinedButton(
                onClick = { copyToClipboard(context, "description", pkg.description) },
            ) { Text("📋 설명 복사") }

            // 태그
            SectionTitle("태그 (${pkg.tags.size}개)")
            FlowRow(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                pkg.tags.forEach { tag ->
                    AssistChip(
                        onClick = { copyToClipboard(context, "tag", tag) },
                        label = { Text(tag) },
                    )
                }
            }
            OutlinedButton(
                onClick = { copyToClipboard(context, "tags", pkg.tags.joinToString(", ")) },
            ) { Text("📋 태그 전체 복사") }

            // 해시태그
            if (pkg.hashtags.isNotEmpty()) {
                SectionTitle("해시태그")
                FlowRow(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                    pkg.hashtags.forEach { h ->
                        AssistChip(
                            onClick = { copyToClipboard(context, "hashtag", h) },
                            label = { Text(h) },
                        )
                    }
                }
            }

            // 썸네일 변형 — 합성 비트맵 직접 표시. 비트맵 맵에 없는 변형은 메타만.
            SectionTitle("썸네일 변형 (${pkg.thumbnailVariants.size}개)")
            pkg.thumbnailVariants.take(5).forEach { v ->
                Card(modifier = Modifier.fillMaxWidth()) {
                    Column(modifier = Modifier.padding(12.dp)) {
                        val bmp = thumbnailBitmaps[v.id]
                        if (bmp != null && !bmp.isRecycled) {
                            Image(
                                bitmap = bmp.asImageBitmap(),
                                contentDescription = v.mainText,
                                contentScale = ContentScale.Crop,
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .aspectRatio(16f / 9f)
                                    .padding(bottom = 8.dp),
                            )
                        } else {
                            Box(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .aspectRatio(16f / 9f)
                                    .padding(bottom = 8.dp),
                            ) {
                                Text(
                                    "(미리보기 합성 실패 — 메타만 표시)",
                                    style = MaterialTheme.typography.bodySmall,
                                )
                            }
                        }
                        Text(v.mainText, style = MaterialTheme.typography.titleSmall)
                        if (v.subText.isNotBlank()) {
                            Text(v.subText, style = MaterialTheme.typography.bodySmall)
                        }
                        Text(
                            "데코: ${v.decoration} · 위치: ${v.anchor}",
                            style = MaterialTheme.typography.bodySmall,
                        )
                    }
                }
            }

            // 숏츠 하이라이트
            if (pkg.shortsHighlights.isNotEmpty()) {
                SectionTitle("숏츠 하이라이트")
                pkg.shortsHighlights.forEach { h ->
                    Text(
                        "${formatMs(h.startMs)}~${formatMs(h.endMs)}  ${h.reason}",
                        style = MaterialTheme.typography.bodyMedium,
                    )
                }
            }

            // 자막/효과 제안 적용
            if (captionSuggestionCount > 0 || effectSuggestionCount > 0) {
                SectionTitle("AI 편집 제안")
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    if (captionSuggestionCount > 0) {
                        OutlinedButton(
                            onClick = onApplyCaptionSuggestions,
                            modifier = Modifier.weight(1f),
                        ) {
                            Text("➕ 자막 ${captionSuggestionCount}개 추가")
                        }
                    }
                    if (effectSuggestionCount > 0) {
                        OutlinedButton(
                            onClick = onApplyEffectSuggestions,
                            modifier = Modifier.weight(1f),
                        ) {
                            Text("✨ 효과 ${effectSuggestionCount}개 적용")
                        }
                    }
                }
                Text(
                    "효과 적용은 EffectStack 에 등록 — 실제 영상 렌더 통합은 다음 라운드.",
                    style = MaterialTheme.typography.bodySmall,
                )
            }
        }
    }
}

@Composable
private fun SectionTitle(text: String) {
    Text(text, style = MaterialTheme.typography.titleMedium)
}

private fun copyToClipboard(context: Context, label: String, value: String) {
    val cm = context.getSystemService(Context.CLIPBOARD_SERVICE) as? ClipboardManager ?: return
    cm.setPrimaryClip(ClipData.newPlainText(label, value))
}

private fun formatMs(ms: Long): String {
    val totalSec = ms / 1000
    val m = totalSec / 60
    val s = totalSec % 60
    return "%d:%02d".format(m, s)
}
