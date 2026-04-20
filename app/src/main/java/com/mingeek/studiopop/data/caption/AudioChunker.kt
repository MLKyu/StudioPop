package com.mingeek.studiopop.data.caption

import android.content.ContentResolver
import android.media.MediaCodec
import android.media.MediaExtractor
import android.media.MediaFormat
import android.media.MediaMuxer
import android.net.Uri
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.nio.ByteBuffer

/**
 * 영상을 오디오만 m4a 청크들로 잘라낸다.
 * Whisper API 의 25MB 제한을 회피하기 위해 시간 구간별로 분할.
 *
 * 핵심 트릭: MediaExtractor 로 AAC 샘플을 그대로 읽어 MediaMuxer 로 MP4 컨테이너에 다시 넣음.
 * 재인코딩 없음 → 빠르고 품질 손실 없음.
 */
class AudioChunker(
    private val contentResolver: ContentResolver,
    private val cacheDir: File,
) {

    data class AudioChunk(
        val file: File,
        val startMs: Long,
        val endMs: Long,
    )

    /**
     * 영상에서 오디오를 뽑아 주어진 길이(기본 8분) 단위로 쪼갠다.
     */
    suspend fun chunk(
        videoUri: Uri,
        chunkDurationMs: Long = DEFAULT_CHUNK_MS,
    ): List<AudioChunk> = withContext(Dispatchers.IO) {
        val chunks = mutableListOf<AudioChunk>()
        val extractor = MediaExtractor()
        val pfd = contentResolver.openFileDescriptor(videoUri, "r")
            ?: error("Cannot open video: $videoUri")

        try {
            pfd.use { extractor.setDataSource(it.fileDescriptor) }

            val audioTrack = findAudioTrack(extractor) ?: error("오디오 트랙이 없습니다")
            extractor.selectTrack(audioTrack)
            val format = extractor.getTrackFormat(audioTrack)
            val durationUs = format.getLongOrNull(MediaFormat.KEY_DURATION) ?: 0L
            val totalMs = durationUs / 1000

            var chunkIndex = 0
            var cursorMs = 0L
            while (cursorMs < totalMs) {
                val endMs = (cursorMs + chunkDurationMs).coerceAtMost(totalMs)
                val chunkFile = File(
                    cacheDir,
                    "audio_chunk_${System.currentTimeMillis()}_$chunkIndex.m4a",
                )
                writeChunk(
                    extractor = extractor,
                    srcFormat = format,
                    outFile = chunkFile,
                    startMs = cursorMs,
                    endMs = endMs,
                )
                chunks += AudioChunk(chunkFile, cursorMs, endMs)
                cursorMs = endMs
                chunkIndex++
            }
        } finally {
            extractor.release()
        }

        chunks
    }

    private fun writeChunk(
        extractor: MediaExtractor,
        srcFormat: MediaFormat,
        outFile: File,
        startMs: Long,
        endMs: Long,
    ) {
        val muxer = MediaMuxer(outFile.absolutePath, MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4)
        val muxTrack = muxer.addTrack(srcFormat)
        muxer.start()

        extractor.seekTo(startMs * 1000, MediaExtractor.SEEK_TO_PREVIOUS_SYNC)

        val bufferSize = srcFormat.getIntegerOrDefault(MediaFormat.KEY_MAX_INPUT_SIZE, FALLBACK_BUFFER)
        val buffer = ByteBuffer.allocate(bufferSize)
        val info = MediaCodec.BufferInfo()

        while (true) {
            val sampleSize = extractor.readSampleData(buffer, 0)
            if (sampleSize < 0) break
            val sampleTimeMs = extractor.sampleTime / 1000
            if (sampleTimeMs >= endMs) break

            info.offset = 0
            info.size = sampleSize
            // 청크 내부 타임스탬프는 0 기준으로 리베이스
            info.presentationTimeUs = (sampleTimeMs - startMs).coerceAtLeast(0L) * 1000
            info.flags = extractor.sampleFlags

            muxer.writeSampleData(muxTrack, buffer, info)
            if (!extractor.advance()) break
        }

        muxer.stop()
        muxer.release()
    }

    private fun findAudioTrack(extractor: MediaExtractor): Int? {
        for (i in 0 until extractor.trackCount) {
            val mime = extractor.getTrackFormat(i).getString(MediaFormat.KEY_MIME) ?: continue
            if (mime.startsWith("audio/")) return i
        }
        return null
    }

    private fun MediaFormat.getIntegerOrDefault(key: String, default: Int): Int =
        if (containsKey(key)) getInteger(key) else default

    private fun MediaFormat.getLongOrNull(key: String): Long? =
        if (containsKey(key)) getLong(key) else null

    companion object {
        private const val DEFAULT_CHUNK_MS = 8 * 60 * 1000L
        private const val FALLBACK_BUFFER = 1 * 1024 * 1024
    }
}
