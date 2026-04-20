package com.mingeek.studiopop.data.caption

import android.content.ContentResolver
import android.media.MediaExtractor
import android.media.MediaFormat
import android.media.MediaMuxer
import android.net.Uri
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.nio.ByteBuffer

/**
 * 영상에서 오디오 트랙만 분리해 m4a(.mp4 컨테이너) 파일로 저장.
 * FFmpeg 없이 Android 내장 MediaExtractor/MediaMuxer 사용.
 *
 * Whisper API 가 mp4/m4a 를 그대로 받으므로 재인코딩 없이 패스스루.
 */
class AudioExtractor(
    private val contentResolver: ContentResolver,
    private val cacheDir: File,
) {

    suspend fun extractAudio(videoUri: Uri, outName: String = "audio_${System.currentTimeMillis()}.m4a"): File =
        withContext(Dispatchers.IO) {
            val outFile = File(cacheDir, outName)

            val extractor = MediaExtractor()
            val pfd = contentResolver.openFileDescriptor(videoUri, "r")
                ?: error("Cannot open video: $videoUri")

            try {
                pfd.use { extractor.setDataSource(it.fileDescriptor) }

                val audioTrackIndex = findAudioTrack(extractor)
                    ?: error("오디오 트랙이 없습니다")
                extractor.selectTrack(audioTrackIndex)

                val audioFormat = extractor.getTrackFormat(audioTrackIndex)
                val muxer = MediaMuxer(outFile.absolutePath, MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4)
                val muxTrackIndex = muxer.addTrack(audioFormat)
                muxer.start()

                val bufferSize = audioFormat.getIntegerOrDefault(MediaFormat.KEY_MAX_INPUT_SIZE, DEFAULT_BUFFER_SIZE)
                val buffer = ByteBuffer.allocate(bufferSize)
                val info = android.media.MediaCodec.BufferInfo()

                while (true) {
                    val sampleSize = extractor.readSampleData(buffer, 0)
                    if (sampleSize < 0) break
                    info.offset = 0
                    info.size = sampleSize
                    info.presentationTimeUs = extractor.sampleTime
                    info.flags = extractor.sampleFlags
                    muxer.writeSampleData(muxTrackIndex, buffer, info)
                    extractor.advance()
                }

                muxer.stop()
                muxer.release()
            } finally {
                extractor.release()
            }

            outFile
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

    companion object {
        private const val DEFAULT_BUFFER_SIZE = 1 * 1024 * 1024
    }
}
