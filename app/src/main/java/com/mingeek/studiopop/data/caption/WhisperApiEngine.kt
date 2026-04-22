package com.mingeek.studiopop.data.caption

import android.net.Uri

/**
 * 기존 ChunkedTranscriber + WhisperClient 를 SpeechToText 인터페이스로 어댑팅.
 *
 * [apiKeyProvider] 는 DataStore 에서 동적으로 키를 읽음 → 설정 화면 변경 즉시 반영.
 */
class WhisperApiEngine(
    private val transcriber: ChunkedTranscriber,
    private val apiKeyProvider: suspend () -> String,
) : SpeechToText {

    override val id = SttEngine.WHISPER_API

    override suspend fun isAvailable(): SpeechToText.Availability =
        if (apiKeyProvider().isBlank()) {
            SpeechToText.Availability.NeedsSetup(
                reason = "설정 화면에서 OpenAI API key 를 입력하세요.",
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
