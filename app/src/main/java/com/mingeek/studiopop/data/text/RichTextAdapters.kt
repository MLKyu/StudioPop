package com.mingeek.studiopop.data.text

import com.mingeek.studiopop.data.caption.Cue
import com.mingeek.studiopop.data.design.SystemDefaultFontPack
import com.mingeek.studiopop.data.editor.CaptionStyle
import com.mingeek.studiopop.data.editor.FixedTextTemplate
import com.mingeek.studiopop.data.editor.TextLayer as LegacyTextLayer
import com.mingeek.studiopop.data.editor.TimelineCaption

/**
 * 기존 자막/텍스트 모델을 [RichTextElement] 로 변환하는 어댑터.
 *
 * 골격 단계의 핵심 가치: 새 시스템(EffectRegistry, AiAssist, BeatBus, 4 렌더러 통합)이
 * 기존 데이터를 손대지 않고도 통일된 입력으로 처리할 수 있게 한다. R2+ 에서 점진적으로
 * 새 모델로 마이그레이트할 때 어댑터를 통한 read 경로가 호환성을 보장.
 *
 * 변환 규칙:
 * - content: 그대로
 * - style: 기존 CaptionStyle 의 색·외곽선·배경 알파를 RichTextStyle 로 매핑.
 *          폰트는 SystemDefaultFontPack (R2 에서 테마 폰트로 확장).
 * - animation: NONE (기존 모델은 애니메이션 개념 없음)
 * - position: anchorX/Y → TextPosition.Static
 * - emphasis: null (기존 모델은 강조 개념 없음)
 */
object RichTextAdapters {

    fun fromCaption(c: TimelineCaption): RichTextElement = RichTextElement(
        id = c.id,
        content = c.text,
        style = c.style.toRichTextStyle(),
        timing = TextTiming.Manual(c.sourceStartMs, c.sourceEndMs),
        position = TextPosition.Static(c.style.anchorX, c.style.anchorY),
    )

    /**
     * STT 가 만든 [Cue] 를 직접 받는 어댑터 — word-level 정보가 있으면 보존한다.
     * 보통은 Cue → TimelineCaption 변환 후 [fromCaption] 을 거치지만, 그 경로는 word
     * 정보가 사라지므로 카라오케/강조어 자막을 만들 땐 이 어댑터를 직접 호출한다.
     *
     * @param style 적용할 스타일. 기본은 흰색 + 검은 외곽선.
     */
    fun fromCue(
        c: Cue,
        style: RichTextStyle,
        position: TextPosition = TextPosition.DEFAULT_BOTTOM,
    ): RichTextElement {
        val timing: TextTiming = c.words?.let { ws ->
            TextTiming.FromWordTimestamps(
                sourceStartMs = c.startMs,
                sourceEndMs = c.endMs,
                words = ws.map { WordTiming(word = it.word, startMs = it.startMs, endMs = it.endMs) },
            )
        } ?: TextTiming.Manual(c.startMs, c.endMs)

        return RichTextElement(
            content = c.text,
            style = style,
            timing = timing,
            position = position,
        )
    }

    fun fromTextLayer(t: LegacyTextLayer): RichTextElement = RichTextElement(
        id = t.id,
        content = t.text,
        style = t.style.toRichTextStyle(),
        timing = TextTiming.Manual(t.sourceStartMs, t.sourceEndMs),
        position = TextPosition.Static(t.style.anchorX, t.style.anchorY),
    )

    /**
     * FixedTextTemplate 은 영상 전체에 걸쳐 표시되므로, 어댑터 호출 시점에 출력 시간 범위를
     * 외부에서 주입한다(보통 0..outputDurationMs). perSegmentText 는 R1 단계에서 대표값
     * (defaultText) 로 단순화 — R2 에서 세그먼트별 분할 처리.
     */
    fun fromFixedTemplate(
        t: FixedTextTemplate,
        sourceStartMs: Long,
        sourceEndMs: Long,
    ): RichTextElement = RichTextElement(
        id = t.id,
        content = t.defaultText,
        style = t.resolvedStyle().toRichTextStyle(),
        timing = TextTiming.Manual(sourceStartMs, sourceEndMs),
        position = TextPosition.Static(t.anchor.ndcX, t.anchor.ndcY),
        enabled = t.enabled,
    )
}

/** [CaptionStyle] → [RichTextStyle] 매핑. 폰트는 SystemDefault, 굵기는 bold→700/정상→400. */
fun CaptionStyle.toRichTextStyle(): RichTextStyle {
    val strokes = if (outlineWidth > 0f) listOf(
        TextStroke(color = outlineColor, widthPx = outlineWidth)
    ) else emptyList()

    val background: TextBackground = if (backgroundAlpha > 0) {
        val bg = (backgroundAlpha and 0xFF) shl 24
        TextBackground.Box(color = bg, cornerRadiusPx = 6f)
    } else TextBackground.None

    return RichTextStyle(
        fontPackId = SystemDefaultFontPack.id,
        fontWeight = if (bold) 700 else 400,
        fillBrush = TextBrush.Solid(textColor),
        strokes = strokes,
        background = background,
        sizeScale = sizeScale,
        align = TextAlign.CENTER,
    )
}
