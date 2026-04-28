package com.mingeek.studiopop.data.audio

import android.net.Uri
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.withContext

/**
 * 한 영상에 대한 3가지 오디오 분석(beat / loudness / waveform) 을 한 번에 수행하고 [AnalysisCache]
 * 에 저장하는 단일 진입점. 호출 측은 캐시 hit 시 비싼 분석 전체를 건너뛴다.
 *
 * 분석은 internally 병렬로 수행 — PcmDecoder 가 각 분석 안에서 호출되므로 PCM 디코드는 3번
 * 일어나지만, mono 16kHz 디코드 비용이 가장 큰 작업이라 한 번만 디코드하도록 최적화하면
 * 좋다 (R4 후속). 현재는 단순 병렬 — 1분 영상 ≈ 1.5초 안에 끝나는 수준.
 */
class AudioAnalysisService(
    private val beatDetector: BeatDetector,
    private val loudnessAnalyzer: LoudnessAnalyzer,
    private val waveformSampler: WaveformSampler,
    private val cache: AnalysisCache,
) {

    /**
     * 캐시 hit 면 즉시 반환, 아니면 3가지 분석 병렬 수행 후 캐시 저장.
     * 부분 실패는 해당 필드 null 로 흡수 — 호출 측은 부분 결과에서도 의미 있는 동작 가능.
     */
    suspend fun analyze(
        uri: Uri,
        durationMs: Long,
    ): AudioAnalysis = withContext(Dispatchers.Default) {
        cache.get(uri)?.let { return@withContext it }
        val analysis = coroutineScope {
            val beatJob = async { beatDetector.detect(uri).getOrNull() }
            val loudnessJob = async { loudnessAnalyzer.analyze(uri).getOrNull() }
            val waveformJob = async { waveformSampler.sample(uri).getOrNull() }
            AudioAnalysis(
                sourceUri = uri,
                durationMs = durationMs,
                waveform = waveformJob.await(),
                beats = beatJob.await(),
                loudness = loudnessJob.await(),
            )
        }
        cache.put(uri, analysis)
        analysis
    }

    /** 이미 캐시된 결과만 가져옴. 분석 강제 안 함. UI 가 비동기적으로 결과를 기다릴 때 활용. */
    fun cached(uri: Uri): AudioAnalysis? = cache.get(uri)

    fun invalidate(uri: Uri) {
        cache.invalidate(uri)
    }
}
