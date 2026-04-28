package com.mingeek.studiopop.data.effects

import com.mingeek.studiopop.data.effects.builtins.CaptionStylePresets
import com.mingeek.studiopop.data.effects.builtins.IntroOutroPresets
import com.mingeek.studiopop.data.effects.builtins.TransitionPresets
import com.mingeek.studiopop.data.effects.builtins.VideoFxPresets
import com.mingeek.studiopop.data.effects.builtins.registerCaptionStylePresets
import com.mingeek.studiopop.data.effects.builtins.registerIntroOutroPresets
import com.mingeek.studiopop.data.effects.builtins.registerTransitionPresets
import com.mingeek.studiopop.data.effects.builtins.registerVideoFxPresets
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * R2 등록 회귀 테스트. 새 효과 추가 시 카운트만 갱신하면 됨.
 */
class EffectRegistryR2Test {

    @Test
    fun `caption style presets register all 8 entries`() {
        val registry = EffectRegistry()
        registry.registerCaptionStylePresets()

        val byCategory = registry.byCategory(EffectCategory.CAPTION_STYLE)
        assertEquals(8, byCategory.size)
        // 각 id 가 등록됐는지 빠르게 확인.
        listOf(
            CaptionStylePresets.GLOW_NEON,
            CaptionStylePresets.GRADIENT_POP,
            CaptionStylePresets.TRIPLE_STROKE,
            CaptionStylePresets.SHADOW_3D,
            CaptionStylePresets.BUBBLE_CHAT,
            CaptionStylePresets.HIGHLIGHT_PEN,
            CaptionStylePresets.RIBBON,
            CaptionStylePresets.CARD_DROP,
        ).forEach { id ->
            assertNotNull("missing $id", registry.get(id))
        }
    }

    @Test
    fun `transition presets register all 10 entries`() {
        val registry = EffectRegistry()
        registry.registerTransitionPresets()

        val byCategory = registry.byCategory(EffectCategory.TRANSITION)
        assertEquals(10, byCategory.size)
    }

    @Test
    fun `caption style buildStyle returns valid style for every id`() {
        for (def in CaptionStylePresets.DEFINITIONS) {
            val style = CaptionStylePresets.buildStyle(def.id, EffectParamValues())
            assertTrue(
                "fontPackId blank for ${def.id}",
                style.fontPackId.isNotBlank(),
            )
            assertTrue(
                "sizeScale > 0 for ${def.id}",
                style.sizeScale > 0f,
            )
        }
    }

    @Test
    fun `video fx presets register all 3 entries`() {
        val registry = EffectRegistry()
        registry.registerVideoFxPresets()

        val byCategory = registry.byCategory(EffectCategory.VIDEO_FX)
        assertEquals(3, byCategory.size)
        listOf(
            VideoFxPresets.KEN_BURNS,
            VideoFxPresets.ZOOM_PUNCH,
            VideoFxPresets.SPEED_RAMP,
        ).forEach { id -> assertNotNull(registry.get(id)) }
    }

    @Test
    fun `combined R2 R3 registration totals 21 effects`() {
        val registry = EffectRegistry()
        registry.registerCaptionStylePresets()
        registry.registerTransitionPresets()
        registry.registerVideoFxPresets()
        // 8 captions + 10 transitions + 3 video_fx
        assertEquals(21, registry.size())
    }

    @Test
    fun `intro outro presets register all 5 entries`() {
        val registry = EffectRegistry()
        registry.registerIntroOutroPresets()
        val byCategory = registry.byCategory(EffectCategory.SHORTS_PIECE)
        assertEquals(5, byCategory.size)
        listOf(
            IntroOutroPresets.HOOK_TITLE,
            IntroOutroPresets.SIMPLE_TITLE,
            IntroOutroPresets.COUNTDOWN_3,
            IntroOutroPresets.SUBSCRIBE_PROMPT,
            IntroOutroPresets.NEXT_EPISODE_CARD,
        ).forEach { id -> assertNotNull(registry.get(id)) }
    }

    @Test
    fun `combined R2 R3 R5b registration totals 26 effects`() {
        val registry = EffectRegistry()
        registry.registerCaptionStylePresets()
        registry.registerTransitionPresets()
        registry.registerVideoFxPresets()
        registry.registerIntroOutroPresets()
        // 8 + 10 + 3 + 5
        assertEquals(26, registry.size())
    }

    @Test
    fun `duplicate registration throws`() {
        val registry = EffectRegistry()
        registry.registerCaptionStylePresets()
        try {
            registry.registerCaptionStylePresets()
            org.junit.Assert.fail("expected IllegalArgumentException on duplicate")
        } catch (_: IllegalArgumentException) {
            // ok
        }
    }
}
