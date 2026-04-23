package com.mingeek.studiopop.ui.editor.components

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material3.Card
import androidx.compose.material3.Checkbox
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.mingeek.studiopop.data.editor.FixedTextTemplate
import com.mingeek.studiopop.data.editor.TemplateAnchor
import com.mingeek.studiopop.data.editor.Timeline

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun FixedTemplateEditorSheet(
    timeline: Timeline,
    onAdd: (TemplateAnchor, defaultText: String) -> Unit,
    onUpdateDefault: (id: String, String) -> Unit,
    onUpdateOverride: (id: String, segmentId: String, text: String) -> Unit,
    onToggle: (id: String) -> Unit,
    onDelete: (id: String) -> Unit,
    onDismiss: () -> Unit,
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
                .padding(horizontal = 16.dp, vertical = 8.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            Text(
                "고정 텍스트 템플릿",
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.Bold,
            )
            Text(
                "로고/채널핸들/워터마크처럼 위치·스타일은 고정하고 텍스트만 세그먼트별로 다르게 넣을 수 있어요.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )

            Text("위치 선택해 템플릿 추가", style = MaterialTheme.typography.labelLarge)
            Row(
                horizontalArrangement = Arrangement.spacedBy(6.dp),
                modifier = Modifier.fillMaxWidth(),
            ) {
                val anchors = listOf(
                    TemplateAnchor.TOP_LEFT to "좌상단",
                    TemplateAnchor.TOP_RIGHT to "우상단",
                    TemplateAnchor.BOTTOM_LEFT to "좌하단",
                    TemplateAnchor.BOTTOM_RIGHT to "우하단",
                )
                anchors.forEach { (anchor, label) ->
                    FilterChip(
                        selected = false,
                        onClick = { onAdd(anchor, "@channel") },
                        label = { Text(label) },
                    )
                }
            }

            if (timeline.fixedTemplates.isNotEmpty()) {
                Text(
                    "적용된 템플릿 ${timeline.fixedTemplates.size}",
                    style = MaterialTheme.typography.labelLarge,
                )
                timeline.fixedTemplates.forEach { template ->
                    TemplateCard(
                        template = template,
                        timeline = timeline,
                        onUpdateDefault = { onUpdateDefault(template.id, it) },
                        onUpdateOverride = { segId, text -> onUpdateOverride(template.id, segId, text) },
                        onToggle = { onToggle(template.id) },
                        onDelete = { onDelete(template.id) },
                    )
                }
            }
        }
    }
}

@Composable
private fun TemplateCard(
    template: FixedTextTemplate,
    timeline: Timeline,
    onUpdateDefault: (String) -> Unit,
    onUpdateOverride: (segmentId: String, text: String) -> Unit,
    onToggle: () -> Unit,
    onDelete: () -> Unit,
) {
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(
            modifier = Modifier.padding(12.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Checkbox(
                    checked = template.enabled,
                    onCheckedChange = { onToggle() },
                )
                Text(
                    "${template.label} 템플릿",
                    style = MaterialTheme.typography.bodyMedium,
                    modifier = Modifier.weight(1f),
                )
                IconButton(onClick = onDelete) {
                    Icon(Icons.Filled.Delete, contentDescription = "삭제")
                }
            }
            OutlinedTextField(
                value = template.defaultText,
                onValueChange = onUpdateDefault,
                label = { Text("기본 텍스트 (모든 세그먼트)") },
                singleLine = true,
                modifier = Modifier.fillMaxWidth(),
            )

            if (timeline.segments.size > 1) {
                Text(
                    "세그먼트별 오버라이드 (비워두면 기본값 사용)",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                timeline.segments.forEachIndexed { idx, seg ->
                    val override = template.perSegmentText[seg.id] ?: ""
                    OutlinedTextField(
                        value = override,
                        onValueChange = { onUpdateOverride(seg.id, it) },
                        label = { Text("세그먼트 ${idx + 1}") },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth(),
                    )
                }
            }
        }
    }
}
