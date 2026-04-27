package com.mingeek.studiopop.data.vocal

import java.io.File
import java.io.OutputStream

/**
 * 16-bit PCM WAV 파일 writer. 모노/스테레오 지원. float32 → short clamping.
 *
 * WAV header 44 bytes (RIFF/WAVE/fmt/data) + PCM 데이터. Media3 는 WAV 를 표준 입력으로 인식해
 * 분리된 보컬 WAV 를 바로 AudioTrack 의 Uri 로 사용 가능.
 */
object WavWriter {

    /**
     * interleaved [samplesFloat] (mono 면 그대로, stereo 면 L,R,L,R...) 를 WAV 로 저장.
     * float → short clipping: [-1, 1] 밖은 clamp.
     */
    fun writePcm16(
        file: File,
        samplesFloat: FloatArray,
        sampleRate: Int,
        channels: Int,
    ) {
        file.outputStream().use { out ->
            val dataSize = samplesFloat.size * 2
            writeWavHeader(out, dataSize, sampleRate, channels)
            val buf = ByteArray(4096)
            var written = 0
            while (written < samplesFloat.size) {
                val batch = minOf(buf.size / 2, samplesFloat.size - written)
                for (i in 0 until batch) {
                    val f = samplesFloat[written + i]
                    val s = (f.coerceIn(-1f, 1f) * 32767f).toInt().toShort()
                    buf[i * 2] = (s.toInt() and 0xFF).toByte()
                    buf[i * 2 + 1] = ((s.toInt() ushr 8) and 0xFF).toByte()
                }
                out.write(buf, 0, batch * 2)
                written += batch
            }
        }
    }

    private fun writeWavHeader(
        out: OutputStream,
        dataSize: Int,
        sampleRate: Int,
        channels: Int,
    ) {
        val byteRate = sampleRate * channels * 2
        val blockAlign = channels * 2
        val totalRiffSize = 36 + dataSize
        out.write("RIFF".toByteArray(Charsets.US_ASCII))
        out.writeIntLE(totalRiffSize)
        out.write("WAVE".toByteArray(Charsets.US_ASCII))
        out.write("fmt ".toByteArray(Charsets.US_ASCII))
        out.writeIntLE(16)             // PCM fmt chunk size
        out.writeShortLE(1)            // format = PCM
        out.writeShortLE(channels)
        out.writeIntLE(sampleRate)
        out.writeIntLE(byteRate)
        out.writeShortLE(blockAlign)
        out.writeShortLE(16)           // bits per sample
        out.write("data".toByteArray(Charsets.US_ASCII))
        out.writeIntLE(dataSize)
    }

    private fun OutputStream.writeIntLE(v: Int) {
        write(v and 0xFF)
        write((v ushr 8) and 0xFF)
        write((v ushr 16) and 0xFF)
        write((v ushr 24) and 0xFF)
    }

    private fun OutputStream.writeShortLE(v: Int) {
        write(v and 0xFF)
        write((v ushr 8) and 0xFF)
    }
}
