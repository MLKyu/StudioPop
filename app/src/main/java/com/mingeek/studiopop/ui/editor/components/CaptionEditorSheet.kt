package com.mingeek.studiopop.ui.editor.components

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Slider
import androidx.compose.material3.Switch
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
import com.mingeek.studiopop.data.effects.builtins.CaptionStylePresets

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
    /**
     * R3.5: 자막 스타일 효과(8종)를 시트에서 직접 선택. null 이면 기본 스타일.
     * 효과 row 자체를 숨기려면 [showEffectPicker] = false. 텍스트 레이어 등 자막이 아닌
     * 항목엔 효과 시스템을 적용하지 않으므로 호출 측에서 false.
     */
    currentEffectId: String? = null,
    onEffectChange: (String?) -> Unit = {},
    showEffectPicker: Boolean = true,
    /**
     * R4.5: 비트 펄스 활성 상태 + 토글. 효과가 적용되지 않은 자막엔 비트 펄스가 의미 없으므로
     * [currentEffectId] == null 일 땐 disabled. 분석 미수행/실패 시에도 동작은 노이즈 — 분석
     * 결과가 빈 onsets 면 펄스가 발생하지 않을 뿐 토글 자체는 켤 수 있게 둔다.
     */
    beatSyncEnabled: Boolean = false,
    onBeatSyncChange: (Boolean) -> Unit = {},
    audioAnalyzing: Boolean = false,
    /**
     * R5c3a: 카라오케 모드(단어별 색 분기). 효과 적용된 자막에서만 활성. STT word 정보가
     * 없어도 시간비례 fake word timing 으로 동작.
     */
    karaokeEnabled: Boolean = false,
    onKaraokeChange: (Boolean) -> Unit = {},
    /**
     * R6: 강조어 폭탄자막 모드. 활성 시 큐 안 가장 긴 단어가 큰 글씨로 한 번 폭발(scale bounce
     * + jitter). STT word timing 이 있으면 그 단어 시간창에 맞춰 발사, 없으면 큐 시작 직후
     * 600ms. 효과 적용된 자막에서만 의미가 있어 효과 미선택 시 disabled.
     */
    bombEnabled: Boolean = false,
    onBombChange: (Boolean) -> Unit = {},
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
            modifier = Modifier
                .fillMaxWidth()
                .imePadding()                      // 키보드 높이만큼 여백 확보
                .navigationBarsPadding()           // 제스처 바 아래 안전 영역
                .verticalScroll(rememberScrollState()) // 내용이 키보드로 가려지면 스크롤로 도달
                .padding(16.dp),
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

            if (showEffectPicker) {
                Text(
                    "자막 효과 (R3.5)",
                    style = MaterialTheme.typography.labelLarge,
                )
                FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    FilterChip(
                        selected = currentEffectId == null,
                        onClick = { onEffectChange(null) },
                        label = { Text("효과 없음") },
                    )
                    CaptionStylePresets.DEFINITIONS.forEach { def ->
                        FilterChip(
                            selected = currentEffectId == def.id,
                            onClick = { onEffectChange(def.id) },
                            label = {
                                val emoji = def.previewHint.emoji
                                Text(if (emoji.isBlank()) def.displayName else "$emoji ${def.displayName}")
                            },
                        )
                    }
                }

                // R4.5: 비트 펄스 토글. 효과 미적용 자막엔 의미 없어 disabled.
                val beatToggleEnabled = currentEffectId != null
                Row(
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalAlignment = androidx.compose.ui.Alignment.CenterVertically,
                ) {
                    Switch(
                        checked = beatSyncEnabled,
                        onCheckedChange = onBeatSyncChange,
                        enabled = beatToggleEnabled,
                    )
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            "🥁 비트 펄스",
                            style = MaterialTheme.typography.labelLarge,
                        )
                        val subtitle = when {
                            !beatToggleEnabled -> "효과 선택 후 활성화 가능"
                            audioAnalyzing -> "오디오 비트 분석 중…"
                            else -> "음악 비트에 맞춰 자막 살짝 튐"
                        }
                        Text(
                            subtitle,
                            style = MaterialTheme.typography.bodySmall,
                        )
                    }
                }

                // R5c3a: 카라오케 토글
                Row(
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalAlignment = androidx.compose.ui.Alignment.CenterVertically,
                ) {
                    Switch(
                        checked = karaokeEnabled,
                        onCheckedChange = onKaraokeChange,
                        enabled = beatToggleEnabled,
                    )
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            "🎤 카라오케",
                            style = MaterialTheme.typography.labelLarge,
                        )
                        val sub = if (beatToggleEnabled) {
                            "단어별 색이 시간 따라 채워짐"
                        } else "효과 선택 후 활성화 가능"
                        Text(sub, style = MaterialTheme.typography.bodySmall)
                    }
                }

                // R6: 강조어 폭탄자막 토글
                Row(
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalAlignment = androidx.compose.ui.Alignment.CenterVertically,
                ) {
                    Switch(
                        checked = bombEnabled,
                        onCheckedChange = onBombChange,
                        enabled = beatToggleEnabled,
                    )
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            "💥 강조어 폭탄",
                            style = MaterialTheme.typography.labelLarge,
                        )
                        val sub = if (beatToggleEnabled) {
                            "큐의 가장 긴 단어가 한 번 크게 터짐"
                        } else "효과 선택 후 활성화 가능"
                        Text(sub, style = MaterialTheme.typography.bodySmall)
                    }
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
