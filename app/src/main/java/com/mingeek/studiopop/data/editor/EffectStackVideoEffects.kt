package com.mingeek.studiopop.data.editor

import androidx.media3.common.Effect
import androidx.media3.common.util.UnstableApi
import com.mingeek.studiopop.data.effects.EffectInstance
import com.mingeek.studiopop.data.effects.EffectStack
import com.mingeek.studiopop.data.effects.builtins.KenBurnsHelper
import com.mingeek.studiopop.data.effects.builtins.VideoFxPresets
import com.mingeek.studiopop.data.effects.builtins.ZoomPunchHelper

/**
 * R6: [EffectStack] 의 영상 효과(VIDEO_FX) EffectInstance → Media3 [Effect] 변환.
 *
 * 1차 지원:
 *  - [VideoFxPresets.KEN_BURNS] → 시간 가변 카메라 줌·팬 ([CameraMatrixEffect])
 *  - [VideoFxPresets.ZOOM_PUNCH] → 짧은 스파이크 줌
 *
 * 후속 라운드:
 *  - [VideoFxPresets.SPEED_RAMP] — Media3 의 PTS 재작성/오디오 리샘플 인프라 통합 필요
 *  - SHORTS_PIECE 정형 인트로/아웃트로 — 텍스트 자산 모듈 확정 후
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
                // SPEED_RAMP / SHORTS_PIECE / 기타: 미지원 — 이번 라운드 스코프 밖.
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
