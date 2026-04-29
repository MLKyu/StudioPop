package com.mingeek.studiopop.data.editor

import androidx.media3.common.C
import androidx.media3.common.util.UnstableApi
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

@UnstableApi
class EffectStackSpeedRampTest {

    @Test
    fun `empty stack returns null provider`() {
        val provider = EffectStackSpeedRamp.build(
            stack = com.mingeek.studiopop.data.effects.EffectStack.EMPTY,
            timeline = Timeline(segments = emptyList()),
        )
        assertNull(provider)
    }

    @Test
    fun `stack without SPEED_RAMP returns null`() {
        val stack = com.mingeek.studiopop.data.effects.EffectStack(
            instances = listOf(
                com.mingeek.studiopop.data.effects.EffectInstance(
                    definitionId = "video_fx.ken_burns",
                    sourceStartMs = 0L,
                    sourceEndMs = 1_000L,
                ),
            ),
        )
        val provider = EffectStackSpeedRamp.build(
            stack = stack,
            timeline = Timeline(segments = emptyList()),
        )
        assertNull(provider)
    }

    // SpeedRampProvider 단위 테스트 — Timeline 비의존 (RampWindow 직접 주입).

    private fun provider(vararg ramps: EffectStackSpeedRamp.RampWindow) =
        EffectStackSpeedRamp.SpeedRampProvider(ramps.toList())

    @Test
    fun `outside ramp returns 1_0`() {
        val p = provider(
            EffectStackSpeedRamp.RampWindow(
                startUs = 1_000_000L, endUs = 3_000_000L, minSpeed = 0.4f,
            ),
        )
        assertEquals(1.0f, p.getSpeed(0L), 0.001f)
        assertEquals(1.0f, p.getSpeed(500_000L), 0.001f)
        assertEquals(1.0f, p.getSpeed(5_000_000L), 0.001f)
    }

    @Test
    fun `at ramp start returns minSpeed and at ramp end returns 1_0`() {
        val p = provider(
            EffectStackSpeedRamp.RampWindow(
                startUs = 1_000_000L, endUs = 3_000_000L, minSpeed = 0.4f,
            ),
        )
        assertEquals(0.4f, p.getSpeed(1_000_000L), 0.001f)
        // endUs 는 exclusive — 1.0 으로 복귀(post-loop default)
        assertEquals(1.0f, p.getSpeed(3_000_000L), 0.001f)
        // 중간점 t=0.5 → 0.4 + 0.6*0.5 = 0.7
        assertEquals(0.7f, p.getSpeed(2_000_000L), 0.001f)
    }

    @Test
    fun `touching ramps no snap at boundary`() {
        // P0 fix: 두 ramp 가 맞닿을 때 boundary 시각에 prev 의 1.0 으로 snap 되지 않고 next 의 head
        // (minSpeed) 로 곧장 이어져야 함 — getSpeed/getNextSpeedChangeTimeUs 가 [start, end) 일치.
        val p = provider(
            EffectStackSpeedRamp.RampWindow(
                startUs = 1_000_000L, endUs = 2_000_000L, minSpeed = 0.4f,
            ),
            EffectStackSpeedRamp.RampWindow(
                startUs = 2_000_000L, endUs = 3_000_000L, minSpeed = 0.5f,
            ),
        )
        // boundary 직전: prev 끝 t≈1 (== prev.maxSpeed default 1.0)
        assertEquals(1.0f, p.getSpeed(1_999_999L), 0.001f)
        // boundary 시각: next 의 minSpeed (1.0 snap 아님)
        assertEquals(0.5f, p.getSpeed(2_000_000L), 0.001f)
    }

    @Test
    fun `getNextSpeedChangeTimeUs returns ramp start when before all ramps`() {
        val p = provider(
            EffectStackSpeedRamp.RampWindow(
                startUs = 1_000_000L, endUs = 3_000_000L, minSpeed = 0.4f,
            ),
        )
        assertEquals(1_000_000L, p.getNextSpeedChangeTimeUs(0L))
    }

