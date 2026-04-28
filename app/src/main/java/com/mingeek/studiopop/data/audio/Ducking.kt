package com.mingeek.studiopop.data.audio

import com.mingeek.studiopop.data.keyframe.Keyframe
import com.mingeek.studiopop.data.keyframe.KeyframeTrack
import com.mingeek.studiopop.data.keyframe.lerpFloat
import kotlin.math.pow

/**
 * BGM 자동 더킹 정책. 원본 영상의 음성 [LoudnessData] 를 입력으로 BGM 볼륨 곡선(시간별
 * multiplier 0..1) 을 만들어준다. 사용자가 "보이스 위에 BGM 자동 -6dB" 같은 효과를
 * 클릭 한 번으로 적용 가능.
 *
 * 현재 R4: 단일 임계값 + 감쇠 정책.
 *  - 음성 dBFS 가 [voiceThresholdDbfs] 이상이면 BGM 을 [duckingDb] 만큼 감쇠.
 *  - 음성이 끝나면 [releaseMs] 에 걸쳐 원래 볼륨으로 복귀.
 *  - 너무 짧은 침묵(<[holdMs])은 침묵으로 보지 않고 ducking 유지 — 단어 사이 미세 휴지에
 *    BGM 이 출렁이는 현상 방지.
 *
 * 출력은 시각 ms 기준의 [KeyframeTrack] — Timeline 의 BGM 볼륨 시간 곡선으로 그대로 적용 가능.
 */
data class DuckingProfile(
    val voiceThresholdDbfs: Float = -35f,
    val duckingDb: Float = -6f,
    val attackMs: Long = 80L,
    val releaseMs: Long = 350L,
    val holdMs: Long = 200L,
) {
    /** dB 감쇠 → 선형 multiplier (e.g. -6dB → 0.501). */
    val duckingFactor: Float
        get() = 10f.pow(duckingDb / 20f)

    companion object {
        val DEFAULT = DuckingProfile()
    }
}

object Ducking {

    /**
     * [LoudnessData] 곡선과 [DuckingProfile] 로 BGM 볼륨 multiplier 키프레임 트랙을 생성.
     *
     * 트랙은 음성 시작 직전(attack 시작)과 음성 종료 직후(release 끝) 시점에 keyframe 을 두어
     * 트랜지션이 자연스럽게 보간된다. [bgmBaseVolume] 가 1.0 이면 multiplier 가 그대로 BGM
     * 볼륨이 됨.
     *
     * @return time(ms) → multiplier 트랙. 빈 LoudnessData 면 빈 트랙.
     */
    fun buildVolumeTrack(
        loudness: LoudnessData,
        profile: DuckingProfile = DuckingProfile.DEFAULT,
        bgmBaseVolume: Float = 1f,
    ): KeyframeTrack<Float> {
        val samples = loudness.lufsSamples
        if (samples.isEmpty()) {
            return KeyframeTrack(emptyList(), interpolator = ::lerpFloat)
        }
        val windowMs = loudness.windowMs.coerceAtLeast(1L)
        val duckLevel = bgmBaseVolume * profile.duckingFactor

        val keyframes = mutableListOf<Keyframe<Float>>()
        var ducking = false
        var lastVoiceTimeMs = -1L

        for ((idx, dbfs) in samples.withIndex()) {
            val timeMs = idx * windowMs
            val isVoice = dbfs >= profile.voiceThresholdDbfs
            if (isVoice) {
                if (!ducking) {
                    // attack 시작 — 직전 시점 base volume, attack 후 duckLevel
                    keyframes += Keyframe(
                        timeMs = (timeMs - profile.attackMs).coerceAtLeast(0L),
                        value = bgmBaseVolume,
                    )
                    keyframes += Keyframe(timeMs = timeMs, value = duckLevel)
                    ducking = true
                }
                lastVoiceTimeMs = timeMs
            } else if (ducking && timeMs - lastVoiceTimeMs >= profile.holdMs) {
                // release 시작
                keyframes += Keyframe(timeMs = lastVoiceTimeMs + profile.holdMs, value = duckLevel)
                keyframes += Keyframe(
                    timeMs = lastVoiceTimeMs + profile.holdMs + profile.releaseMs,
                    value = bgmBaseVolume,
                )
                ducking = false
            }
        }
        if (ducking) {
            // 영상이 음성 중간에 끝나도 release 키프레임을 추가해 BGM 이 마지막에 base 로 복귀.
            // 추가 안 하면 export 끝까지 -6dB 로 막혀 버즈 같은 인상.
            val totalMs = samples.size.toLong() * windowMs
            keyframes += Keyframe(timeMs = lastVoiceTimeMs, value = duckLevel)
            val targetReleaseMs = lastVoiceTimeMs + profile.releaseMs
            val cappedReleaseMs = minOf(targetReleaseMs, totalMs)
            // 음성 종료 직후로 release 가 갈 시간이 남았으면 정상 복귀, 그렇지 않고 영상이 즉시
            // 끝나면 동일 timeMs 에 keyframe 두 개가 들어가지 않게 (KeyframeTrack 의 sampleAt
            // 이진탐색이 모호해짐) duck 키프레임 자체를 base 로 대체해 마무리.
            if (cappedReleaseMs > lastVoiceTimeMs) {
                keyframes += Keyframe(timeMs = cappedReleaseMs, value = bgmBaseVolume)
            } else {
                // duckLevel 키프레임이 이미 들어갔으니 마지막에 한 단계 더 base 로 — 시간을 1ms
                // 미세 추가해 sampleAt 이 base 를 되돌리도록 함.
                keyframes += Keyframe(timeMs = lastVoiceTimeMs + 1L, value = bgmBaseVolume)
            }
        }
        return KeyframeTrack(keyframes, interpolator = ::lerpFloat)
    }
}
