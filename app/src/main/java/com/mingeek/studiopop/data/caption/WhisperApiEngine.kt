package com.mingeek.studiopop.data.caption

import android.net.Uri
import com.mingeek.studiopop.BuildConfig

/**
 * 기존 ChunkedTranscriber + WhisperClient 를 SpeechToText 인터페이스로 어댑팅.
 */
class WhisperApiEngine(
    private val transcriber: ChunkedTranscriber,
) : SpeechToText {

    override val id = SttEngine.WHISPER_API

    override suspend fun isAvailable(): SpeechToText.Availability =
        if (BuildConfig.OPENAI_API_KEY.isBlank()) {
            SpeechToText.Availability.NeedsSetup(
                reason = "local.properties 에 OPENAI_API_KEY 가 없습니다.",
            )
        } else SpeechToText.Availability.Ready

    override suspend fun transcribe(
        videoUri: Uri,
        language: String?,
        onProgress: (SpeechToText.Progress) -> Unit,
    ): Result<List<Cue>> = transcriber.transcribe(
        videoUri = videoUri,
        language = language,
        onProgress = { p ->
            onProgress(
                SpeechToText.Progress(
                    currentStep = p.currentChunk,
                    totalSteps = p.totalChunks,
                    phaseLabel = p.phaseLabel,
                )
            )
        },
    )
}
