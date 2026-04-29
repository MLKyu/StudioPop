package com.mingeek.studiopop.data.editor

import androidx.media3.common.util.UnstableApi
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * COUNTDOWN_3 의 cue 분할 로직만 단위 검증. Timeline 통합(rangeToOutputWindows 매핑) 은
 * Timeline 자체 테스트가 책임 — 여기선 splitCountdownWindow 의 산수만 본다.
 */
@UnstableApi
class EffectStackShortsOverlaysTest {

    @Test
    fun `countdown 3000ms splits into 3 equal animated segments with text 3 2 1`() {
        val segs = EffectStackShortsOverlays.splitCountdownWindow(0L, 3_000L)
        assertEquals(3, segs.size)
        assertEquals(listOf("3", "2", "1"), segs.map { it.text })
        assertEquals(0L, segs[0].startMs)
        assertEquals(1_000L, segs[0].endMs)
        assertEquals(1_000L, segs[1].startMs)
        assertEquals(2_000L, segs[1].endMs)
        assertEquals(2_000L, segs[2].startMs)
        assertEquals(3_000L, segs[2].endMs)
    }

    @Test
    fun `countdown shorter than 900ms still produces 3 segments with 900ms floor`() {
        val segs = EffectStackShortsOverlays.splitCountdownWindow(0L, 100L)
        assertEquals(3, segs.size)
        assertEquals(listOf("3", "2", "1"), segs.map { it.text })
        // span coerceAtLeast(900) = 900, third = 300, last segment 끝은 outEnd 그대로(=100).
        assertEquals(0L, segs[0].startMs)
        assertEquals(300L, segs[0].endMs)
        assertEquals(300L, segs[1].startMs)
        assertEquals(600L, segs[1].endMs)
        assertEquals(600L, segs[2].startMs)
        assertEquals(100L, segs[2].endMs)
    }

    @Test
    fun `countdown preserves outStart offset`() {
        val segs = EffectStackShortsOverlays.splitCountdownWindow(4_500L, 7_500L)
        assertEquals(4_500L, segs[0].startMs)
        assertEquals(5_500L, segs[0].endMs)
        assertEquals(5_500L, segs[1].startMs)
        assertEquals(6_500L, segs[1].endMs)
        assertEquals(6_500L, segs[2].startMs)
        assertEquals(7_500L, segs[2].endMs)
    }

    @Test
    fun `countdown segments use countdownPulse animator`() {
        // 검증: 각 세그먼트가 같은 animator 를 공유하고 t=0 에서 큰 scale, t=1 에서 작은 scale.
        val segs = EffectStackShortsOverlays.splitCountdownWindow(0L, 3_000L)
        val first = segs[0].animator(0f)
        val last = segs[0].animator(1f)
        assertEquals(1.4f, first.scale, 0.001f)
        // t=1 은 페이드아웃 적용돼 alpha=0, scale 은 1.0
        assertEquals(1.0f, last.scale, 0.001f)
        assertEquals(0f, last.alpha, 0.001f)
    }
}
