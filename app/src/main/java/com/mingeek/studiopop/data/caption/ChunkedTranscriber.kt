package com.mingeek.studiopop.data.caption

import android.net.Uri
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * 긴 영상을 오디오 청크 여러 개로 쪼갠 뒤 순차적으로 Whisper 에 보내고,
 * 각 청크의 SRT 타임스탬프를 원본 영상 기준으로 오프셋 보정해서 합친다.
 */
class ChunkedTranscriber(
    private val chunker: AudioChunker,
    private val whisper: WhisperClient,
) {

    data class Progress(
        val currentChunk: Int,
        val totalChunks: Int,
        val phaseLabel: String,
    )

    suspend fun transcribe(
        videoUri: Uri,
        language: String? = "ko",
        onProgress: (Progress) -> Unit = {},
    ): Result<List<Cue>> = withContext(Dispatchers.IO) {
        runCatching {
            onProgress(Progress(0, 1, "오디오 청크 분할 중"))
            val chunks = chunker.chunk(videoUri)
            if (chunks.isEmpty()) error("분할된 오디오 청크가 없습니다")

            val allCues = mutableListOf<Cue>()
            try {
                chunks.forEachIndexed { idx, chunk ->
                    onProgress(
                        Progress(
                            currentChunk = idx + 1,
                            totalChunks = chunks.size,
                            phaseLabel = "${idx + 1}/${chunks.size} 전사 중",
                        )
                    )
                    val srt = whisper.transcribeToSrt(
                        audioFile = chunk.file,
                        language = language,
                    ).getOrThrow()

                    val cues = Srt.parse(srt).map { cue ->
                        cue.copy(
                            startMs = cue.startMs + chunk.startMs,
                            endMs = cue.endMs + chunk.startMs,
                        )
                    }
                    allCues += cues
                }
            } finally {
                chunks.forEach { runCatching { it.file.delete() } }
            }

            // 인덱스 재부여
            allCues.mapIndexed { i, c -> c.copy(index = i + 1) }
        }
    }
}
