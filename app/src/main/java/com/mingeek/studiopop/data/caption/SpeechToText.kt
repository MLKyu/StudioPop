package com.mingeek.studiopop.data.caption

import android.net.Uri

/**
 * STT 엔진 추상화. 구현체:
 *  - WhisperApiEngine     : OpenAI Whisper API (유료 키 필요, 최상 품질)
 *  - VoskEngine           : Vosk 온디바이스 (무료 Apache 2.0, 50MB 모델 다운로드)
 *  - WhisperCppEngine     : whisper.cpp 온디바이스 (무료 MIT, NDK 필요, 모델 75MB+)
 *
 * 입력은 모두 영상/오디오 Uri. 진행률은 0..1, currentChunk/totalChunks 형태로 보고.
 */
interface SpeechToText {

    val id: SttEngine

    /**
     * 현재 환경(키 / 모델 / NDK lib)에서 즉시 사용 가능한지.
     * UI 에서 disabled 표시·선택 차단용.
     */
    suspend fun isAvailable(): Availability

    /**
     * 영상에서 자막 큐를 생성. 시각은 영상 원본 기준(0부터).
     */
    suspend fun transcribe(
        videoUri: Uri,
        language: String? = "ko",
        onProgress: (Progress) -> Unit = {},
    ): Result<List<Cue>>

    data class Progress(
        val currentStep: Int,
        val totalSteps: Int,
        val phaseLabel: String,
    )

    sealed interface Availability {
        data object Ready : Availability
        data class NeedsSetup(val reason: String, val actionable: Boolean = true) : Availability
        data class Unsupported(val reason: String) : Availability
    }
}

enum class SttEngine(val displayName: String, val subtitle: String) {
    WHISPER_API(
        displayName = "OpenAI Whisper API",
        subtitle = "최상 품질 · 약 ₩80/10분 · 키 필요",
    ),
    VOSK_LOCAL(
        displayName = "Vosk (무료 · 온디바이스)",
        subtitle = "오프라인 · Korean 모델 50MB · 중상 품질",
    ),
    WHISPER_CPP(
        displayName = "whisper.cpp (무료 · 온디바이스)",
        subtitle = "오프라인 · 모델 75MB+ · 상 품질 · 처리 느림",
    ),
}
