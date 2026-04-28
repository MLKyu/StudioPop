package com.mingeek.studiopop.data.design

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class SyntheticCubeLutsTest {

    @Test
    fun `forId returns each builtin lut`() {
        assertNotNull(SyntheticCubeLuts.forId(BuiltinLuts.CINEMATIC.id))
        assertNotNull(SyntheticCubeLuts.forId(BuiltinLuts.VIVID.id))
        assertNotNull(SyntheticCubeLuts.forId(BuiltinLuts.MONO.id))
        assertNotNull(SyntheticCubeLuts.forId(BuiltinLuts.VINTAGE.id))
        assertNotNull(SyntheticCubeLuts.forId(BuiltinLuts.COOL.id))
    }

    @Test
    fun `unknown id returns null`() {
        assertNull(SyntheticCubeLuts.forId("lut.unknown"))
        assertNull(SyntheticCubeLuts.forId(""))
    }

    @Test
    fun `cube has expected dimensions`() {
        val cube = SyntheticCubeLuts.forId(BuiltinLuts.CINEMATIC.id)!!
        assertEquals(CubeLut.Dimension.THREE_D, cube.dimension)
        assertEquals(16, cube.size)
        assertEquals(16 * 16 * 16 * 3, cube.data.size)
    }

    @Test
    fun `mono lut produces equal RGB channels`() {
        val cube = SyntheticCubeLuts.forId(BuiltinLuts.MONO.id)!!
        // 임의 위치 5개 샘플링 — R == G == B 여야 함 (휘도 단일값). 16^3 = 4096 셀.
        val samples = listOf(0, 100, 500, 2048, 4095)
        for (i in samples) {
            val base = i * 3
            val r = cube.data[base]
            val g = cube.data[base + 1]
            val b = cube.data[base + 2]
            assertEquals("mono pixel $i", r, g, 1e-3f)
            assertEquals("mono pixel $i", g, b, 1e-3f)
        }
    }

    @Test
    fun `all values clamped to 0_1`() {
        val cube = SyntheticCubeLuts.forId(BuiltinLuts.VIVID.id)!!
        for (v in cube.data) {
            assertTrue("vivid clamp violated: $v", v in 0f..1f)
        }
    }
}
