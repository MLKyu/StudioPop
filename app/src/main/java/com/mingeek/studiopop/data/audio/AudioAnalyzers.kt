package com.mingeek.studiopop.data.audio

import android.net.Uri

/**
 * 오디오 분석 인터페이스들. R1 골격 단계에선 인터페이스만 — 실제 구현(jTransforms 기반 onset
 * 검출, EBU R128 라우드니스 등)은 R4 에서. 인터페이스를 미리 깔아두는 이유:
 * - AiAssist / RenderPlan / EffectStack 이 분석 결과를 받는 자리만 잡아두면 R4 가 들어와도
 *   downstream 코드 수정 없이 동작한다.
 * - R2~R3 의 자막·전환 효과 일부도 분석 결과가 있으면 자동으로 비트 동기로 강화 가능 —
 *   분석 미가용 시 NOOP fallback.
 */
interface BeatDetector {
    suspend fun detect(uri: Uri): Result<BeatData>
}

interface LoudnessAnalyzer {
    suspend fun analyze(uri: Uri): Result<LoudnessData>
}

interface WaveformSampler {
    suspend fun sample(uri: Uri, samplesPerSecond: Int = 200): Result<WaveformData>
}

/** R4 까지 사용할 noop 구현 — 항상 실패하는 stub. */
object NoopBeatDetector : BeatDetector {
    override suspend fun detect(uri: Uri): Result<BeatData> =
        Result.failure(NotImplementedError("BeatDetector not yet implemented (R4)"))
}

object NoopLoudnessAnalyzer : LoudnessAnalyzer {
    override suspend fun analyze(uri: Uri): Result<LoudnessData> =
        Result.failure(NotImplementedError("LoudnessAnalyzer not yet implemented (R4)"))
}

object NoopWaveformSampler : WaveformSampler {
    override suspend fun sample(uri: Uri, samplesPerSecond: Int): Result<WaveformData> =
        Result.failure(NotImplementedError("WaveformSampler not yet implemented (R4)"))
}
