package com.mingeek.studiopop.data.editor

import androidx.media3.common.Effect
import androidx.media3.common.util.UnstableApi
import com.mingeek.studiopop.data.effects.EffectInstance
import com.mingeek.studiopop.data.effects.EffectStack
import com.mingeek.studiopop.data.effects.builtins.KenBurnsHelper
import com.mingeek.studiopop.data.effects.builtins.VideoFxPresets
import com.mingeek.studiopop.data.effects.builtins.ZoomPunchHelper

/**
 * R6+: [EffectStack] 의 영상 효과(VIDEO_FX) 중 [CameraMatrixEffect] 로 표현되는 카메라 무브 효과
 * 들을 Media3 [Effect] 로 변환. 카메라 행렬 외 시간축 자체가 변하는 효과들은 별도 헬퍼:
 *  - [VideoFxPresets.KEN_BURNS] / [VideoFxPresets.ZOOM_PUNCH] → 여기서 처리
 *  - [VideoFxPresets.SPEED_RAMP] → [EffectStackSpeedRamp] (composition-level SpeedProvider)
 *  - SHORTS_PIECE → [EffectStackShortsOverlays] (TextureOverlay)
 *
 * 한 EffectInstance 의 source 범위가 여러 effective 세그먼트(컷 분할 결과)에 걸치면
 * [Timeline.rangeToOutputWindows] 결과만큼 별개 [CameraMatrixEffect] 가 만들어진다 — 각각의 출력
 * 시간창에서만 활성, 그 외엔 항등 행렬이라 안전히 체인에 들어감.
 */
@UnstableApi
object EffectStackVideoEffects {

    fun build(stack: EffectStack, timeline: Timeline): List<Effect> {
        if (stack.instances.isEmpty()) return emptyList()
        val out = mutableListOf<Effect>()
        for (inst in stack.instances) {
            if (!inst.enabled) continue
            when (inst.definitionId) {
                VideoFxPresets.KEN_BURNS -> out += buildKenBurns(inst, timeline)
                VideoFxPresets.ZOOM_PUNCH -> out += buildZoomPunch(inst, timeline)
                // SPEED_RAMP 는 EffectStackSpeedRamp 가, SHORTS_PIECE 는 EffectStackShortsOverlays
                // 가 별도 처리. 기타 카테고리는 카메라 행렬 효과가 아니라 여기선 skip.
                else -> Unit
            }
        }
        return out
    }

    private fun buildKenBurns(inst: EffectInstance, timeline: Timeline): List<Effect> {
        val direction = parseDirection(inst.params.choice("direction", VideoFxPresets.KenBurnsDirection.IN.name))
        val intensity = inst.params.float("intensity", 1f).coerceIn(0f, 1.5f)
        val windows = timeline.rangeToOutputWindows(inst.sourceStartMs, inst.sourceEndMs)
        return windows.map { w ->
            val outStart = w.first
            val outEnd = w.last
            val track = KenBurnsHelper.build(
                startMs = outStart,
                durationMs = (outEnd - outStart).coerceAtLeast(1L),
                direction = direction,
                intensity = intensity,
            )
            CameraMatrixEffect(track, outStart, outEnd)
        }
    }

    private fun buildZoomPunch(inst: EffectInstance, timeline: Timeline): List<Effect> {
        val intensity = inst.params.float("intensity", 1f).coerceIn(0f, 1.5f)
        val windows = timeline.rangeToOutputWindows(inst.sourceStartMs, inst.sourceEndMs)
        return windows.map { w ->
            val outStart = w.first
            val outEnd = w.last
            val span = (outEnd - outStart).coerceAtLeast(1L)
            val peak = outStart + span / 2
            val track = ZoomPunchHelper.build(
                peakTimeMs = peak,
                durationMs = span,
                intensity = intensity,
            )
            CameraMatrixEffect(track, outStart, outEnd)
        }
    }

    private fun parseDirection(raw: String): VideoFxPresets.KenBurnsDirection =
        runCatching { VideoFxPresets.KenBurnsDirection.valueOf(raw) }
            .getOrDefault(VideoFxPresets.KenBurnsDirection.IN)
}
