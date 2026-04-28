package com.mingeek.studiopop.data.effects.builtins

import com.mingeek.studiopop.data.design.BuiltinFontPacks
import com.mingeek.studiopop.data.design.ColorPalette
import com.mingeek.studiopop.data.design.StudioPopDefaultPalette
import com.mingeek.studiopop.data.effects.EffectCategory
import com.mingeek.studiopop.data.effects.EffectDefinition
import com.mingeek.studiopop.data.effects.EffectParameter
import com.mingeek.studiopop.data.effects.EffectParamValues
import com.mingeek.studiopop.data.effects.EffectRegistry
import com.mingeek.studiopop.data.text.RichTextStyle
import com.mingeek.studiopop.data.text.TextAlign
import com.mingeek.studiopop.data.text.TextBackground
import com.mingeek.studiopop.data.text.TextBrush
import com.mingeek.studiopop.data.text.TextGlow
import com.mingeek.studiopop.data.text.TextShadow
import com.mingeek.studiopop.data.text.TextStroke

/**
 * 자막/텍스트 스타일 8종 프리셋. 각 항목은 두 가지를 동시에 제공한다:
 *
 * 1. [EffectDefinition] — UI 효과 패널이 카드/탭으로 보여주는 메타 + 사용자 조정 파라미터
 *    명세 (색·세기·크기). EffectRegistry 에 등록되어 다른 효과와 동일하게 다뤄진다.
 *
 * 2. [buildStyle] — 효과 id + 파라미터 값 + 채널 팔레트를 받아 실제 [RichTextStyle] 인스턴스
 *    를 만들어주는 빌더. 렌더러(R3+) 는 이 RichTextStyle 만 받으면 페인팅 가능.
 *
 * 새 자막 스타일 추가 = 이 파일에 항목 한 개 더 (EffectDefinition + buildStyle 분기). 효과 id
 * 는 "caption.<key>" 컨벤션.
 */
object CaptionStylePresets {

    // --- 효과 id 상수 ----------------------------------------------------

    const val GLOW_NEON = "caption.glow_neon"
    const val GRADIENT_POP = "caption.gradient_pop"
    const val TRIPLE_STROKE = "caption.triple_stroke"
    const val SHADOW_3D = "caption.shadow_3d"
    const val BUBBLE_CHAT = "caption.bubble_chat"
    const val HIGHLIGHT_PEN = "caption.highlight_pen"
    const val RIBBON = "caption.ribbon"
    const val CARD_DROP = "caption.card_drop"

    // --- 정의 -----------------------------------------------------------

    private val intensityParam = EffectParameter.FloatRange(
        key = "intensity",
        label = "효과 세기",
        min = 0f,
        max = 1.5f,
        default = 1.0f,
    )

    private val sizeScaleParam = EffectParameter.FloatRange(
        key = "sizeScale",
        label = "크기",
        min = 0.5f,
        max = 2.0f,
        default = 1.0f,
    )

    private val accentColorParam = EffectParameter.Color(
        key = "accentColor",
        label = "강조 색",
        default = 0xFFA6FF00.toInt(),
    )

    private val fillColorParam = EffectParameter.Color(
        key = "fillColor",
        label = "텍스트 색",
        default = 0xFFFFFFFF.toInt(),
    )

