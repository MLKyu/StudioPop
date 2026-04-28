package com.mingeek.studiopop.data.audio

import android.net.Uri

/**
 * 한 영상 또는 BGM 오디오의 분석 결과 묶음. 영상별 1회 계산하고 캐시(Room) 에 저장해
 * 자막 펄스·비트 컷·속도 램프·자동 더킹이 모두 같은 결과를 공유한다.
 *
 * R1 골격 단계에선 인터페이스만 — 실제 [BeatDetector] / [LoudnessAnalyzer] / [WaveformSampler]
 * 구현은 R4 에서. 캐시 ([AnalysisCache]) 도 비어 있는 in-memory 구현만 제공.
 */
data class AudioAnalysis(
    val sourceUri: Uri,
    val durationMs: Long,
    val waveform: WaveformData?,
    val beats: BeatData?,
    val loudness: LoudnessData?,
)

/** 시각화용 다운샘플 파형. samples 는 [-1.0, 1.0] 범위의 RMS 또는 peak. */
data class WaveformData(
    val samplesPerSecond: Int,
    val samples: FloatArray,
) {
    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is WaveformData) return false
        return samplesPerSecond == other.samplesPerSecond &&
            samples.contentEquals(other.samples)
    }
    override fun hashCode(): Int =
        samplesPerSecond * 31 + samples.contentHashCode()
}

/** 비트 검출 결과. onsets 는 비트 시각(ms). bpm 은 추정값 — 신뢰도 [confidence] 동봉. */
data class BeatData(
    val bpm: Float,
    val confidence: Float,
    val onsets: LongArray,
) {
    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is BeatData) return false
        return bpm == other.bpm && confidence == other.confidence &&
            onsets.contentEquals(other.onsets)
    }
    override fun hashCode(): Int {
        var h = bpm.hashCode()
        h = 31 * h + confidence.hashCode()
        h = 31 * h + onsets.contentHashCode()
        return h
    }
}

/** 라우드니스(LUFS) 곡선 — 더킹 결정에 사용. window 마다 한 샘플. */
data class LoudnessData(
    val windowMs: Long,
    val lufsSamples: FloatArray,
    val integratedLufs: Float,
) {
    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is LoudnessData) return false
        return windowMs == other.windowMs &&
            integratedLufs == other.integratedLufs &&
            lufsSamples.contentEquals(other.lufsSamples)
    }
    override fun hashCode(): Int {
        var h = windowMs.hashCode()
        h = 31 * h + integratedLufs.hashCode()
        h = 31 * h + lufsSamples.contentHashCode()
        return h
    }
}
