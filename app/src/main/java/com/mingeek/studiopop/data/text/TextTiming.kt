package com.mingeek.studiopop.data.text

/**
 * 텍스트가 화면에 등장할 시점을 어디서 결정하는지.
 *
 * - Manual: 사용자가 직접 [sourceStartMs..sourceEndMs] 지정.
 * - FromWordTimestamps: STT word-level 결과를 그대로 따라감 — 카라오케 자막. 단어
 *   타이밍이 채워져 있어야 한다(R2 에서 SpeechToText 인터페이스 확장 후 채움).
 * - FromAiHighlight: AiAssist 가 추천한 강조 시점 리스트.
 */
sealed interface TextTiming {
    val sourceStartMs: Long
    val sourceEndMs: Long

    data class Manual(
        override val sourceStartMs: Long,
        override val sourceEndMs: Long,
    ) : TextTiming

    data class FromWordTimestamps(
        override val sourceStartMs: Long,
        override val sourceEndMs: Long,
        val words: List<WordTiming>,
    ) : TextTiming

    data class FromAiHighlight(
        override val sourceStartMs: Long,
        override val sourceEndMs: Long,
        val emphasis: List<EmphasisRange>,
    ) : TextTiming
}

/** 한 단어의 시간 범위 + 본문. STT 엔진(Vosk, whisper.cpp)이 제공하는 word-level 데이터의 표준화. */
data class WordTiming(
    val word: String,
    val startMs: Long,
    val endMs: Long,
)

/**
 * AI 가 강조하라고 표시한 범위(글자 인덱스 또는 단어 인덱스). 자막의 강조어 폭탄자막용.
 *
 * @property startCharIndex 시작 문자 인덱스 (포함).
 * @property endCharIndex 종료 문자 인덱스 (배제).
 * @property reason AI 가 제시한 강조 이유 — UX 에 표시 가능 (디버깅/재선택용).
 */
data class EmphasisRange(
    val startCharIndex: Int,
    val endCharIndex: Int,
    val reason: String = "",
)