    val DEFINITIONS: List<EffectDefinition> = listOf(
        EffectDefinition(
            id = GLOW_NEON,
            displayName = "네온 글로우",
            category = EffectCategory.CAPTION_STYLE,
            parameters = listOf(intensityParam, sizeScaleParam, accentColorParam, fillColorParam),
            previewHint = EffectDefinition.PreviewHint(
                emoji = "✨", tagLine = "외곽이 빛나는 네온 자막",
                accentColor = 0xFFA6FF00.toInt(),
            ),
        ),
        EffectDefinition(
            id = GRADIENT_POP,
            displayName = "그라디언트 팝",
            category = EffectCategory.CAPTION_STYLE,
            parameters = listOf(sizeScaleParam, accentColorParam, fillColorParam),
            previewHint = EffectDefinition.PreviewHint(
                emoji = "🌈", tagLine = "두 색 그라디언트 텍스트",
            ),
        ),
        EffectDefinition(
            id = TRIPLE_STROKE,
            displayName = "3중 외곽선",
            category = EffectCategory.CAPTION_STYLE,
            parameters = listOf(sizeScaleParam, accentColorParam, fillColorParam),
            previewHint = EffectDefinition.PreviewHint(
                emoji = "🎯", tagLine = "외곽선 3겹 — 강력한 가독성",
            ),
        ),
        EffectDefinition(
            id = SHADOW_3D,
            displayName = "3D 섀도",
            category = EffectCategory.CAPTION_STYLE,
            parameters = listOf(intensityParam, sizeScaleParam, accentColorParam, fillColorParam),
            previewHint = EffectDefinition.PreviewHint(
                emoji = "🎲", tagLine = "두께감 있는 입체 자막",
            ),
        ),
        EffectDefinition(
            id = BUBBLE_CHAT,
            displayName = "말풍선",
            category = EffectCategory.CAPTION_STYLE,
            parameters = listOf(sizeScaleParam, accentColorParam),
            previewHint = EffectDefinition.PreviewHint(
                emoji = "💬", tagLine = "말풍선 안에 담긴 자막",
            ),
        ),
        EffectDefinition(
            id = HIGHLIGHT_PEN,
            displayName = "형광펜",
            category = EffectCategory.CAPTION_STYLE,
            parameters = listOf(sizeScaleParam, accentColorParam, fillColorParam),
            previewHint = EffectDefinition.PreviewHint(
                emoji = "🖍️", tagLine = "형광펜 줄 친 강조 자막",
            ),
        ),
        EffectDefinition(
            id = RIBBON,
            displayName = "리본",
            category = EffectCategory.CAPTION_STYLE,
            parameters = listOf(sizeScaleParam, accentColorParam, fillColorParam),
            previewHint = EffectDefinition.PreviewHint(
                emoji = "🎀", tagLine = "리본 띠 위 자막",
            ),
        ),
        EffectDefinition(
            id = CARD_DROP,
            displayName = "카드 (그림자 부유감)",
            category = EffectCategory.CAPTION_STYLE,
            parameters = listOf(intensityParam, sizeScaleParam, accentColorParam, fillColorParam),
            previewHint = EffectDefinition.PreviewHint(
                emoji = "🪪", tagLine = "그림자로 떠 있는 카드 자막",
            ),
        ),
    )

