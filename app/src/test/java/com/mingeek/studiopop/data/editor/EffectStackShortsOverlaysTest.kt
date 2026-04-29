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
    fun `countdown 3000ms splits into 3 equal cues with text 3 2 1`() {
        val cues = EffectStackShortsOverlays.splitCountdownWindow(0L, 3_000L)
        assertEquals(3, cues.size)
        assertEquals(listOf("3", "2", "1"), cues.map { it.text })
        assertEquals(0L, cues[0].startMs)
        assertEquals(1_000L, cues[0].endMs)
        assertEquals(1_000L, cues[1].startMs)
        assertEquals(2_000L, cues[1].endMs)
        assertEquals(2_000L, cues[2].startMs)
        assertEquals(3_000L, cues[2].endMs)
    }

    @Test
    fun `countdown shorter than 900ms still produces 3 cues with 900ms floor`() {
        val cues = EffectStackShortsOverlays.splitCountdownWindow(0L, 100L)
        assertEquals(3, cues.size)
        assertEquals(listOf("3", "2", "1"), cues.map { it.text })
        // span coerceAtLeast(900) = 900, third = 300, last cue 끝은 outEnd 그대로(=100).
        assertEquals(0L, cues[0].startMs)
        assertEquals(300L, cues[0].endMs)
        assertEquals(300L, cues[1].startMs)
        assertEquals(600L, cues[1].endMs)
        assertEquals(600L, cues[2].startMs)
        assertEquals(100L, cues[2].endMs)
    }

    @Test
    fun `countdown preserves outStart offset`() {
        // 파이프라인 중간 시각(예: 4500ms 시작) 에 카운트다운이 들어와도 cue 가 그 위치에 박혀야 함.
        val cues = EffectStackShortsOverlays.splitCountdownWindow(4_500L, 7_500L)
        assertEquals(4_500L, cues[0].startMs)
        assertEquals(5_500L, cues[0].endMs)
        assertEquals(5_500L, cues[1].startMs)
        assertEquals(6_500L, cues[1].endMs)
        assertEquals(6_500L, cues[2].startMs)
        assertEquals(7_500L, cues[2].endMs)
    }
}
