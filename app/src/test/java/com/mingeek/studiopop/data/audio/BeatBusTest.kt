package com.mingeek.studiopop.data.audio

import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.take
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class BeatBusTest {

    @Test
    fun `subscriber receives emitted events`() = runTest {
        val bus = BeatBus()
        val collected = mutableListOf<BeatBus.BeatEvent>()
        val job = launch { bus.events.take(3).toList(collected) }
        // emit 는 collector 가 ready 된 뒤 발생해야 — runTest 의 스케줄러가 순차 처리
        advanceUntilIdle()
        bus.emit(BeatBus.BeatEvent(sourceTimeMs = 100L, beatIndex = 0))
        bus.emit(BeatBus.BeatEvent(sourceTimeMs = 600L, beatIndex = 1))
        bus.emit(BeatBus.BeatEvent(sourceTimeMs = 1100L, beatIndex = 2))
        advanceUntilIdle()
        job.cancel()
        assertEquals(3, collected.size)
        assertEquals(0, collected[0].beatIndex)
        assertEquals(1100L, collected[2].sourceTimeMs)
    }
}