    /**
     * 효과 id + 파라미터 값 + 채널 팔레트로 실제 [RichTextStyle] 을 만들어준다.
     * 알 수 없는 id 면 기본 자막 스타일(흰색 + 검은 외곽선) 반환.
     *
     * @param subtitleFontPackId 적용할 자막 폰트 팩 id. 보통 ThemePack.subtitleFontPackId.
     */
    fun buildStyle(
        id: String,
        params: EffectParamValues,
        palette: ColorPalette = StudioPopDefaultPalette,
        subtitleFontPackId: String = BuiltinFontPacks.SUBTITLE_NOTO_BOLD.id,
    ): RichTextStyle {
        val intensity = params.float("intensity", 1f).coerceIn(0f, 1.5f)
        val size = params.float("sizeScale", 1f).coerceIn(0.5f, 2f)
        val accent = params.color("accentColor", palette.accent)
        val fill = params.color("fillColor", 0xFFFFFFFF.toInt())

        return when (id) {
            GLOW_NEON -> RichTextStyle(
                fontPackId = subtitleFontPackId,
                fontWeight = 800,
                fillBrush = TextBrush.Solid(fill),
                strokes = listOf(TextStroke(color = 0xFF000000.toInt(), widthPx = 5f * size)),
                glow = TextGlow(color = accent, radiusPx = 28f * size, intensity = intensity),
                sizeScale = size,
                align = TextAlign.CENTER,
            )

            GRADIENT_POP -> RichTextStyle(
                fontPackId = subtitleFontPackId,
                fontWeight = 800,
                fillBrush = TextBrush.LinearGradient(
                    startColor = fill,
                    endColor = accent,
                    angleDegrees = 90f,
                ),
                strokes = listOf(TextStroke(color = 0xFF000000.toInt(), widthPx = 4f * size)),
                sizeScale = size,
                align = TextAlign.CENTER,
            )

            TRIPLE_STROKE -> RichTextStyle(
                fontPackId = subtitleFontPackId,
                fontWeight = 900,
                fillBrush = TextBrush.Solid(fill),
                strokes = listOf(
                    TextStroke(color = 0xFF000000.toInt(), widthPx = 8f * size),
                    TextStroke(color = accent, widthPx = 5f * size),
                    TextStroke(color = 0xFF000000.toInt(), widthPx = 2f * size),
                ),
                sizeScale = size,
                align = TextAlign.CENTER,
            )

            SHADOW_3D -> {
                // 한 색의 다중 그림자를 살짝씩 어긋나게 쌓아 두께감 표현.
                val depth = (8f * intensity).coerceAtLeast(2f)
                val shadows = (1..6).map { i ->
                    TextShadow(
                        color = accent,
                        offsetXPx = i * (depth / 6f),
                        offsetYPx = i * (depth / 6f),
                        blurPx = 0f,
                    )
                }
                RichTextStyle(
                    fontPackId = subtitleFontPackId,
                    fontWeight = 900,
                    fillBrush = TextBrush.Solid(fill),
                    strokes = listOf(TextStroke(color = 0xFF000000.toInt(), widthPx = 4f * size)),
                    shadows = shadows,
                    sizeScale = size,
                )
            }

            BUBBLE_CHAT -> RichTextStyle(
                fontPackId = subtitleFontPackId,
                fontWeight = 700,
                fillBrush = TextBrush.Solid(palette.textOnAccent),
                background = TextBackground.Bubble(
                    color = accent,
                    pointerSide = TextBackground.Bubble.PointerSide.BOTTOM_LEFT,
                ),
                sizeScale = size,
                align = TextAlign.CENTER,
            )

            HIGHLIGHT_PEN -> RichTextStyle(
                fontPackId = subtitleFontPackId,
                fontWeight = 700,
                fillBrush = TextBrush.Solid(fill),
                background = TextBackground.Highlight(color = accent),
                sizeScale = size,
                align = TextAlign.CENTER,
            )

            RIBBON -> RichTextStyle(
                fontPackId = subtitleFontPackId,
                fontWeight = 800,
                fillBrush = TextBrush.Solid(fill),
                background = TextBackground.Ribbon(color = accent),
                sizeScale = size,
                align = TextAlign.CENTER,
            )

            CARD_DROP -> RichTextStyle(
                fontPackId = subtitleFontPackId,
                fontWeight = 700,
                fillBrush = TextBrush.Solid(fill),
                background = TextBackground.Card(
                    color = palette.primary,
                    cornerRadiusPx = 16f,
                    shadow = TextShadow(
                        color = 0x66000000.toInt(),
                        offsetXPx = 0f,
                        offsetYPx = 6f * intensity,
                        blurPx = 18f * intensity,
                    ),
                ),
                sizeScale = size,
                align = TextAlign.CENTER,
            )

            else -> defaultStyle(subtitleFontPackId)
        }
    }

    private fun defaultStyle(fontPackId: String) = RichTextStyle(
        fontPackId = fontPackId,
        fontWeight = 700,
        fillBrush = TextBrush.Solid(0xFFFFFFFF.toInt()),
        strokes = listOf(TextStroke(color = 0xFF000000.toInt(), widthPx = 4f)),
        align = TextAlign.CENTER,
    )
}

/** 일괄 등록 진입점. */
fun EffectRegistry.registerCaptionStylePresets() {
    registerAll(CaptionStylePresets.DEFINITIONS)
}
