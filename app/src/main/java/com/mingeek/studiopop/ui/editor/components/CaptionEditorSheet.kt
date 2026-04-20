package com.mingeek.studiopop.ui.editor.components

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Slider
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.mingeek.studiopop.data.editor.CaptionPreset
import com.mingeek.studiopop.data.editor.CaptionStyle

/**
 * 자막 / 텍스트 레이어 공용 편집 시트.
 */
data class EditableTextItem(
    val id: String,
    val text: String,
    val sourceStartMs: Long,
    val sourceEndMs: Long,
    val style: CaptionStyle,
    val existsInTimeline: Boolean,
)

@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
fun CaptionEditorSheet(
    item: EditableTextItem,
    title: String = "자막 편집",
    onDismiss: () -> Unit,
    onSave: (EditableTextItem) -> Unit,
    onDelete: (() -> Unit)? = null,
) {
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    var text by remember(item.id) { mutableStateOf(item.text) }
    var startMs by remember(item.id) { mutableLongStateOf(item.sourceStartMs) }
    var endMs by remember(item.id) { mutableLongStateOf(item.sourceEndMs) }
    var preset by remember(item.id) { mutableStateOf(item.style.preset) }
    var anchorY by remember(item.id) { mutableFloatStateOf(item.style.anchorY) }
    var sizeScale by remember(item.id) { mutableFloatStateOf(item.style.sizeScale) }

    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = sheetState) {
        Column(
            modifier = Modifier.fillMaxWidth().padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Text(title, style = MaterialTheme.typography.titleMedium)

            OutlinedTextField(
                value = text,
                onValueChange = { text = it },
                label = { Text("텍스트") },
                minLines = 2,
                modifier = Modifier.fillMaxWidth(),
            )

            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedTextField(
                    value = startMs.toString(),
                    onValueChange = { v -> v.toLongOrNull()?.let { startMs = it } },
                    label = { Text("시작 (ms)") },
                    singleLine = true,
                    modifier = Modifier.weight(1f),
                )
                OutlinedTextField(
                    value = endMs.toString(),
                    onValueChange = { v -> v.toLongOrNull()?.let { endMs = it } },
                    label = { Text("끝 (ms)") },
                    singleLine = true,
                    modifier = Modifier.weight(1f),
                )
            }

            Text("스타일 프리셋", style = MaterialTheme.typography.labelLarge)
            FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                CaptionPreset.entries.forEach { p ->
                    FilterChip(
                        selected = p == preset,
                        onClick = { preset = p },
                        label = { Text(p.label) },
                    )
                }
            }

            Text("세로 위치 (${"%.2f".format(anchorY)})", style = MaterialTheme.typography.labelLarge)
            Slider(
                value = anchorY,
                onValueChange = { anchorY = it },
                valueRange = -1f..1f,
            )

            Text("크기 (${"%.2f".format(sizeScale)}x)", style = MaterialTheme.typography.labelLarge)
            Slider(
                value = sizeScale,
                onValueChange = { sizeScale = it },
                valueRange = 0.5f..3f,
            )

            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(
                    onClick = {
                        onSave(
                            item.copy(
                                text = text,
                                sourceStartMs = startMs,
                                sourceEndMs = endMs.coerceAtLeast(startMs + 100L),
                                style = CaptionStyle(
                                    preset = preset,
                                    anchorY = anchorY,
                                    sizeScale = sizeScale,
                                ),
                            )
                        )
                    },
                    modifier = Modifier.weight(1f),
                ) { Text("저장") }
                if (onDelete != null) {
                    OutlinedButton(
                        onClick = onDelete,
                        modifier = Modifier.weight(1f),
                    ) { Text("삭제") }
                }
            }
        }
    }
}
