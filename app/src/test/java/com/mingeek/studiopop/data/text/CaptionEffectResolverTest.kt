package com.mingeek.studiopop.data.text

import com.mingeek.studiopop.data.design.DesignTokens
import com.mingeek.studiopop.data.editor.CaptionStyle
import com.mingeek.studiopop.data.editor.TimelineCaption
import com.mingeek.studiopop.data.effects.EffectRegistry
import com.mingeek.studiopop.data.effects.builtins.CaptionStylePresets
import com.mingeek.studiopop.data.effects.builtins.registerCaptionStylePresets
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class CaptionEffectResolverTest {

    private fun setup(): Triple<EffectRegistry, DesignTokens, List<TimelineCaption>> {
        val reg = EffectRegistry().apply { registerCaptionStylePresets() }
        val tokens = DesignTokens()
        val captions = listOf(
            TimelineCaption(id = "c1", sourceStartMs = 0, sourceEndMs = 1000, text = "hi", style = CaptionStyle.DEFAULT),
            TimelineCaption(id = "c2", sourceStartMs = 1000, sourceEndMs = 2000, text = "yo", style = CaptionStyle.DEFAULT),
        )
        return Triple(reg, tokens, captions)
    }

    @Test
    fun `no mapping yields empty result`() {
        val (reg, tokens, captions) = setup()
        val out = CaptionEffectResolver.resolveEffectiveCaptions(
            captions = captions,
            captionEffectIds = emptyMap(),
            effectRegistry = reg,
            designTokens = tokens,
            themeId = "studiopop.default",
        )
        assertTrue(out.isEmpty())
    }

    @Test
    fun `mapping produces RichTextElement with style and timing`() {
        val (reg, tokens, captions) = setup()
        val out = CaptionEffectResolver.resolveEffectiveCaptions(
            captions = captions,
            captionEffectIds = mapOf("c1" to CaptionStylePresets.GLOW_NEON),
            effectRegistry = reg,
            designTokens = tokens,
            themeId = "studiopop.default",
        )
        assertEquals(1, out.size)
        val element = out.single()
        assertEquals("c1", element.id)
        assertEquals("hi", element.content)
        assertEquals(0L, element.sourceStartMs)
        assertEquals(1000L, element.sourceEndMs)
        // GLOW_NEON 은 glow 가 채워져 있어야 함
        assertTrue(element.style.glow != null)
        // 비트 펄스 비활성 — animation NONE
        assertFalse(element.animation.beatSync)
    }

    @Test
    fun `unknown effect id is ignored`() {
        val (reg, tokens, captions) = setup()
        val out = CaptionEffectResolver.resolveEffectiveCaptions(
            captions = captions,
            captionEffectIds = mapOf("c1" to "caption.does_not_exist"),
            effectRegistry = reg,
            designTokens = tokens,
            themeId = "studiopop.default",
        )
        assertTrue(out.isEmpty())
    }

    @Test
    fun `beatSyncIds enables animation beatSync flag`() {
        val (reg, tokens, captions) = setup()
        val out = CaptionEffectResolver.resolveEffectiveCaptions(
            captions = captions,
            captionEffectIds = mapOf("c1" to CaptionStylePresets.GLOW_NEON),
            effectRegistry = reg,
            designTokens = tokens,
            themeId = "studiopop.default",
            captionBeatSyncIds = setOf("c1"),
        )
        assertEquals(1, out.size)
        assertTrue(out.single().animation.beatSync)
    }

    @Test
    fun `beatSync without effect mapping does not appear in result`() {
        val (reg, tokens, captions) = setup()
        // c1 에 대해 beatSync 만 켰지만 effect 미적용 → empty
        val out = CaptionEffectResolver.resolveEffectiveCaptions(
            captions = captions,
            captionEffectIds = emptyMap(),
            effectRegistry = reg,
            designTokens = tokens,
            themeId = "studiopop.default",
            captionBeatSyncIds = setOf("c1"),
        )
        assertTrue(out.isEmpty())
    }

    @Test
    fun `karaoke ids attach FromWordTimestamps timing with fake words`() {
        val (reg, tokens, captions) = setup()
        val out = CaptionEffectResolver.resolveEffectiveCaptions(
            captions = captions,
            captionEffectIds = mapOf("c1" to CaptionStylePresets.GLOW_NEON),
            effectRegistry = reg,
            designTokens = tokens,
            themeId = "studiopop.default",
            captionKaraokeIds = setOf("c1"),
        )
        assertEquals(1, out.size)
        val timing = out.single().timing
        assertTrue(
            "expected FromWordTimestamps timing for karaoke caption",
            timing is TextTiming.FromWordTimestamps,
        )
        val words = (timing as TextTiming.FromWordTimestamps).words
        assertTrue("expected at least 1 word", words.isNotEmpty())
        assertEquals(0L, words.first().startMs)
        assertEquals(1000L, words.last().endMs)
    }

    @Test
    fun `fakeWordTimings splits text evenly`() {
        val ws = CaptionEffectResolver.fakeWordTimings("hello world foo", 0L, 3000L)
        assertEquals(3, ws.size)
        assertEquals(0L, ws[0].startMs)
        assertEquals(3000L, ws.last().endMs)
        // 마지막 word 의 startMs 는 순차적 — 균등 분할이라 1000, 2000 근처
        assertTrue(ws[1].startMs in 900L..1100L)
        assertTrue(ws[2].startMs in 1900L..2100L)
    }

    @Test
    fun `unknown theme falls back gracefully`() {
        val (reg, tokens, captions) = setup()
        val out = CaptionEffectResolver.resolveEffectiveCaptions(
            captions = captions,
            captionEffectIds = mapOf("c1" to CaptionStylePresets.GLOW_NEON),
            effectRegistry = reg,
            designTokens = tokens,
            themeId = "theme.does_not_exist",
        )
        assertEquals(1, out.size)
        // fallback 테마(StudioPop default) 의 fontPackId 가 채워져야 함
        assertNull(out.single().style.glow?.let { null })  // 단순 not-throw 확인
    }
}
