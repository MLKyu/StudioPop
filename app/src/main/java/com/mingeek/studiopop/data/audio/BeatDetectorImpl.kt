package com.mingeek.studiopop.data.audio

import android.net.Uri
import com.mingeek.studiopop.data.caption.PcmDecoder
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.jtransforms.fft.FloatFFT_1D
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt
import kotlin.math.sqrt

/**
 * Spectral Flux 기반 onset detection + 자기상관 BPM 추정.
 *
 * 알고리즘:
 *  1. 16kHz mono PCM → 1024 sample 윈도우(64ms) hop=512(32ms)
 *  2. 각 윈도우 magnitude spectrum → 이전 윈도우 대비 양의 차분 합 = spectral flux
 *  3. local max (앞뒤 ±5 윈도우 보다 큰 값) + threshold 통과 = onset 후보
 *  4. onset 간격의 자기상관에서 peak 가장 높은 BPM 후보 선택 (60..180 BPM)
 *
 * 비트가 명확하지 않은 영상(말소리 위주)에선 confidence 가 낮게 나오며, BeatBus 구독자는
 * confidence 임계값으로 비트 동기 효과를 끌 수 있다.
 *
 * 정확도보다 견고성에 무게: 음악 영상에서 대략 BPM ±5 / onset ±50ms 면 자막 펄스·줌 펀치엔
 * 충분한 품질. 정밀 비트 트래킹은 R5+ 의 ML 모델로 별도 도입 가능.
 */
class BeatDetectorImpl(
    private val pcmDecoder: PcmDecoder,
) : BeatDetector {

    override suspend fun detect(uri: Uri): Result<BeatData> = withContext(Dispatchers.Default) {
        runCatching {
            val pcm = pcmDecoder.decode(uri, SAMPLE_RATE)
            if (pcm.size < FFT_SIZE) error("오디오가 너무 짧음 (samples=${pcm.size})")
            val flux = spectralFlux(pcm)
            val onsets = pickOnsets(flux)
            val bpm = estimateBpm(onsets)
            val onsetMs = LongArray(onsets.size) { i ->
                (onsets[i].toLong() * HOP * 1000L) / SAMPLE_RATE
            }
            BeatData(
                bpm = bpm.bpm,
                confidence = bpm.confidence,
                onsets = onsetMs,
            )
        }
    }

    /** PCM → spectral flux 시계열. 길이 = (pcm.size - FFT_SIZE) / HOP + 1. */
    private fun spectralFlux(pcm: ShortArray): FloatArray {
        val fft = FloatFFT_1D(FFT_SIZE.toLong())
        val window = hannWindow(FFT_SIZE)
        val numFrames = max(1, (pcm.size - FFT_SIZE) / HOP + 1)
        val flux = FloatArray(numFrames)
        var prevMag: FloatArray? = null
        val buffer = FloatArray(FFT_SIZE * 2) // realForwardFull 결과 = real,imag 인터리브

        for (frame in 0 until numFrames) {
            val offset = frame * HOP
            // window 적용
            for (i in 0 until FFT_SIZE) {
                val s = if (offset + i < pcm.size) pcm[offset + i].toFloat() / 32768f else 0f
                buffer[i] = s * window[i]
            }
            // 뒷부분(허수) 0
            for (i in FFT_SIZE until buffer.size) buffer[i] = 0f
            fft.realForwardFull(buffer)

            val mag = FloatArray(FFT_SIZE / 2)
            for (k in 0 until FFT_SIZE / 2) {
                val re = buffer[2 * k]
                val im = buffer[2 * k + 1]
                mag[k] = sqrt(re * re + im * im)
            }
            val prev = prevMag
            if (prev != null) {
                var sum = 0f
                for (k in mag.indices) {
                    val diff = mag[k] - prev[k]
                    if (diff > 0f) sum += diff
                }
                flux[frame] = sum
            }
            prevMag = mag
        }
        return flux
    }

    /** local max + adaptive threshold 로 onset frame index 추출. */
    private fun pickOnsets(flux: FloatArray): IntArray {
        if (flux.isEmpty()) return IntArray(0)
        val mean = flux.sum() / flux.size.coerceAtLeast(1)
        val std = run {
            var s = 0f
            for (f in flux) { val d = f - mean; s += d * d }
            sqrt(s / flux.size.coerceAtLeast(1))
        }
        val threshold = mean + std * 0.6f
        val out = ArrayList<Int>()
        val window = 5
        for (i in window until flux.size - window) {
            val v = flux[i]
            if (v < threshold) continue
            var isMax = true
            for (j in 1..window) {
                if (flux[i - j] >= v || flux[i + j] >= v) { isMax = false; break }
            }
            if (isMax) {
                // 마지막 onset 과의 최소 간격(80ms) 보장
                val minGap = (0.080 * SAMPLE_RATE / HOP).roundToInt()
                if (out.isEmpty() || i - out.last() >= minGap) out += i
            }
        }
        return out.toIntArray()
    }

    private data class BpmResult(val bpm: Float, val confidence: Float)

    /**
     * onset 간격 자기상관으로 BPM 추정.
     * 60 BPM = 1.0s/beat, 180 BPM = 0.333s/beat 범위에서 peak 탐색.
     */
    private fun estimateBpm(onsets: IntArray): BpmResult {
        if (onsets.size < 4) return BpmResult(bpm = 0f, confidence = 0f)
        // onset frame index → ms 변환
        val onsetMs = DoubleArray(onsets.size) {
            (onsets[it].toDouble() * HOP * 1000.0) / SAMPLE_RATE
        }
        // onset 시계열을 1ms bin pulse 로 변환 (총 길이 = 마지막 - 첫 + 1ms, 너무 길면 다운샘플)
        val totalMs = (onsetMs.last() - onsetMs.first()).toInt()
        if (totalMs < 1000) return BpmResult(bpm = 0f, confidence = 0f)
        // 10ms 빈으로 다운샘플 (충분한 시간 해상도)
        val binMs = 10
        val pulses = FloatArray(totalMs / binMs + 1)
        for (t in onsetMs) {
            val idx = ((t - onsetMs.first()) / binMs).toInt().coerceIn(0, pulses.lastIndex)
            pulses[idx] = 1f
        }
        // 자기상관: lag 333..1000 ms (180..60 BPM)
        val lagMin = 333 / binMs
        val lagMax = min(pulses.size - 1, 1000 / binMs)
        if (lagMax <= lagMin) return BpmResult(bpm = 0f, confidence = 0f)
        var bestLag = lagMin
        var bestVal = 0f
        for (lag in lagMin..lagMax) {
            var sum = 0f
            for (i in 0 until pulses.size - lag) sum += pulses[i] * pulses[i + lag]
            if (sum > bestVal) { bestVal = sum; bestLag = lag }
        }
        val periodSec = bestLag.toDouble() * binMs / 1000.0
        val bpm = (60.0 / periodSec).toFloat()
        // confidence: peak / total energy 정규화
        val totalEnergy = pulses.sumOf { it.toDouble() }.coerceAtLeast(1.0)
        val confidence = (bestVal / totalEnergy).toFloat().coerceIn(0f, 1f)
        return BpmResult(bpm = bpm, confidence = confidence)
    }

    private fun hannWindow(n: Int): FloatArray = FloatArray(n) { i ->
        (0.5 * (1.0 - kotlin.math.cos(2.0 * Math.PI * i / (n - 1)))).toFloat()
    }

    private companion object {
        const val SAMPLE_RATE = 16_000
        const val FFT_SIZE = 1024
        const val HOP = 512
    }
}
