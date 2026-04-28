package com.mingeek.studiopop.data.audio

import android.net.Uri
import com.mingeek.studiopop.data.caption.PcmDecoder
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlin.math.abs

/**
 * 16-bit mono PCM → 다운샘플 파형 (peak 기반).
 *
 * 출력 [WaveformData.samples] 는 [-1, 1] 범위. 각 샘플은 한 구간의 절댓값 peak (또는 RMS) 를
 * 담아 시각화 친화적. 200 sps (= 5ms 당 1 샘플) 가 기본 — 1분 영상이면 12000 샘플.
 *
 * 비트 분석은 BeatDetector 가 별도로 수행 — 이 클래스는 시각화에만 책임.
 */
class WaveformSamplerImpl(
    private val pcmDecoder: PcmDecoder,
) : WaveformSampler {

    override suspend fun sample(
        uri: Uri,
        samplesPerSecond: Int,
    ): Result<WaveformData> = withContext(Dispatchers.Default) {
        runCatching {
            val sps = samplesPerSecond.coerceIn(20, 1000)
            val pcm = pcmDecoder.decode(uri, SAMPLE_RATE)
            val groupSize = (SAMPLE_RATE / sps).coerceAtLeast(1)
            val numOut = (pcm.size + groupSize - 1) / groupSize
            val out = FloatArray(numOut)
            for (i in 0 until numOut) {
                val start = i * groupSize
                val end = minOf(start + groupSize, pcm.size)
                var peak = 0
                for (j in start until end) {
                    val v = abs(pcm[j].toInt())
                    if (v > peak) peak = v
                }
                out[i] = peak / 32768f
            }
            WaveformData(samplesPerSecond = sps, samples = out)
        }
    }

    private companion object {
        const val SAMPLE_RATE = 16_000
    }
}