    @Test
    fun `getNextSpeedChangeTimeUs returns next step boundary inside ramp`() {
        val p = provider(
            EffectStackSpeedRamp.RampWindow(
                startUs = 1_000_000L, endUs = 3_000_000L, minSpeed = 0.4f,
            ),
        )
        // 1_000_000 (start) 직후엔 다음 step 50_000Us 후 → 1_050_000
        assertEquals(1_050_000L, p.getNextSpeedChangeTimeUs(1_000_000L))
        // 1_075_000 → 다음 step 1_100_000 (rel=75000, /STEP=1, +1, *STEP=100000)
        assertEquals(1_100_000L, p.getNextSpeedChangeTimeUs(1_075_000L))
    }

    @Test
    fun `getNextSpeedChangeTimeUs returns ramp end at last step boundary`() {
        val p = provider(
            EffectStackSpeedRamp.RampWindow(
                startUs = 1_000_000L, endUs = 1_100_000L, minSpeed = 0.4f,
            ),
        )
        // 1_080_000 → 다음 step 1_100_000 = ramp endUs (caller 가 ramp 끝 후 1.0 으로 복귀)
        assertEquals(1_100_000L, p.getNextSpeedChangeTimeUs(1_080_000L))
    }

    @Test
    fun `getNextSpeedChangeTimeUs at ramp end returns TIME_UNSET when no more ramps`() {
        // P0 fix: 계약은 반환값이 strictly > timeUs 거나 TIME_UNSET. timeUs == endUs 시
        // 자기 자신 반환하면 Media3 가 멈출 수 있음.
        val p = provider(
            EffectStackSpeedRamp.RampWindow(
                startUs = 1_000_000L, endUs = 1_100_000L, minSpeed = 0.4f,
            ),
        )
        assertEquals(C.TIME_UNSET, p.getNextSpeedChangeTimeUs(1_100_000L))
    }

    @Test
    fun `getNextSpeedChangeTimeUs at ramp end returns next ramp start`() {
        val p = provider(
            EffectStackSpeedRamp.RampWindow(
                startUs = 1_000_000L, endUs = 1_100_000L, minSpeed = 0.4f,
            ),
            EffectStackSpeedRamp.RampWindow(
                startUs = 5_000_000L, endUs = 6_000_000L, minSpeed = 0.2f,
            ),
        )
        // ramp1 끝 시점 → 다음은 ramp2 시작 (자기 자신 1_100_000 반환 금지)
        assertEquals(5_000_000L, p.getNextSpeedChangeTimeUs(1_100_000L))
    }

    @Test
    fun `getNextSpeedChangeTimeUs returns TIME_UNSET after all ramps`() {
        val p = provider(
            EffectStackSpeedRamp.RampWindow(
                startUs = 1_000_000L, endUs = 3_000_000L, minSpeed = 0.4f,
            ),
        )
        assertEquals(C.TIME_UNSET, p.getNextSpeedChangeTimeUs(5_000_000L))
    }

    @Test
    fun `multiple ramps return correct per-window speeds`() {
        val p = provider(
            EffectStackSpeedRamp.RampWindow(
                startUs = 1_000_000L, endUs = 2_000_000L, minSpeed = 0.5f,
            ),
            EffectStackSpeedRamp.RampWindow(
                startUs = 5_000_000L, endUs = 6_000_000L, minSpeed = 0.2f,
            ),
        )
        assertEquals(0.5f, p.getSpeed(1_000_000L), 0.001f)  // ramp 1 start
        assertEquals(1.0f, p.getSpeed(3_000_000L), 0.001f)  // 사이 빈 구간
        assertEquals(0.2f, p.getSpeed(5_000_000L), 0.001f)  // ramp 2 start
        assertEquals(1.0f, p.getSpeed(7_000_000L), 0.001f)  // 모든 ramp 뒤
    }
}
