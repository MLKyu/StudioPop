package com.mingeek.studiopop.data.effects

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class EffectStackResolverTest {

    @Test
    fun `empty stack returns null`() {
        assertNull(EffectStackResolver.resolveActiveLutId(EffectStack.EMPTY))
    }

    @Test
    fun `non-lut definition returns null`() {
        val stack = EffectStack(
            instances = listOf(
                EffectInstance(
                    definitionId = "video_fx.zoom_punch",
                    sourceStartMs = 0L,
                    sourceEndMs = 1_000L,
                ),
            ),
        )
        assertNull(EffectStackResolver.resolveActiveLutId(stack))
    }

    @Test
    fun `params lutId takes precedence over definitionId`() {
        val stack = EffectStack(
            instances = listOf(
                EffectInstance(
                    definitionId = "lut.fallback",
                    params = EffectParamValues(mapOf("lutId" to "lut.cinematic")),
                    sourceStartMs = 0L,
                    sourceEndMs = 5_000L,
                ),
            ),
        )
        assertEquals("lut.cinematic", EffectStackResolver.resolveActiveLutId(stack))
    }

    @Test
    fun `definition id used when params absent`() {
        val stack = EffectStack(
            instances = listOf(
                EffectInstance(
                    definitionId = "lut.vintage",
                    sourceStartMs = 0L,
                    sourceEndMs = 1_000L,
                ),
            ),
        )
        assertEquals("lut.vintage", EffectStackResolver.resolveActiveLutId(stack))
    }

    @Test
    fun `widest range wins`() {
        val stack = EffectStack(
            instances = listOf(
                EffectInstance(
                    definitionId = "lut.short",
                    sourceStartMs = 0L,
                    sourceEndMs = 500L,
                ),
                EffectInstance(
                    definitionId = "lut.long",
                    sourceStartMs = 0L,
                    sourceEndMs = 10_000L,
                ),
            ),
        )
        assertEquals("lut.long", EffectStackResolver.resolveActiveLutId(stack))
    }

    @Test
    fun `disabled instance ignored`() {
        val stack = EffectStack(
            instances = listOf(
                EffectInstance(
                    definitionId = "lut.disabled",
                    sourceStartMs = 0L,
                    sourceEndMs = 10_000L,
                    enabled = false,
                ),
                EffectInstance(
                    definitionId = "lut.active",
                    sourceStartMs = 0L,
                    sourceEndMs = 1_000L,
                ),
            ),
        )
        assertEquals("lut.active", EffectStackResolver.resolveActiveLutId(stack))
    }

    // resolvePerSegmentLutsForRanges — Timeline 의존 없이 pure-data 로 검증.

    private val twoSegments = listOf(0L to 5_000L, 5_000L to 10_000L)

    @Test
    fun `perSegmentLuts empty stack returns fallback per segment`() {
        val out = EffectStackResolver.resolvePerSegmentLutsForRanges(
            stack = EffectStack.EMPTY,
            segmentRanges = twoSegments,
            fallbackLutId = "theme.lut",
        )
        assertEquals(listOf("theme.lut", "theme.lut"), out)
    }

    @Test
    fun `perSegmentLuts empty segments returns empty`() {
        val stack = EffectStack(
            instances = listOf(
                EffectInstance(definitionId = "lut.x", sourceStartMs = 0L, sourceEndMs = 10_000L),
            ),
        )
        val out = EffectStackResolver.resolvePerSegmentLutsForRanges(
            stack = stack, segmentRanges = emptyList(), fallbackLutId = "theme.lut",
        )
        assertEquals(emptyList<String?>(), out)
    }

    @Test
    fun `perSegmentLuts routes different luts per segment by overlap`() {
        val stack = EffectStack(
            instances = listOf(
                EffectInstance(
                    definitionId = "lut.cinematic", sourceStartMs = 0L, sourceEndMs = 5_000L,
                ),
                EffectInstance(
                    definitionId = "lut.vivid", sourceStartMs = 5_000L, sourceEndMs = 10_000L,
                ),
            ),
        )
        val out = EffectStackResolver.resolvePerSegmentLutsForRanges(
            stack = stack, segmentRanges = twoSegments, fallbackLutId = null,
        )
        assertEquals(listOf("lut.cinematic", "lut.vivid"), out)
    }

    @Test
    fun `perSegmentLuts returns fallback for unmatched segment`() {
        val stack = EffectStack(
            instances = listOf(
                EffectInstance(
                    definitionId = "lut.cinematic", sourceStartMs = 0L, sourceEndMs = 5_000L,
                ),
            ),
        )
        val out = EffectStackResolver.resolvePerSegmentLutsForRanges(
            stack = stack, segmentRanges = twoSegments, fallbackLutId = "theme.lut",
        )
        assertEquals(listOf("lut.cinematic", "theme.lut"), out)
    }

    @Test
    fun `perSegmentLuts picks largest overlap when instance straddles segments`() {
        // LUT 0..6000 → seg0(0..5000) 5000ms 겹침, seg1(5000..10000) 1000ms 겹침. 둘 다 매칭.
        val stack = EffectStack(
            instances = listOf(
                EffectInstance(
                    definitionId = "lut.straddle", sourceStartMs = 0L, sourceEndMs = 6_000L,
                ),
            ),
        )
        val out = EffectStackResolver.resolvePerSegmentLutsForRanges(
            stack = stack, segmentRanges = twoSegments, fallbackLutId = "theme.lut",
        )
        assertEquals(listOf("lut.straddle", "lut.straddle"), out)
    }
}
