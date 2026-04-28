package com.mingeek.studiopop.data.design

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class CubeLutLoaderTest {

    @Test
    fun `parse 1D LUT minimal`() {
        val cube = """
            TITLE "Minimal 1D"
            LUT_1D_SIZE 2
            0.0 0.0 0.0
            1.0 1.0 1.0
        """.trimIndent()
        val result = CubeLutLoader.loadFromString(cube).getOrThrow()
        assertEquals("Minimal 1D", result.title)
        assertEquals(CubeLut.Dimension.ONE_D, result.dimension)
        assertEquals(2, result.size)
        assertEquals(6, result.data.size)
        assertEquals(0f, result.data[0], 0f)
        assertEquals(1f, result.data[3], 0f)
    }

    @Test
    fun `parse 3D LUT 2x2x2`() {
        // 2³ = 8 entries × 3 channels = 24 floats. b 가 가장 빠른 축.
        val cube = """
            TITLE "Cube 2"
            LUT_3D_SIZE 2
            DOMAIN_MIN 0 0 0
            DOMAIN_MAX 1 1 1
            0 0 0
            1 0 0
            0 1 0
            1 1 0
            0 0 1
            1 0 1
            0 1 1
            1 1 1
        """.trimIndent()
        val result = CubeLutLoader.loadFromString(cube).getOrThrow()
        assertEquals(CubeLut.Dimension.THREE_D, result.dimension)
        assertEquals(2, result.size)
        assertEquals(24, result.data.size)
    }

    @Test
    fun `comments and blank lines are ignored`() {
        val cube = """
            # leading comment
            TITLE "With comments"

            # mid comment
            LUT_1D_SIZE 2
            0 0 0   # last entry zero
            1 1 1
        """.trimIndent()
        val result = CubeLutLoader.loadFromString(cube).getOrThrow()
        assertEquals(2, result.size)
    }

    @Test
    fun `invalid size declaration fails`() {
        val cube = """
            TITLE "Bad"
            LUT_1D_SIZE 2
            0 0 0
        """.trimIndent()
        val result = CubeLutLoader.loadFromString(cube)
        assertTrue("expected failure for short data", result.isFailure)
    }
}
