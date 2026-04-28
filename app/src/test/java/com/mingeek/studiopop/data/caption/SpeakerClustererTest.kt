package com.mingeek.studiopop.data.caption

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class SpeakerClustererTest {

    @Test
    fun `empty input returns empty`() {
        assertTrue(SpeakerClusterer.cluster(emptyList()).isEmpty())
    }

    @Test
    fun `single speaker collapses to one label`() {
        val v1 = vec(1f, 0f, 0f)
        val v2 = vec(0.95f, 0.05f, 0f) // 거의 같은 방향 → cosine ~ 0.998
        val labels = SpeakerClusterer.cluster(listOf(v1, v2, v1))
        assertEquals(listOf("A", "A", "A"), labels)
    }

    @Test
    fun `two distinct speakers get separate labels`() {
        val a = vec(1f, 0f, 0f)
        val b = vec(0f, 1f, 0f) // cosine = 0 → 신규 클러스터
        val labels = SpeakerClusterer.cluster(listOf(a, b, a, b))
        assertEquals(listOf("A", "B", "A", "B"), labels)
    }

    @Test
    fun `null embedding yields null label without disturbing others`() {
        val a = vec(1f, 0f, 0f)
        val labels = SpeakerClusterer.cluster(listOf(a, null, a))
        assertEquals("A", labels[0])
        assertNull(labels[1])
        assertEquals("A", labels[2])
    }

    @Test
    fun `threshold above max similarity creates new cluster`() {
        val a = vec(1f, 0f, 0f)
        val a2 = vec(0.9f, 0.4f, 0f) // 정규화 후 cosine ~ 0.91
        // threshold = 0.95 → a2 는 신규 클러스터
        val labels = SpeakerClusterer.cluster(listOf(a, a2), threshold = 0.95f)
        assertEquals(listOf("A", "B"), labels)
    }

    private fun vec(vararg xs: Float): FloatArray = xs
}
