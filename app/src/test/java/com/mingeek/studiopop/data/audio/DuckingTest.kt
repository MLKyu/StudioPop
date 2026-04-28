package com.mingeek.studiopop.data.audio

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class DuckingTest {

    @Test
    fun `silent loudness produces empty or single-keyframe track`() {
        // 모든 샘플이 -90dBFS (무음) 면 음성 감지 무 → ducking 발생 안 함.
        val loudness = LoudnessData(
            windowMs = 100L,
            lufsSamples = FloatArray(20) { -90f },
            integratedLufs = -90f,
        )
        val track = Ducking.buildVolumeTrack(loudness)
        assertTrue("expected empty track on silent input", track.isEmpty)
    }

    @Test
    fun `voice over threshold triggers ducking and release`() {
        // 0..1s 무음, 1..2s 음성, 2..3s 무음
        val voice = -20f  // dBFS, threshold(-35) 보다 큼 → 음성
        val silence = -80f
        val samples = FloatArray(30) { idx ->
            when (idx) {
                in 0..9 -> silence
                in 10..19 -> voice
                else -> silence
            }
        }
        val loudness = LoudnessData(
            windowMs = 100L,
            lufsSamples = samples,
            integratedLufs = -40f,
        )
        val profile = DuckingProfile(
            voiceThresholdDbfs = -35f,
            duckingDb = -6f,
            attackMs = 80L,
            releaseMs = 300L,
            holdMs = 200L,
        )
        val track = Ducking.buildVolumeTrack(loudness, profile, bgmBaseVolume = 1f)

        // 트랙 시작 부분(시간 0 근처) 은 base = 1.0 — attack 시작 키프레임이 음성 직전에 있어야.
        val atStart = track.sampleAt(0L) ?: error("no value at 0ms")
        assertEquals(1f, atStart, 0.05f)

        // 음성 한가운데(시간 1500ms) 는 duckLevel ≈ 0.5
        val midVoice = track.sampleAt(1500L) ?: error("no value at 1500ms")
        assertTrue(
            "expected ~0.5 at voice mid, got $midVoice",
            midVoice in 0.45f..0.6f,
        )

        // 음성 종료 + release 후(시간 3000ms) 는 다시 base 1.0
        val afterRelease = track.sampleAt(3000L) ?: error("no value at 3000ms")
        assertEquals(1f, afterRelease, 0.1f)
    }
}
