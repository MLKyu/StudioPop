package com.mingeek.studiopop.data.ai

import com.mingeek.studiopop.data.design.BuiltinLuts
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class LutMatcherTest {

    @Test
    fun `null tone returns null`() {
        assertNull(LutMatcher.match(null))
    }

    @Test
    fun `low saturation maps to mono`() {
        val tone = ToneEstimate(brightness = 0f, saturation = -0.8f, contrast = 0f, warmth = 0f)
        assertEquals(BuiltinLuts.MONO, LutMatcher.match(tone))
    }

    @Test
    fun `dark and warm maps to cinematic`() {
        val tone = ToneEstimate(brightness = -0.3f, saturation = 0.0f, contrast = 0f, warmth = 0.2f)
        assertEquals(BuiltinLuts.CINEMATIC, LutMatcher.match(tone))
    }

    @Test
    fun `cool warmth maps to cool`() {
        val tone = ToneEstimate(brightness = 0.0f, saturation = 0.0f, contrast = 0f, warmth = -0.4f)
        assertEquals(BuiltinLuts.COOL, LutMatcher.match(tone))
    }

    @Test
    fun `bright and saturated maps to vivid`() {
        val tone = ToneEstimate(brightness = 0.3f, saturation = 0.4f, contrast = 0f, warmth = 0f)
        assertEquals(BuiltinLuts.VIVID, LutMatcher.match(tone))
    }

    @Test
    fun `low contrast neutral maps to vintage`() {
        val tone = ToneEstimate(brightness = 0f, saturation = 0f, contrast = -0.5f, warmth = 0f)
        assertEquals(BuiltinLuts.VINTAGE, LutMatcher.match(tone))
    }

    @Test
    fun `mid neutral tone returns null`() {
        val tone = ToneEstimate(brightness = 0f, saturation = 0f, contrast = 0f, warmth = 0f)
        assertNull(LutMatcher.match(tone))
    }
}
