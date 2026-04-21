package com.mingeek.studiopop.data.caption

import android.content.Context
import android.media.MediaCodec
import android.media.MediaExtractor
import android.media.MediaFormat
import android.net.Uri
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.nio.ByteOrder

/**
 * 영상/오디오 Uri 를 16-bit signed mono PCM @ 16kHz 로 디코드.
 * Vosk·whisper.cpp 모두 이 포맷이 표준 입력.
 *
 * MediaExtractor + MediaCodec 으로 디코드 → 멀티채널이면 평균으로 모노 다운믹스 →
 * 선형 보간 리샘플링.
 *
 * 메모리: 16kHz × 2byte × 1초 = 32KB/sec → 10분 영상 ≈ 19MB. 1시간 ≈ 115MB.
 * 매우 긴 영상은 호출부에서 나눠 처리 권장.
 */
class PcmDecoder(private val context: Context) {

    suspend fun decode(
        uri: Uri,
        targetSampleRate: Int = 16_000,
    ): ShortArray = withContext(Dispatchers.IO) {
        val extractor = MediaExtractor()
        val pfd = context.contentResolver.openFileDescriptor(uri, "r")
            ?: error("파일 열기 실패: $uri")

        try {
            pfd.use { extractor.setDataSource(it.fileDescriptor) }

            val audioTrackIndex = (0 until extractor.trackCount).firstOrNull { i ->
                extractor.getTrackFormat(i)
                    .getString(MediaFormat.KEY_MIME)
                    ?.startsWith("audio/") == true
            } ?: error("오디오 트랙이 없습니다")

            extractor.selectTrack(audioTrackIndex)
            val inputFormat = extractor.getTrackFormat(audioTrackIndex)
            val mime = inputFormat.getString(MediaFormat.KEY_MIME)!!

            val codec = MediaCodec.createDecoderByType(mime)
            codec.configure(inputFormat, null, null, 0)
            codec.start()

            val monoChunks = mutableListOf<ShortArray>()
            var sourceSampleRate =
                inputFormat.getIntegerOrDefault(MediaFormat.KEY_SAMPLE_RATE, 0)
            var sourceChannels =
                inputFormat.getIntegerOrDefault(MediaFormat.KEY_CHANNEL_COUNT, 1)

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

                when (val outputIdx = codec.dequeueOutputBuffer(info, TIMEOUT_US)) {
                    MediaCodec.INFO_OUTPUT_FORMAT_CHANGED -> {
                        val fmt = codec.outputFormat
                        sourceSampleRate =
                            fmt.getIntegerOrDefault(MediaFormat.KEY_SAMPLE_RATE, sourceSampleRate)
                        sourceChannels =
                            fmt.getIntegerOrDefault(MediaFormat.KEY_CHANNEL_COUNT, sourceChannels)
                    }

                    MediaCodec.INFO_TRY_AGAIN_LATER -> Unit

                    else -> if (outputIdx >= 0) {
                        if (info.size > 0) {
                            val buf = codec.getOutputBuffer(outputIdx)
                            if (buf != null) {
                                buf.position(info.offset)
                                buf.limit(info.offset + info.size)
                                val shorts = ShortArray(info.size / 2)
                                buf.order(ByteOrder.LITTLE_ENDIAN).asShortBuffer().get(shorts)
                                monoChunks += mixdownToMono(shorts, sourceChannels)
                            }
                        }
                        codec.releaseOutputBuffer(outputIdx, false)
                        if ((info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM) != 0) {
                            outputDone = true
                        }
                    }
                }
            }

            codec.stop()
            codec.release()
            extractor.release()

            val rawTotal = monoChunks.sumOf { it.size }
            val merged = ShortArray(rawTotal)
            var offset = 0
            for (chunk in monoChunks) {
                chunk.copyInto(merged, offset)
                offset += chunk.size
            }

            if (sourceSampleRate == 0 || sourceSampleRate == targetSampleRate) merged
            else resampleLinear(merged, sourceSampleRate, targetSampleRate)
        } catch (e: Throwable) {
            extractor.release()
            throw e
        }
    }

    private fun mixdownToMono(samples: ShortArray, channels: Int): ShortArray {
        if (channels <= 1) return samples
        val frames = samples.size / channels
        val out = ShortArray(frames)
        for (f in 0 until frames) {
            var sum = 0
            for (c in 0 until channels) sum += samples[f * channels + c].toInt()
            out[f] = (sum / channels).toShort()
        }
        return out
    }

    private fun resampleLinear(input: ShortArray, fromHz: Int, toHz: Int): ShortArray {
        if (fromHz == toHz || input.isEmpty()) return input
        val ratio = fromHz.toDouble() / toHz
        val outLen = (input.size / ratio).toInt().coerceAtLeast(1)
        val out = ShortArray(outLen)
        for (i in 0 until outLen) {
            val srcPos = i * ratio
            val idx = srcPos.toInt()
            val frac = srcPos - idx
            val a = input[idx].toInt()
            val b = if (idx + 1 < input.size) input[idx + 1].toInt() else a
            out[i] = (a + (b - a) * frac).toInt().toShort()
        }
        return out
    }

    private fun MediaFormat.getIntegerOrDefault(key: String, default: Int): Int =
        if (containsKey(key)) getInteger(key) else default

    companion object {
        private const val TIMEOUT_US = 10_000L
    }
}
