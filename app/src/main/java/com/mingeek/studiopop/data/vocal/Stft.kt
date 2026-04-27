package com.mingeek.studiopop.data.vocal

import org.jtransforms.fft.FloatFFT_1D
import kotlin.math.PI
import kotlin.math.cos

/**
 * UVR MDX-Net 전용 STFT / iSTFT 유틸 (JTransforms 기반).
 *
 * PyTorch 의 `torch.stft(center=False)` 에 해당하는 동작:
 *  - 입력 오디오를 hop 간격으로 슬라이드하며 Hann window(length = [nFft]) 곱해 FFT
 *  - 출력 frames = `floor((len - nFft) / hop) + 1`
 *  - 각 frame 은 `nFft/2 + 1` 개의 complex bin 으로 표현 (real + imag interleaved)
 *
 * iSTFT 는 동일 파라미터로 overlap-add + window-normalize.
 *
 * JTransforms `realForward(a)` 는 **packed** 출력:
 *   a[0]     = Re[0]
 *   a[1]     = Re[N/2]              (Nyquist)
 *   a[2k]    = Re[k]  for k=1..N/2-1
 *   a[2k+1]  = Im[k]  for k=1..N/2-1
 * 내부에서 이를 표준 complex[N/2+1] 로 언팩 / 반대로 팩.
 */
class Stft(private val nFft: Int, private val hop: Int) {

    private val fft: FloatFFT_1D = FloatFFT_1D(nFft.toLong())
    private val window: FloatArray = hannWindowPeriodic(nFft)
    val freqBins: Int get() = nFft / 2 + 1

    /**
     * [signal] 에 STFT 수행. 반환 shape = `[frames][2 * freqBins]` (interleaved Re, Im per bin).
     * 호출자는 frame 수를 signal 길이와 nFft, hop 로 미리 계산해서 padding.
     */
    fun forward(signal: FloatArray): Array<FloatArray> {
        require(signal.size >= nFft) { "signal too short: ${signal.size} < $nFft" }
        val frames = ((signal.size - nFft) / hop) + 1
        val frame = FloatArray(nFft)
        val out = Array(frames) { FloatArray(2 * freqBins) }
        for (f in 0 until frames) {
            val start = f * hop
            for (i in 0 until nFft) frame[i] = signal[start + i] * window[i]
            fft.realForward(frame)
            unpack(frame, out[f])
        }
        return out
    }

    /**
     * iSTFT — [spectrogram] shape = `[frames][2 * freqBins]` (interleaved Re, Im).
     * 출력 길이 = `hop * (frames - 1) + nFft`.
     */
    fun inverse(spectrogram: Array<FloatArray>): FloatArray {
        val frames = spectrogram.size
        if (frames == 0) return FloatArray(0)
        val outLen = hop * (frames - 1) + nFft
        val out = FloatArray(outLen)
        val normSum = FloatArray(outLen)
        val packed = FloatArray(nFft)

        for (f in 0 until frames) {
            pack(spectrogram[f], packed)
            fft.realInverse(packed, /* scale = */ true)
            val start = f * hop
            for (i in 0 until nFft) {
                out[start + i] += packed[i] * window[i]
                normSum[start + i] += window[i] * window[i]
            }
        }
        // window-square normalize — 0 나눗셈 피하려고 epsilon.
        for (i in 0 until outLen) {
            if (normSum[i] > 1e-8f) out[i] /= normSum[i]
        }
        return out
    }

    /** JTransforms packed → 표준 [Re0, Im0, Re1, Im1, …, Re_{N/2}, Im_{N/2}]. */
    private fun unpack(packed: FloatArray, out: FloatArray) {
        out[0] = packed[0]            // Re[0]
        out[1] = 0f                   // Im[0]
        val half = nFft / 2
        out[2 * half] = packed[1]     // Re[N/2]
        out[2 * half + 1] = 0f        // Im[N/2]
        var k = 1
        while (k < half) {
            out[2 * k] = packed[2 * k]
            out[2 * k + 1] = packed[2 * k + 1]
            k++
        }
    }

    /** 표준 complex → JTransforms packed. */
    private fun pack(complex: FloatArray, packed: FloatArray) {
        val half = nFft / 2
        packed[0] = complex[0]        // Re[0]
        packed[1] = complex[2 * half] // Re[N/2] (Nyquist)
        var k = 1
        while (k < half) {
            packed[2 * k] = complex[2 * k]
            packed[2 * k + 1] = complex[2 * k + 1]
            k++
        }
    }

    /**
     * Hann periodic window: `w[n] = 0.5 * (1 - cos(2π*n/N))` for n=0..N-1.
     * (periodic=True — scipy / torch 기본).
     */
    private fun hannWindowPeriodic(n: Int): FloatArray {
        val w = FloatArray(n)
        for (i in 0 until n) {
            w[i] = (0.5 * (1.0 - cos(2.0 * PI * i / n))).toFloat()
        }
        return w
    }

}
