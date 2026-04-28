package com.mingeek.studiopop.data.text

import java.util.UUID

/**
 * 영상 위에 올라가는 한 텍스트 요소. 자막·타이틀·강조 한 줄·워터마크 모두 이 단일 모델로
 * 표현된다 (기존 [com.mingeek.studiopop.data.editor.TimelineCaption] /
 * [com.mingeek.studiopop.data.editor.TextLayer] / [com.mingeek.studiopop.data.editor.FixedTextTemplate]
 * 의 통합 후계자).
 *
 * R1 단계에선 이 모델로 직접 렌더링하는 코드는 없다. 기존 모델을 [RichTextElement] 로
 * 변환하는 어댑터([RichTextAdapters])만 제공해서 새 시스템(EffectRegistry, AiAssist,
 * BeatBus 등) 이 통일된 입력으로 동작할 수 있게 한다. R2 부터 직접 렌더 경로 추가.
 *
 * @property content 텍스트 원본. 빈 문자열이면 해당 시점 표시 안 함.
 * @property style 시각 스타일.
 * @property animation 진입/퇴장 애니메이션. NONE 이면 정적 표시.
 * @property timing 시간 결정 방식. Manual 이 기본, word-level 이나 AI 추천도 동일 모델로.
 * @property position 화면 위치. Static / Tracked / Animated.
 * @property emphasis 강조어 표시 규칙. null 이면 강조 없음.
 */
data class RichTextElement(
    val id: String = UUID.randomUUID().toString(),
    val content: String,
    val style: RichTextStyle,
    val animation: TextAnimation = TextAnimation.NONE,
    val timing: TextTiming,
    val position: TextPosition = TextPosition.DEFAULT_BOTTOM,
    val emphasis: EmphasisRule? = null,
    val enabled: Boolean = true,
) {
    val sourceStartMs: Long get() = timing.sourceStartMs
    val sourceEndMs: Long get() = timing.sourceEndMs
    val durationMs: Long get() = (sourceEndMs - sourceStartMs).coerceAtLeast(0L)
}

/**
 * 강조어 처리 규칙. 강조 대상 단어들에 [overrideStyle] 을 덧입혀 다른 색·크기로 보여준다.
 * AI 가 제안한 [TextTiming.FromAiHighlight] 의 [EmphasisRange] 와 결합해 작동.
 */
data class EmphasisRule(
    val overrideStyle: RichTextStyle,
    val pulseOnBeat: Boolean = false,
)
