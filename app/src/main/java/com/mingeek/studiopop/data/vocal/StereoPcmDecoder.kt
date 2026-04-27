package com.mingeek.studiopop.data.vocal

import android.content.Context
import android.media.MediaCodec
import android.media.MediaExtractor
import android.media.MediaFormat
import android.net.Uri
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.nio.ByteOrder

/**
 * 영상/오디오 Uri 를 **44.1kHz stereo PCM float32** 로 디코드. UVR MDX-Net 입력 포맷에 맞춤.
 *
 * 출력: `FloatArray` 길이 = `2 * frames` (interleaved L, R, L, R …), 샘플 범위 [-1, 1].
 *
 * 채널 처리:
 * - 원본 mono → L=R 로 복제 (stereo 로 업믹스)
 * - 원본 stereo → 그대로
 * - 3+ 채널 → 앞 두 채널 L/R 로 사용 (드묾)
 *
 * 리샘플링: 선형 보간. 44.1kHz 아닌 원본(48kHz 등) 은 약간 품질 손실이지만 보컬 분리 용도엔 허용.
 */
class StereoPcmDecoder(private val context: Context) {

    suspend fun decodeFloat32(
        uri: Uri,
        targetSampleRate: Int = TARGET_SAMPLE_RATE,
    ): FloatArray = withContext(Dispatchers.IO) {
        val extractor = MediaExtractor()
        val pfd = context.contentResolver.openFileDescriptor(uri, "r")
            ?: error("파일 열기 실패: $uri")
        try {
            pfd.use { extractor.setDataSource(it.fileDescriptor) }

            val audioTrackIndex = (0 until extractor.trackCount).firstOrNull { i ->
                extractor.getTrackFormat(i).getString(MediaFormat.KEY_MIME)
                    ?.startsWith("audio/") == true
            } ?: error("오디오 트랙이 없습니다")

            extractor.selectTrack(audioTrackIndex)
            val inputFormat = extractor.getTrackFormat(audioTrackIndex)
            val mime = inputFormat.getString(MediaFormat.KEY_MIME)!!

            val codec = MediaCodec.createDecoderByType(mime)
            codec.configure(inputFormat, null, null, 0)
            codec.start()

            var sourceSampleRate = inputFormat.intOrDefault(MediaFormat.KEY_SAMPLE_RATE, 0)
            var sourceChannels = inputFormat.intOrDefault(MediaFormat.KEY_CHANNEL_COUNT, 1)

            val chunks = mutableListOf<FloatArray>()
            val info = MediaCodec.BufferInfo()
            var inputDone = false
            var outputDone = false

            while (!outputDone) {
                if (!inputDone) {
                    val inputIdx = codec.dequeueInputBuffer(TIMEOUT_US)
                    if (inputIdx >= 0) {
                        val buf = codec.getInputBuffer(inputIdx) ?: continue
                        val sampleSize = extractor.readSampleData(buf, 0)
                        if (sampleSize < 0) {
                            codec.queueInputBuffer(
                                inputIdx, 0, 0, 0,
                                MediaCodec.BUFFER_FLAG_END_OF_STREAM,
                            )
                            inputDone = true
                        } else {
                            codec.queueInputBuffer(
                                inputIdx, 0, sampleSize, extractor.sampleTime, 0,
                            )
                            extractor.advance()
                        }
                    }
                }
                when (val outIdx = codec.dequeueOutputBuffer(info, TIMEOUT_US)) {
                    MediaCodec.INFO_OUTPUT_FORMAT_CHANGED -> {
                        val fmt = codec.outputFormat
                        sourceSampleRate = fmt.intOrDefault(MediaFormat.KEY_SAMPLE_RATE, sourceSampleRate)
                        sourceChannels = fmt.intOrDefault(MediaFormat.KEY_CHANNEL_COUNT, sourceChannels)
                    }
                    MediaCodec.INFO_TRY_AGAIN_LATER -> Unit
                    else -> if (outIdx >= 0) {
                        if (info.size > 0) {
                            val buf = codec.getOutputBuffer(outIdx)
                            if (buf != null) {
                                buf.position(info.offset)
                                buf.limit(info.offset + info.size)
                                val shortCount = info.size / 2
                                val shorts = ShortArray(shortCount)
                                buf.order(ByteOrder.LITTLE_ENDIAN).asShortBuffer().get(shorts)
                                chunks += toStereoFloat(shorts, sourceChannels)
                            }
                        }
                        codec.releaseOutputBuffer(outIdx, false)
                        if ((info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM) != 0) {
                            outputDone = true
                        }
                    }
                }
            }

            codec.stop()
            codec.release()
            extractor.release()

            val totalFloats = chunks.sumOf { it.size }
            val merged = FloatArray(totalFloats)
            var offset = 0
            for (c in chunks) {
                c.copyInto(merged, offset)
                offset += c.size
            }
            if (sourceSampleRate == 0 || sourceSampleRate == targetSampleRate) merged
            else resampleStereoLinear(merged, sourceSampleRate, targetSampleRate)
        } catch (e: Throwable) {
            runCatching { extractor.release() }
            throw e
        }
    }

    /**
     * interleaved PCM16 [shorts] 를 stereo float32 (interleaved L, R) 로 변환.
     * mono → L=R 복제. stereo → 그대로. 3+ 채널 → 앞 두 채널만 사용.
     */
    private fun toStereoFloat(shorts: ShortArray, channels: Int): FloatArray {
        if (channels <= 1) {
            val out = FloatArray(shorts.size * 2)
            for (i in shorts.indices) {
                val v = shorts[i].toInt() / 32768f
                out[i * 2] = v
                out[i * 2 + 1] = v
            }
            return out
        }
        val frames = shorts.size / channels
        val out = FloatArray(frames * 2)
        for (f in 0 until frames) {
            out[f * 2] = shorts[f * channels].toInt() / 32768f
            out[f * 2 + 1] = shorts[f * channels + 1].toInt() / 32768f
        }
        return out
    }

    private fun resampleStereoLinear(input: FloatArray, fromHz: Int, toHz: Int): FloatArray {
        if (fromHz == toHz || input.isEmpty()) return input
        val inputFrames = input.size / 2
        val ratio = fromHz.toDouble() / toHz
        val outFrames = (inputFrames / ratio).toInt().coerceAtLeast(1)
        val out = FloatArray(outFrames * 2)
        for (i in 0 until outFrames) {
            val srcPos = i * ratio
            val idx = srcPos.toInt()
            val frac = srcPos - idx
            val a0 = input[idx * 2]
            val a1 = input[idx * 2 + 1]
            val b0 = if (idx + 1 < inputFrames) input[(idx + 1) * 2] else a0
            val b1 = if (idx + 1 < inputFrames) input[(idx + 1) * 2 + 1] else a1
            out[i * 2] = (a0 + (b0 - a0) * frac).toFloat()
            out[i * 2 + 1] = (a1 + (b1 - a1) * frac).toFloat()
        }
        return out
    }

    private fun MediaFormat.intOrDefault(key: String, default: Int): Int =
        if (containsKey(key)) getInteger(key) else default

    companion object {
        const val TARGET_SAMPLE_RATE = 44_100
        private const val TIMEOUT_US = 10_000L
    }
}
