package com.mingeek.studiopop.data.caption

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Unit test 환경에선 android.net.Uri 가 사용 불가라 String 기반 internal API 로 검증.
 * Uri 인스턴스 매개변수의 동작은 [CaptionWordStore.put] 가 toString() 위임이라 동일.
 */
class CaptionWordStoreTest {

    private val key = "file:///video.mp4"

    @Test
    fun `putFromCues skips empty word lists`() {
        val store = CaptionWordStore()
        store.putFromCuesByKey(key, listOf(Cue(1, 0, 1000, "hi", words = null)))
        assertTrue(store.getByKey(key).isEmpty())
    }

    @Test
    fun `wordsInRange returns words within bounds`() {
        val store = CaptionWordStore()
        store.putByKey(
            key,
            listOf(
                CueWord("hello", 100L, 500L),
                CueWord("world", 600L, 1100L),
                CueWord("foo", 1500L, 1900L),
            ),
        )
        val inRange = store.wordsInRangeByKey(key, 0L, 1200L)
        assertEquals(listOf("hello", "world"), inRange.map { it.word })
    }

    @Test
    fun `wordsInRange empty for unknown key`() {
        val store = CaptionWordStore()
        val out = store.wordsInRangeByKey(key, 0L, 10_000L)
        assertTrue(out.isEmpty())
    }

    @Test
    fun `put sorts by startMs`() {
        val store = CaptionWordStore()
        store.putByKey(
            key,
            listOf(
                CueWord("c", 2000L, 2500L),
                CueWord("a", 100L, 500L),
                CueWord("b", 1000L, 1400L),
            ),
        )
        assertEquals(listOf("a", "b", "c"), store.getByKey(key).map { it.word })
    }
}
