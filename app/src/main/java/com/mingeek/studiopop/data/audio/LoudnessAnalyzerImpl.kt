package com.mingeek.studiopop.data.audio

import android.net.Uri
import com.mingeek.studiopop.data.caption.PcmDecoder
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlin.math.log10
import kotlin.math.sqrt

/**
 * RMS 기반 라우드니스 곡선. EBU R128 풀구현(K-weighting + gating + integrated LUFS) 은 R5+ 에서
 * 도입. 자동 더킹 (BGM 볼륨이 보이스 라우드니스 따라 자동 -6dB 등) 입력으로는 RMS 근사값으로
 * 충분한 품질.
 *
 * 출력 [LoudnessData.lufsSamples] 는 dBFS (0 = digital full scale, 음수 = 더 작음). 보통 한국어
 * 음성은 -25 ~ -15 dBFS 범위. integratedLufs 는 전체 평균 dBFS.
 *
 * 윈도우: 400ms (EBU R128 momentary loudness 권장값과 동일).
 */
class LoudnessAnalyzerImpl(
    private val pcmDecoder: PcmDecoder,
) : LoudnessAnalyzer {

    override suspend fun analyze(uri: Uri): Result<LoudnessData> = withContext(Dispatchers.Default) {
        runCatching {
            val pcm = pcmDecoder.decode(uri, SAMPLE_RATE)
            val windowMs = 400L
            val windowSize = (SAMPLE_RATE * windowMs / 1000L).toInt()
            val hopSize = windowSize / 4 // 100ms 간격
            val numWindows = ((pcm.size - windowSize) / hopSize + 1).coerceAtLeast(1)
            val out = FloatArray(numWindows)
            var totalEnergy = 0.0
            var totalSamples = 0L

            for (w in 0 until numWindows) {
                val start = w * hopSize
                val end = minOf(start + windowSize, pcm.size)
                var sumSq = 0.0
                for (i in start until end) {
                    val v = pcm[i].toDouble() / 32768.0
                    sumSq += v * v
                }
                val rms = sqrt(sumSq / (end - start).coerceAtLeast(1))
                out[w] = if (rms > 1e-7) (20.0 * log10(rms)).toFloat() else SILENCE_DBFS
                totalEnergy += sumSq
                totalSamples += (end - start)
            }
            val integratedRms = sqrt(totalEnergy / totalSamples.coerceAtLeast(1L))
            val integrated = if (integratedRms > 1e-7) (20.0 * log10(integratedRms)).toFloat()
                else SILENCE_DBFS

            LoudnessData(
                windowMs = hopSize.toLong() * 1000L / SAMPLE_RATE,
                lufsSamples = out,
                integratedLufs = integrated,
            )
        }
    }

    private companion object {
        const val SAMPLE_RATE = 16_000
        /** 무음(또는 거의 무음) 시 사용할 floor — log(0) 회피. */
        const val SILENCE_DBFS = -90f
    }
}
