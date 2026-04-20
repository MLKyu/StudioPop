package com.mingeek.studiopop.ui.editor.components

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.mingeek.studiopop.data.editor.TimelineCaption

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun CaptionEditorSheet(
    caption: TimelineCaption,
    onDismiss: () -> Unit,
    onSave: (TimelineCaption) -> Unit,
    onDelete: (() -> Unit)? = null,
) {
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    var text by remember(caption.id) { mutableStateOf(caption.text) }
    var startMs by remember(caption.id) { mutableLongStateOf(caption.sourceStartMs) }
    var endMs by remember(caption.id) { mutableLongStateOf(caption.sourceEndMs) }

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = sheetState,
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Text("자막 편집")

            OutlinedTextField(
                value = text,
                onValueChange = { text = it },
                label = { Text("자막 텍스트") },
                minLines = 2,
                modifier = Modifier.fillMaxWidth(),
            )

            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedTextField(
                    value = startMs.toString(),
                    onValueChange = { v -> v.toLongOrNull()?.let { startMs = it } },
                    label = { Text("시작 (ms, 원본 기준)") },
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

            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(
                    onClick = {
                        onSave(
                            caption.copy(
                                text = text,
                                sourceStartMs = startMs,
                                sourceEndMs = endMs.coerceAtLeast(startMs + 100L),
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
