package com.mingeek.studiopop.ui.editor.components

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Slider
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.mingeek.studiopop.data.effects.EffectDefinition
import com.mingeek.studiopop.data.effects.EffectInstance
import com.mingeek.studiopop.data.effects.EffectParameter

/**
 * R6: 배치된 영상 효과 막대를 탭했을 때 뜨는 편집 시트. EffectDefinition 의 parameters 명세를
 * 보고 타입별로 적절한 컨트롤을 자동 렌더 — FloatRange/IntRange = Slider, Choice = FilterChip,
 * Toggle = Switch, Color/AssetSlot 은 현재 라운드 미지원(default 유지). 사용자가 "적용" 누르면
 * 변경된 params 일괄 [onApply] 로 흘려보냄. 시간 범위는 막대 핸들로만 조정 — 여기선 건드리지
 * 않음 (UI 책임 분리).
 */
@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
fun VideoFxEditSheet(
    instance: EffectInstance,
    definition: EffectDefinition?,
    onApply: (Map<String, Any>) -> Unit,
    onDelete: () -> Unit,
    onDismiss: () -> Unit,
) {
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    // 현재 params 를 시작값으로 — instance.params.values 의 Map<String,Any> 가 시드.
    var draft by remember(instance.id) { mutableStateOf(instance.params.values) }

    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = sheetState) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .verticalScroll(rememberScrollState())
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Text(
                "🎬 ${definition?.displayName ?: "영상 효과"}",
                style = MaterialTheme.typography.titleMedium,
            )
            Text(
                "시간 범위 ${instance.sourceStartMs}ms ~ ${instance.sourceEndMs}ms — 시간은 막대 핸들로 조정.",
                style = MaterialTheme.typography.bodySmall,
            )

            val parameters = definition?.parameters.orEmpty()
            if (parameters.isEmpty()) {
                Text(
                    "조정할 파라미터가 없습니다.",
                    style = MaterialTheme.typography.bodySmall,
                )
            } else {
                parameters.forEach { param ->
                    ParameterEditor(
                        parameter = param,
                        currentValue = draft[param.key],
                        onChange = { newValue ->
                            draft = draft.toMutableMap().apply { put(param.key, newValue) }
                        },
                    )
                }
            }

            Spacer(Modifier.width(8.dp))
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                OutlinedButton(
                    onClick = onDelete,
                    modifier = Modifier.weight(1f),
                ) { Text("🗑 삭제") }
                OutlinedButton(
                    onClick = onDismiss,
                    modifier = Modifier.weight(1f),
                ) { Text("닫기") }
                OutlinedButton(
                    onClick = {
                        onApply(draft)
                    },
                    modifier = Modifier.weight(1f),
                ) { Text("적용") }
            }
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun ParameterEditor(
    parameter: EffectParameter,
    currentValue: Any?,
    onChange: (Any) -> Unit,
) {
    when (parameter) {
        is EffectParameter.FloatRange -> {
            val seed = (currentValue as? Number)?.toFloat() ?: parameter.default
            Text(
                "${parameter.label}  ${"%.2f".format(seed)}",
                style = MaterialTheme.typography.labelMedium,
            )
            Slider(
                value = seed,
                onValueChange = { onChange(it) },
                valueRange = parameter.min..parameter.max,
            )
        }
        is EffectParameter.IntRange -> {
            val seed = (currentValue as? Number)?.toInt() ?: parameter.default
            Text(
                "${parameter.label}  $seed",
                style = MaterialTheme.typography.labelMedium,
            )
            Slider(
                value = seed.toFloat(),
                onValueChange = { onChange(it.toInt()) },
                valueRange = parameter.min.toFloat()..parameter.max.toFloat(),
                steps = (parameter.max - parameter.min - 1).coerceAtLeast(0),
            )
        }
        is EffectParameter.Toggle -> {
            val seed = (currentValue as? Boolean) ?: parameter.default
            Row(verticalAlignment = androidx.compose.ui.Alignment.CenterVertically) {
                Text(parameter.label, modifier = Modifier.weight(1f))
                Switch(
                    checked = seed,
                    onCheckedChange = { onChange(it) },
                )
            }
        }
        is EffectParameter.Choice -> {
            val seed = (currentValue as? String) ?: parameter.defaultId
            Text(parameter.label, style = MaterialTheme.typography.labelMedium)
            FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                parameter.options.forEach { opt ->
                    FilterChip(
                        selected = seed == opt.id,
                        onClick = { onChange(opt.id) },
                        label = { Text(opt.label) },
                    )
                }
            }
        }
        is EffectParameter.Color, is EffectParameter.AssetSlot -> {
            // 현재 라운드 미지원 — Color picker / asset picker 는 별도 UI 컴포넌트 필요.
            // default 그대로 두면 EffectStack 해석부에서 default 를 적용함.
            Text(
                "${parameter.label} (현재 라운드 미지원 — 기본값 유지)",
                style = MaterialTheme.typography.bodySmall,
            )
        }
    }
}
