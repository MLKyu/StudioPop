package com.mingeek.studiopop.ui.editor.components

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.mingeek.studiopop.data.design.BuiltinLuts
import com.mingeek.studiopop.data.design.DesignTokens
import com.mingeek.studiopop.data.effects.EffectStack
import com.mingeek.studiopop.data.effects.builtins.CameraTransform
import com.mingeek.studiopop.data.effects.builtins.IntroOutroPresets
import com.mingeek.studiopop.data.effects.builtins.KenBurnsHelper
import com.mingeek.studiopop.data.effects.builtins.VideoFxPresets
import com.mingeek.studiopop.data.effects.builtins.ZoomPunchHelper
import com.mingeek.studiopop.data.keyframe.Vec2

/**
 * 프리뷰에서 영상 효과 동작을 사용자에게 노출.
 *
 * - **Ken Burns / Zoom Punch**: [sampleCameraTransform] 가 카메라 변환을 계산 → 호출 측이
 *   PlayerView 박스에 graphicsLayer 로 적용해 시각적 줌·팬을 그대로 미리 봄.
 * - **Speed Ramp / LUT / 인트로·아웃트로**: Media3 GL 파이프라인이 export 시점에 합성하므로
 *   프리뷰에선 텍스트 배지로 "지금 활성" 임을 알려준다 — 사용자는 효과가 "동작 안 함" 으로 오해
 *   하지 않고, export 결과에 어떤 효과가 들어갈지 한눈에 인지 가능.
 */

/**
 * 현재 source ms 에 활성인 카메라 변환 효과(Ken Burns / Zoom Punch)를 합성. 두 개 이상이 동시
 * 활성이면 마지막 인스턴스가 우선 — 일반적으로 사용자는 한 시점에 한 카메라 무브만 두는 게 자연스럽
 * 고, 충돌 시 최신 의도 반영.
 *
 * 반환 null = 적용할 효과 없음 (호출 측은 항등 변환으로 처리).
 */
fun sampleCameraTransform(stack: EffectStack, sourceMs: Long): CameraTransform? {
    if (stack.instances.isEmpty()) return null
    var result: CameraTransform? = null
    for (inst in stack.instances) {
        if (!inst.enabled) continue
        if (sourceMs !in inst.sourceStartMs..inst.sourceEndMs) continue
        val span = (inst.sourceEndMs - inst.sourceStartMs).coerceAtLeast(1L)
        when (inst.definitionId) {
            VideoFxPresets.KEN_BURNS -> {
                val direction = runCatching {
                    VideoFxPresets.KenBurnsDirection.valueOf(
                        inst.params.choice("direction", VideoFxPresets.KenBurnsDirection.IN.name)
                    )
                }.getOrDefault(VideoFxPresets.KenBurnsDirection.IN)
                val intensity = inst.params.float("intensity", 1f).coerceIn(0f, 1.5f)
                val track = KenBurnsHelper.build(
                    startMs = inst.sourceStartMs,
                    durationMs = span,
                    direction = direction,
                    intensity = intensity,
                )
                result = track.sampleAt(sourceMs)
            }
            VideoFxPresets.ZOOM_PUNCH -> {
                val intensity = inst.params.float("intensity", 1f).coerceIn(0f, 1.5f)
                val peak = inst.sourceStartMs + span / 2
                val track = ZoomPunchHelper.build(
                    peakTimeMs = peak,
                    durationMs = span,
                    intensity = intensity,
                )
                result = track.sampleAt(sourceMs)
            }
            else -> Unit
        }
    }
    return result
}

/**
 * 현재 source ms 시점에 활성인 영상 효과 배지를 PlayerView 위에 겹쳐 그린다. 좌하단 정렬로
 * 자막/짤과 충돌 적게.
 */
@Composable
fun PreviewVideoFxBadges(
    effectStack: EffectStack,
    currentSourceMs: Long,
    designTokens: DesignTokens,
    selectedThemeId: String?,
    modifier: Modifier = Modifier,
) {
    val labels = remember(effectStack, currentSourceMs, selectedThemeId) {
        buildList {
            // VIDEO_FX 카테고리 (export 시 GL 합성)
            effectStack.instances.forEach { inst ->
                if (!inst.enabled) return@forEach
                val active = currentSourceMs in inst.sourceStartMs..inst.sourceEndMs
                if (!active) return@forEach
                when (inst.definitionId) {
                    VideoFxPresets.SPEED_RAMP -> add(
                        Badge("🌀", "Speed Ramp", "export 시 슬로우→정상")
                    )
                    IntroOutroPresets.HOOK_TITLE -> add(
                        Badge("🎬", "후킹 타이틀", "export 시 텍스트 합성")
                    )
                    IntroOutroPresets.SIMPLE_TITLE -> add(
                        Badge("📝", "심플 타이틀", "export 시 텍스트 합성")
                    )
                    IntroOutroPresets.COUNTDOWN_3 -> add(
                        Badge("⏱️", "카운트다운", "export 시 3·2·1 합성")
                    )
                    IntroOutroPresets.SUBSCRIBE_PROMPT -> add(
                        Badge("🔔", "구독 유도", "export 시 텍스트 합성")
                    )
                    IntroOutroPresets.NEXT_EPISODE_CARD -> add(
                        Badge("➡️", "다음 영상 카드", "export 시 텍스트 합성")
                    )
                }
            }
            // LUT — selectedTheme 의 lutId 가 영상 전체에 항상 적용. theme 자체는 시간축 없음
            // 이라 전 시점에 동일 라벨 노출.
            val lutId = selectedThemeId?.let { designTokens.theme(it).lutId }
            val lut = lutId?.let { designTokens.lut(it) ?: BuiltinLuts.ALL.firstOrNull { l -> l.id == it } }
            if (lut != null) {
                add(Badge("🎨", "LUT: ${lut.displayName}", "export 시 색감 합성"))
            }
        }
    }
    if (labels.isEmpty()) return
    androidx.compose.foundation.layout.Box(modifier = modifier.fillMaxSize()) {
        Column(
            modifier = Modifier
                .align(Alignment.BottomStart)
                .padding(8.dp),
            verticalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            labels.forEach { b ->
                Text(
                    text = "${b.emoji} ${b.title} · ${b.note}",
                    color = Color.White,
                    fontSize = 11.sp,
                    modifier = Modifier
                        .clip(RoundedCornerShape(4.dp))
                        .background(Color.Black.copy(alpha = 0.55f))
                        .padding(horizontal = 6.dp, vertical = 3.dp),
                )
            }
        }
    }
}

private data class Badge(val emoji: String, val title: String, val note: String)

/**
 * 항등 변환 — 활성 카메라 효과가 없을 때 사용. graphicsLayer 호출 측이 null check 대신 일관된
 * 변환 객체를 받도록.
 */
val IdentityCameraTransform = CameraTransform(Vec2(0f, 0f), 1f)
