package com.mingeek.studiopop.data.vocal

import ai.onnxruntime.OnnxTensor
import ai.onnxruntime.OrtEnvironment
import ai.onnxruntime.OrtSession
import android.content.Context
import android.net.Uri
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.withContext
import java.io.File
import java.nio.FloatBuffer

/**
 * UVR MDX-Net (9482) 기반 보컬 분리 파이프라인.
 *
 *  1. 영상 Uri → 44.1kHz stereo float32 PCM ([StereoPcmDecoder])
 *  2. chunk_size=261120 샘플씩 순차 처리:
 *     - 양 끝 trim(3072) 제로 패딩 → STFT → lower 2048 bin 추출 → 4채널 패킹
 *     - ONNX 추론 → 보컬 complex spectrogram [1,4,2048,256]
 *     - 언팩 → upper 1025 bin 제로 패딩 → iSTFT → trim 양 끝 제거
 *  3. concat 후 compensate(1.035) 배율 → mono 다운믹스 → 16-bit WAV 저장
 *
 *  출력: `filesDir/vocals/vocals_{timestamp}.wav` (앱 uninstall 까지 영속).
 *  진행률: [progress] 0..1 StateFlow 로 UI 업데이트.
 */
class VocalSeparator(
    private val context: Context,
    private val modelManager: UvrModelManager,
    private val stereoDecoder: StereoPcmDecoder,
) {

    private val _progress = MutableStateFlow(0f)
    val progress: StateFlow<Float> = _progress.asStateFlow()

    /**
     * @param sourceStartMs 세그먼트가 사용하는 소스 구간 시작 (0 이면 처음부터). trim 반영.
     * @param sourceEndMs 세그먼트가 사용하는 소스 구간 끝 (null 이면 영상 끝까지).
     * @return 추출된 vocals WAV 파일 (mono 44.1kHz, **길이 = 해당 trim 구간**). 실패 시 Result.failure.
     *
     * 세그먼트가 원본의 일부만 쓰는 경우(예: 10~40초 구간만) 해당 구간만 처리해야 export 시
     * 시각 매칭이 맞음. 전체 영상 디코드 후 slice — 정확도·단순성 trade-off.
     */
    suspend fun extractVocals(
        videoUri: Uri,
        sourceStartMs: Long = 0L,
        sourceEndMs: Long? = null,
    ): Result<File> = withContext(Dispatchers.IO) {
        runCatching {
            _progress.value = 0f
            // 1) 모델 준비
            modelManager.ensureInstalled().getOrThrow()
            _progress.value = 0.02f

            // 2) 오디오 디코드 후 trim 범위로 slice
            val fullStereo = stereoDecoder.decodeFloat32(videoUri)
            val fullFrames = fullStereo.size / 2
            val startFrame = (sourceStartMs * SAMPLE_RATE / 1000L).toInt().coerceIn(0, fullFrames)
            val endFrame = if (sourceEndMs != null) {
                (sourceEndMs * SAMPLE_RATE / 1000L).toInt().coerceIn(startFrame, fullFrames)
            } else fullFrames
            val slicedFrames = endFrame - startFrame
            if (slicedFrames > MAX_FRAMES) {
                error("구간이 너무 깁니다 (${slicedFrames / SAMPLE_RATE}초). " +
                    "현재 최대 ${MAX_FRAMES / SAMPLE_RATE}초(~${MAX_FRAMES / SAMPLE_RATE / 60}분) 지원.")
            }
            if (slicedFrames < CHUNK_SIZE / 4) {
                error("구간이 너무 짧습니다 (최소 ${CHUNK_SIZE / SAMPLE_RATE}초 권장).")
            }
            val stereoInterleaved = FloatArray(slicedFrames * 2)
            fullStereo.copyInto(
                destination = stereoInterleaved,
                destinationOffset = 0,
                startIndex = startFrame * 2,
                endIndex = endFrame * 2,
            )
            val totalFrames = slicedFrames
            _progress.value = 0.08f

            // 3) ONNX 세션 초기화 (한 번)
            val env = OrtEnvironment.getEnvironment()
            val session = env.createSession(modelManager.modelPath(), OrtSession.SessionOptions())
            val stft = Stft(N_FFT, HOP)

            val vocalsStereo: FloatArray = try {
                processAllChunks(stereoInterleaved, env, session, stft)
            } finally {
                runCatching { session.close() }
            }
            _progress.value = 0.92f

            // 4) compensate + mono mixdown
            val monoLen = vocalsStereo.size / 2
            val mono = FloatArray(monoLen)
            for (i in 0 until monoLen) {
                val l = vocalsStereo[i * 2]
                val r = vocalsStereo[i * 2 + 1]
                mono[i] = ((l + r) * 0.5f * COMPENSATE).coerceIn(-1f, 1f)
            }

            // 5) WAV 저장 — cacheDir 은 OS 에 의해 축출될 수 있어 프로젝트 저장 후 며칠 뒤 export
            //    시점에 파일이 사라질 수 있음. 영속적으로 유지되는 filesDir 아래로.
            val outDir = File(context.filesDir, "vocals").apply { mkdirs() }
            val outFile = File(outDir, "vocals_${System.currentTimeMillis()}.wav")
            WavWriter.writePcm16(
                file = outFile,
                samplesFloat = mono,
                sampleRate = SAMPLE_RATE,
                channels = 1,
            )
            _progress.value = 1f
            outFile
        }.onFailure { _progress.value = 0f }
    }

    /**
     * 오디오 전체를 chunk 단위로 순차 처리해 보컬 stereo float 결과 반환.
     * 청크 경계 seam 을 막기 위해 `stride = CHUNK_SIZE - MARGIN` 으로 overlap 을 두고,
     * 출력 결과의 첫 MARGIN 샘플을 이전 청크 꼬리와 **linear crossfade** 해 매끄럽게 이어붙임.
     */
    private fun processAllChunks(
        stereoInterleaved: FloatArray,
        env: OrtEnvironment,
        session: OrtSession,
        stft: Stft,
    ): FloatArray {
        val totalFrames = stereoInterleaved.size / 2
        val stride = CHUNK_SIZE - MARGIN
        // 포지션이 어떻게 진행되든 totalFrames 이하로 쓰므로 넉넉히 여유 버퍼.
        val bufLen = totalFrames + CHUNK_SIZE
        val outL = FloatArray(bufLen)
        val outR = FloatArray(bufLen)

        var position = 0
        var chunkIndex = 0
        // 예상 청크 수 — 진행률 계산용.
        val estimatedChunks =
            ((totalFrames - 1) / stride).coerceAtLeast(0) + 1
        while (position < totalFrames) {
            val (chunkL, chunkR) = sliceStereo(stereoInterleaved, position, CHUNK_SIZE)
            val (vocL, vocR) = processOneChunk(chunkL, chunkR, env, session, stft)

            if (chunkIndex == 0) {
                // 첫 청크는 그대로 복사
                vocL.copyInto(outL, position, 0, CHUNK_SIZE)
                vocR.copyInto(outR, position, 0, CHUNK_SIZE)
            } else {
                // 이전 청크 꼬리(= outL[position..position+MARGIN)) 와
                // 현재 청크 머리(= vocL[0..MARGIN)) 를 linear crossfade
                for (i in 0 until MARGIN) {
                    val w = i.toFloat() / MARGIN
                    outL[position + i] = outL[position + i] * (1f - w) + vocL[i] * w
                    outR[position + i] = outR[position + i] * (1f - w) + vocR[i] * w
                }
                // 나머지는 덮어쓰기
                for (i in MARGIN until CHUNK_SIZE) {
                    outL[position + i] = vocL[i]
                    outR[position + i] = vocR[i]
                }
            }

            position += stride
            chunkIndex += 1
            _progress.value = 0.1f + 0.8f * chunkIndex / estimatedChunks.coerceAtLeast(1)
        }

        val outInterleaved = FloatArray(totalFrames * 2)
        for (i in 0 until totalFrames) {
            outInterleaved[i * 2] = outL[i]
            outInterleaved[i * 2 + 1] = outR[i]
        }
        return outInterleaved
    }

    /**
     * 1 chunk 에 대해 STFT → MDX 추론 → iSTFT → trim 한 뒤 L,R 반환 (각 CHUNK_SIZE 길이).
     */
    private fun processOneChunk(
        chunkL: FloatArray,
        chunkR: FloatArray,
        env: OrtEnvironment,
        session: OrtSession,
        stft: Stft,
    ): Pair<FloatArray, FloatArray> {
        // pad 양 끝 TRIM 제로
        val padded = CHUNK_SIZE + 2 * TRIM
        val lp = FloatArray(padded)
        val rp = FloatArray(padded)
        chunkL.copyInto(lp, TRIM)
        chunkR.copyInto(rp, TRIM)

        // STFT: frames × (2 * freqBins) interleaved
        val specL = stft.forward(lp)
        val specR = stft.forward(rp)
        val frames = specL.size
        require(frames == DIM_T) {
            "Unexpected STFT frames: $frames, expected $DIM_T"
        }
        val freqBins = stft.freqBins // = N_FFT/2 + 1 = 3073

        // Pack → [1, 4, DIM_F, DIM_T] float32. 채널 순서: (stereoL Re, stereoL Im, stereoR Re, stereoR Im)
        val inputBuf = FloatBuffer.allocate(1 * DIM_C * DIM_F * DIM_T)
        // 패킹 규칙: 출력 index = c * DIM_F * DIM_T + bin * DIM_T + t
        for (c in 0 until DIM_C) {
            val isL = (c / 2) == 0
            val isRe = (c % 2) == 0
            for (bin in 0 until DIM_F) {
                for (t in 0 until DIM_T) {
                    val specFrame = if (isL) specL[t] else specR[t]
                    val value = if (isRe) specFrame[2 * bin] else specFrame[2 * bin + 1]
                    val flatIdx = c * DIM_F * DIM_T + bin * DIM_T + t
                    inputBuf.put(flatIdx, value)
                }
            }
        }
        inputBuf.rewind()

        val inputShape = longArrayOf(1, DIM_C.toLong(), DIM_F.toLong(), DIM_T.toLong())
        val outputArr: Array<Array<Array<FloatArray>>> = OnnxTensor
            .createTensor(env, inputBuf, inputShape).use { inputTensor ->
                session.run(mapOf(INPUT_NAME to inputTensor)).use { results ->
                    @Suppress("UNCHECKED_CAST")
                    (results[0].value as Array<Array<Array<FloatArray>>>)
                }
            }
        // outputArr[1][4][DIM_F][DIM_T]

        // Unpack → spec L/R (frames × 2*freqBins), top(freqBins - DIM_F) bins = 0
        val outSpecL = Array(DIM_T) { FloatArray(2 * freqBins) }
        val outSpecR = Array(DIM_T) { FloatArray(2 * freqBins) }
        for (c in 0 until DIM_C) {
            val isL = (c / 2) == 0
            val isRe = (c % 2) == 0
            val target = if (isL) outSpecL else outSpecR
            for (bin in 0 until DIM_F) {
                for (t in 0 until DIM_T) {
                    val v = outputArr[0][c][bin][t]
                    val idx = if (isRe) 2 * bin else 2 * bin + 1
                    target[t][idx] = v
                }
            }
        }

        // iSTFT → padded 샘플
        val recL = stft.inverse(outSpecL)
        val recR = stft.inverse(outSpecR)
        // trim 양 끝 → CHUNK_SIZE 복원
        val vocL = FloatArray(CHUNK_SIZE)
        val vocR = FloatArray(CHUNK_SIZE)
        recL.copyInto(vocL, 0, TRIM, TRIM + CHUNK_SIZE)
        recR.copyInto(vocR, 0, TRIM, TRIM + CHUNK_SIZE)
        return vocL to vocR
    }

    /**
     * interleaved stereo 에서 [startFrame..startFrame+count) 구간을 L/R 로 추출. 끝이 모자라면 0 pad.
     */
    private fun sliceStereo(
        stereoInterleaved: FloatArray,
        startFrame: Int,
        count: Int,
    ): Pair<FloatArray, FloatArray> {
        val totalFrames = stereoInterleaved.size / 2
        val l = FloatArray(count)
        val r = FloatArray(count)
        val available = (totalFrames - startFrame).coerceAtMost(count).coerceAtLeast(0)
        for (i in 0 until available) {
            l[i] = stereoInterleaved[(startFrame + i) * 2]
            r[i] = stereoInterleaved[(startFrame + i) * 2 + 1]
        }
        return l to r
    }

    companion object {
        /** MDX-Net 9482 고정 파라미터. */
        const val SAMPLE_RATE = 44_100
        const val N_FFT = 6144
        const val HOP = 1024
        const val DIM_C = 4
        const val DIM_F = 2048
        const val DIM_T = 256
        /** chunk_size = hop * (DIM_T - 1) = 261120 samples (≈5.92s @ 44.1kHz). */
        const val CHUNK_SIZE = HOP * (DIM_T - 1)
        /** n_fft/2 — chunk 양 끝 zero pad 샘플 수. */
        const val TRIM = N_FFT / 2
        /** UVR 9482 hash unrecognized — sibling 모델 평균값. */
        const val COMPENSATE = 1.035f
        /** ONNX input tensor 이름 (netron 에서 검증된 값). */
        const val INPUT_NAME = "input"
        /**
         * 청크 사이 overlap 샘플 수 — 1초. 청크 경계 seam 에서 linear crossfade 영역.
         * seanghay Python reference 와 동일.
         */
        const val MARGIN = 44_100
        /** OOM 방어선 — 15분 (44.1kHz stereo float32 약 160MB). 실사용 shorts 커버 가능. */
        const val MAX_FRAMES = SAMPLE_RATE * 60 * 15
    }
}
