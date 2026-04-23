package com.mingeek.studiopop.data.editor

import android.net.Uri
import androidx.core.net.toUri
import java.io.File

/**
 * SFX 지연 재생을 위해 "정확히 N ms 길이의 무음 WAV" 파일을 캐시에 생성.
 * Media3 Transformer 가 오디오 시퀀스를 concat 하기 때문에, SFX 앞에 무음 항목을 끼워넣는
 * 방식으로 "영상 t=X ms 에서 SFX 재생" 을 구현.
 *
 * 8000Hz mono 16-bit PCM — 1초당 ~16KB 라서 캐시 부담 적음.
 */
class SilenceAudioGenerator(cacheDir: File) {

    private val dir: File = File(cacheDir, "silence").apply { mkdirs() }

    /**
     * 같은 duration 요청은 파일 재사용. 파일이 없거나 기대 크기와 맞지 않으면 재생성.
     * 이전 쓰기가 중간에 끊겼을 가능성(크래시, 디스크 풀)을 고려해:
     *  1) 임시 파일(.tmp) 에 전체 WAV 를 쓰고
     *  2) renameTo 로 원자 교체 (실패 시 target 을 지우고 다시 rename → 그래도 실패면 예외)
     *  3) size 검증 — target 의 실제 크기와 기대값 불일치 시 corrupt 로 간주해 재작성
     * 부분 쓰기가 target 에 남아 Transformer 가 읽어 실패하는 것 방지.
     */
    fun generate(durationMs: Long): Uri {
        val clamped = durationMs.coerceAtLeast(1L)
        val file = File(dir, "silence_${clamped}.wav")
        val expectedSize = expectedWavSize(clamped)
        if (!file.exists() || file.length() != expectedSize) {
            writeSilentWavAtomically(file, clamped)
        }
        return file.toUri()
    }

    private fun writeSilentWavAtomically(target: File, durationMs: Long) {
        val tmp = File(target.parentFile, target.name + ".tmp")
        try {
            writeSilentWav(tmp, durationMs)
            if (!tmp.renameTo(target)) {
                // target 이 이미 있어서 rename 거부되는 경우. 지우고 재시도.
                runCatching { target.delete() }
                if (!tmp.renameTo(target)) {
                    // 마지막 안전망: tmp 내용을 유지하되 target 을 확실히 제거해 다음 호출에서 재생성되게 함.
                    runCatching { target.delete() }
                    throw java.io.IOException("silence wav rename 실패: ${tmp.absolutePath} → ${target.absolutePath}")
                }
            }
        } catch (t: Throwable) {
            // tmp / target 정리 — 파셜 파일이 남아 다음 호출에서 읽히지 않도록.
            runCatching { tmp.delete() }
            throw t
        } finally {
            runCatching { if (tmp.exists()) tmp.delete() }
        }
    }

    /** 8000Hz mono 16-bit PCM 기준 WAV 파일 크기(헤더 44 + 샘플 데이터). */
    private fun expectedWavSize(durationMs: Long): Long {
        val sampleRate = 8000L
        val channels = 1L
        val bytesPerSample = 2L
        val numSamples = (sampleRate * durationMs / 1000L).coerceAtLeast(1L)
        val dataSize = numSamples * channels * bytesPerSample
        return 44L + dataSize
    }

    private fun writeSilentWav(file: File, durationMs: Long) {
        val sampleRate = 8000
        val channels = 1
        val bitsPerSample = 16
        val numSamples = (sampleRate.toLong() * durationMs / 1000L).toInt().coerceAtLeast(1)
        val byteRate = sampleRate * channels * bitsPerSample / 8
        val dataSize = numSamples * channels * bitsPerSample / 8
        val totalSize = 36 + dataSize

        file.outputStream().use { out ->
            out.write("RIFF".toByteArray(Charsets.US_ASCII))
            out.writeIntLE(totalSize)
            out.write("WAVE".toByteArray(Charsets.US_ASCII))
            out.write("fmt ".toByteArray(Charsets.US_ASCII))
            out.writeIntLE(16)                 // PCM fmt chunk size
            out.writeShortLE(1)                // format = PCM
            out.writeShortLE(channels)
            out.writeIntLE(sampleRate)
            out.writeIntLE(byteRate)
            out.writeShortLE(channels * bitsPerSample / 8)
            out.writeShortLE(bitsPerSample)
            out.write("data".toByteArray(Charsets.US_ASCII))
            out.writeIntLE(dataSize)
            val buf = ByteArray(4096) // zeros
            var remaining = dataSize
            while (remaining > 0) {
                val chunk = minOf(buf.size, remaining)
                out.write(buf, 0, chunk)
                remaining -= chunk
            }
        }
    }

    private fun java.io.OutputStream.writeIntLE(v: Int) {
        write(v and 0xFF)
        write((v ushr 8) and 0xFF)
        write((v ushr 16) and 0xFF)
        write((v ushr 24) and 0xFF)
    }

    private fun java.io.OutputStream.writeShortLE(v: Int) {
        write(v and 0xFF)
        write((v ushr 8) and 0xFF)
    }
}
