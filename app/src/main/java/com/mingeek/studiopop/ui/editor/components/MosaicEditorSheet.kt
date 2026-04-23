package com.mingeek.studiopop.ui.editor.components

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material3.Card
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.mingeek.studiopop.data.editor.MosaicMode
import com.mingeek.studiopop.data.editor.MosaicRegion

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MosaicEditorSheet(
    regions: List<MosaicRegion>,
    isDetecting: Boolean,
    onAddManual: () -> Unit,
    onAddAutoFace: () -> Unit,
    onDelete: (String) -> Unit,
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
                .padding(horizontal = 16.dp, vertical = 8.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            Text(
                "모자이크",
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.Bold,
            )
            Text(
                "수동: 프리뷰에서 사각형을 드래그해 위치·크기 조정\n" +
                "자동: 현재 시점부터 10초 구간에서 얼굴을 찾아 자동 추적",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedButton(
                    onClick = onAddManual,
                    modifier = Modifier.weight(1f),
                    enabled = !isDetecting,
                ) { Text("수동 박스 추가") }
                OutlinedButton(
                    onClick = onAddAutoFace,
                    modifier = Modifier.weight(1f),
                    enabled = !isDetecting,
                ) { Text("얼굴 자동 감지") }
            }
            if (isDetecting) {
                LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
                Text(
                    "얼굴 찾는 중...",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }

            if (regions.isNotEmpty()) {
                Text(
                    "현재 모자이크 영역 ${regions.size}",
                    style = MaterialTheme.typography.labelLarge,
                )
                regions.forEach { region ->
                    Card(modifier = Modifier.fillMaxWidth()) {
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(horizontal = 12.dp, vertical = 8.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Column(modifier = Modifier.weight(1f)) {
                                Text(
                                    if (region.mode == MosaicMode.AUTO_FACE) "얼굴 자동" else "수동 박스",
                                    style = MaterialTheme.typography.bodyMedium,
                                )
                                Text(
                                    "${region.sourceStartMs / 1000.0}s ~ ${region.sourceEndMs / 1000.0}s · " +
                                            "키프레임 ${region.keyframes.size}",
                                    style = MaterialTheme.typography.labelSmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                            }
                            IconButton(onClick = { onDelete(region.id) }) {
                                Icon(Icons.Filled.Delete, contentDescription = "삭제")
                            }
                        }
                    }
                }
            }
        }
    }
}
