package com.mingeek.studiopop.ui.editor.components

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Movie
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.mingeek.studiopop.data.project.AssetEntity
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * "내 편집본(앱 내부 export 영상) 라이브러리" 선택 시트.
 * 체크박스 다중 선택 → 확인 시 선택 순서대로 현재 타임라인에 append.
 *
 * [items] 는 최신순으로 정렬된 EXPORT_VIDEO asset 목록. 파일이 실제로 존재하지 않는 항목은 자동 숨김.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ExportedVideoPickerSheet(
    items: List<AssetEntity>,
    onConfirm: (List<AssetEntity>) -> Unit,
    onDismiss: () -> Unit,
) {
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    // 선택 "순서" 를 유지하기 위해 Set 대신 LinkedHashSet 기반 리스트.
    var selectedIds by remember { mutableStateOf<List<Long>>(emptyList()) }
    val existingItems = remember(items) { items.filter { File(it.value).exists() } }

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = sheetState,
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 8.dp)
                .heightIn(min = 200.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Text(
                "내 편집본 불러오기",
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.Bold,
            )
            Text(
                "선택한 순서대로 현재 타임라인 끝에 이어붙입니다.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )

            if (existingItems.isEmpty()) {
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(vertical = 24.dp),
                    contentAlignment = Alignment.Center,
                ) {
                    Text(
                        "저장된 편집본이 없습니다. 먼저 편집 후 내보내기를 해주세요.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            } else {
                LazyColumn(
                    modifier = Modifier.weight(1f, fill = false).heightIn(max = 420.dp),
                    verticalArrangement = Arrangement.spacedBy(6.dp),
                ) {
                    items(existingItems, key = { it.id }) { asset ->
                        val order = selectedIds.indexOf(asset.id)
                        val selected = order >= 0
                        ExportedVideoRow(
                            asset = asset,
                            orderNumber = if (selected) order + 1 else null,
                            onToggle = {
                                selectedIds = if (selected) {
                                    selectedIds - asset.id
                                } else {
                                    selectedIds + asset.id
                                }
                            },
                        )
                    }
                }
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    OutlinedButton(
                        onClick = onDismiss,
                        modifier = Modifier.weight(1f),
                    ) { Text("취소") }
                    OutlinedButton(
                        onClick = {
                            val ordered = selectedIds.mapNotNull { id ->
                                existingItems.firstOrNull { it.id == id }
                            }
                            onConfirm(ordered)
                        },
                        enabled = selectedIds.isNotEmpty(),
                        modifier = Modifier.weight(1f),
                    ) {
                        Text(if (selectedIds.isEmpty()) "선택" else "${selectedIds.size}개 이어붙이기")
                    }
                }
            }
        }
    }
}

@Composable
private fun ExportedVideoRow(
    asset: AssetEntity,
    orderNumber: Int?,
    onToggle: () -> Unit,
) {
    val selected = orderNumber != null
    val dateFormat = remember { SimpleDateFormat("yy.MM.dd HH:mm", Locale.getDefault()) }
    val fileName = remember(asset.value) { File(asset.value).name }
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(8.dp))
            .clickable { onToggle() }
            .padding(horizontal = 8.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Box(
            modifier = Modifier
                .size(36.dp)
                .clip(CircleShape)
                .background(
                    if (selected) MaterialTheme.colorScheme.primary
                    else MaterialTheme.colorScheme.surfaceVariant
                ),
            contentAlignment = Alignment.Center,
        ) {
            if (selected) {
                Text(
                    orderNumber.toString(),
                    color = Color.White,
                    style = MaterialTheme.typography.labelLarge,
                    fontWeight = FontWeight.Bold,
                )
            } else {
                Icon(
                    Icons.Filled.Movie,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
        Column(modifier = Modifier.weight(1f)) {
            Text(
                asset.label.ifBlank { fileName },
                style = MaterialTheme.typography.bodyMedium,
                fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Normal,
                maxLines = 1,
            )
            Text(
                dateFormat.format(Date(asset.createdAt)),
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        if (selected) {
            Icon(
                Icons.Filled.Check,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.primary,
            )
        }
    }
}
