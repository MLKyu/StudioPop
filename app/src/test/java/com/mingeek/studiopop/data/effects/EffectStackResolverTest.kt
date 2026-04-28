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
}
