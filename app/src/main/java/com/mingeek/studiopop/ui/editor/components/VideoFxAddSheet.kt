package com.mingeek.studiopop.ui.editor.components

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
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
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.mingeek.studiopop.data.design.BuiltinLuts
import com.mingeek.studiopop.data.effects.builtins.IntroOutroPresets
import com.mingeek.studiopop.data.effects.builtins.VideoFxPresets

/**
 * R6: 영상 효과 추가 시트. 사용자가 현재 플레이헤드 위치에서 default duration 동안 적용할
 * 효과를 한 탭으로 선택. AI 추천 흐름을 통하지 않고 직접 추가하고 싶을 때.
 *
 * 노출 항목:
 *  - VIDEO_FX: Ken Burns(방향 5종) / Zoom Punch / Speed Ramp(slow → normal)
 *  - LUT: 5종 합성 (Cinematic / Vivid / Mono / Vintage / Cool)
 *  - SHORTS_PIECE: 인트로/아웃트로 텍스트 5종 (default 한국어 카피, 막대 드래그로 위치 조정)
 */
@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
fun VideoFxAddSheet(
    /** Ken Burns / Zoom Punch — 플레이헤드 위치 기준 default duration. */
    onAddFxAtPlayhead: (definitionId: String, params: Map<String, Any>) -> Unit,
    /** LUT — 영상 전체에 적용. */
    onAddLutFull: (lutId: String) -> Unit,
    onDismiss: () -> Unit,
) {
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    var selectedDirection by remember {
        mutableStateOf(VideoFxPresets.KenBurnsDirection.IN)
    }

    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = sheetState) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .verticalScroll(rememberScrollState())
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Text("🎬 영상 효과 추가", style = MaterialTheme.typography.titleMedium)
            Text(
                "현재 플레이헤드 위치에서 2초 동안 적용. 추가 후 타임라인 막대 핸들로 시간 조정 가능.",
                style = MaterialTheme.typography.bodySmall,
            )

            Text("Ken Burns 방향", style = MaterialTheme.typography.labelLarge)
            FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                VideoFxPresets.KenBurnsDirection.entries.forEach { dir ->
                    FilterChip(
                        selected = selectedDirection == dir,
                        onClick = { selectedDirection = dir },
                        label = { Text(directionLabel(dir)) },
                    )
                }
            }
            OutlinedButton(
                onClick = {
                    onAddFxAtPlayhead(
                        VideoFxPresets.KEN_BURNS,
                        mapOf("direction" to selectedDirection.name, "intensity" to 1f),
                    )
                },
                modifier = Modifier.fillMaxWidth(),
            ) { Text("🎥 Ken Burns 추가") }

            OutlinedButton(
                onClick = {
                    onAddFxAtPlayhead(
                        VideoFxPresets.ZOOM_PUNCH,
                        mapOf("intensity" to 1f),
                    )
                },
                modifier = Modifier.fillMaxWidth(),
            ) { Text("💥 Zoom Punch 추가") }

            OutlinedButton(
                onClick = {
                    onAddFxAtPlayhead(
                        VideoFxPresets.SPEED_RAMP,
                        // 슬로우 모션 (40% 속도) → 정상 속도. 막대 늘리면 ramp duration 길어짐.
                        mapOf("minSpeed" to 0.4f, "maxSpeed" to 1.0f),
                    )
                },
                modifier = Modifier.fillMaxWidth(),
            ) { Text("🌀 Speed Ramp (슬로우→정상) 추가") }

            Text("LUT (영상 전체 색감)", style = MaterialTheme.typography.labelLarge)
            FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                BuiltinLuts.ALL.forEach { lut ->
                    FilterChip(
                        selected = false,
                        onClick = { onAddLutFull(lut.id) },
                        label = { Text("🎨 ${lut.displayName}") },
                    )
                }
            }

            // 인트로/아웃트로 텍스트 — 플레이헤드 위치에 default 카피로 추가. 막대 드래그로 영상
            // 시작/끝으로 옮겨 사용. 텍스트는 EffectStackShortsOverlays 가 default 한국어로 채움.
            Text("인트로/아웃트로 텍스트", style = MaterialTheme.typography.labelLarge)
            FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                ShortsPieceEntry.entries.forEach { entry ->
                    FilterChip(
                        selected = false,
                        onClick = {
                            onAddFxAtPlayhead(entry.definitionId, emptyMap())
                        },
                        label = { Text("${entry.emoji} ${entry.label}") },
                    )
                }
            }
        }
    }
}

/**
 * 인트로/아웃트로 sheet 표시용 enum — IntroOutroPresets 의 displayName 보다 짧게 줄임 (sheet
 * chip 너비 제한). COUNTDOWN_3 은 텍스트 cue fallback 으로 export 됨 (애니메이션 비주얼은 별개).
 */
private enum class ShortsPieceEntry(
    val definitionId: String,
    val emoji: String,
    val label: String,
) {
    HOOK(IntroOutroPresets.HOOK_TITLE, "🎬", "후킹 타이틀"),
    SIMPLE(IntroOutroPresets.SIMPLE_TITLE, "📝", "심플 타이틀"),
    COUNTDOWN(IntroOutroPresets.COUNTDOWN_3, "⏱️", "3·2·1 카운트다운"),
    SUBSCRIBE(IntroOutroPresets.SUBSCRIBE_PROMPT, "🔔", "구독 유도"),
    NEXT_EPISODE(IntroOutroPresets.NEXT_EPISODE_CARD, "➡️", "다음 영상 카드"),
}

private fun directionLabel(d: VideoFxPresets.KenBurnsDirection): String = when (d) {
    VideoFxPresets.KenBurnsDirection.IN -> "줌 인"
    VideoFxPresets.KenBurnsDirection.OUT -> "줌 아웃"
    VideoFxPresets.KenBurnsDirection.PAN_LEFT -> "좌→우"
    VideoFxPresets.KenBurnsDirection.PAN_RIGHT -> "우→좌"
    VideoFxPresets.KenBurnsDirection.DIAGONAL -> "대각"
}
