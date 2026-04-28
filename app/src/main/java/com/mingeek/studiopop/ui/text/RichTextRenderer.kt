package com.mingeek.studiopop.ui.text

import android.graphics.BlurMaskFilter
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import android.graphics.Shader
import android.graphics.Typeface
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.drawscope.drawIntoCanvas
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.sp
import com.mingeek.studiopop.data.text.RichTextStyle
import com.mingeek.studiopop.data.text.TextBackground
import com.mingeek.studiopop.data.text.TextBrush
import com.mingeek.studiopop.data.text.TextTransform
import com.mingeek.studiopop.data.text.WordTiming
import kotlin.math.cos
import kotlin.math.max
import kotlin.math.sin

/**
 * [RichTextStyle] 한 묶음을 Compose Canvas 에 그리는 단일 컴포저블.
 *
 * R2 에서 정의된 8 자막 스타일(Glow/Gradient/3D Shadow/Triple Stroke/Bubble/Highlight/Ribbon/
 * Card) 을 모두 처리한다. 그리기 순서:
 *  1. 배경 (Box/Bubble/Highlight/Ribbon/Card) — 텍스트 뒤
 *  2. 글로우 (있으면)
 *  3. 다중 그림자 (3D Shadow 처럼 여러 개)
 *  4. 다중 외곽선 (큰→작은 순으로 그려 안쪽이 위로)
 *  5. 채움 (단색 / Gradient / MultiStop Gradient)
 *
 * 컴포저블의 크기는 텍스트 + 효과 여백을 모두 포함해 자동 계산. 호출 측에서 외부 패딩을
 * 두려면 modifier 로 감싸면 됨.
 */
@Composable
fun RichTextRenderer(
    text: String,
    style: RichTextStyle,
    baseFontSizeSp: Float = 18f,
    modifier: Modifier = Modifier,
    /**
     * R5c3a: 카라오케 단어 단위 색 분기. words 가 비어 있거나 currentTimeMs == null 이면
     * 기존 단일색 페인팅 그대로. words 가 들어오면 currentTimeMs 기준으로 단어를 3 상태로
     * 분류해 색 분기:
     *  - 발화 완료 (endMs <= now)        → karaokeHighlightColor
     *  - 발화 중   (startMs <= now < endMs) → karaokeProgressColor
     *  - 미발화    (startMs > now)        → 기본 fillBrush 색
     */
    words: List<WordTiming> = emptyList(),
    currentTimeMs: Long? = null,
    karaokeHighlightColor: Int = 0xFFFFEB3B.toInt(),
    karaokeProgressColor: Int = 0xFFA6FF00.toInt(),
    /**
     * R6: fontPackId → Typeface 변환기. null 이면 시스템 기본 typeface (기존 동작 유지).
     * 호출 측이 [com.mingeek.studiopop.data.design.TypefaceLoader] 를 [com.mingeek.studiopop.data.design.DesignTokens.fontPack] 와
     * 함께 넘겨주면 테마별 폰트 변경이 시각적으로 적용된다.
     */
    typefaceProvider: ((fontPackId: String, weight: Int) -> android.graphics.Typeface)? = null,
) {
    if (text.isBlank()) return
    val density = LocalDensity.current
    val measured = remember(text, style, baseFontSizeSp, density, typefaceProvider) {
        measure(text, style, baseFontSizeSp, density, typefaceProvider)
    }
    val widthDp = with(density) { measured.canvasWidth.toDp() }
    val heightDp = with(density) { measured.canvasHeight.toDp() }

    val karaokeRanges = if (words.isNotEmpty() && currentTimeMs != null) {
        computeKaraokeRanges(measured.text, words, currentTimeMs)
    } else null

    Canvas(modifier = modifier.size(widthDp, heightDp)) {
        drawIntoCanvas { canvas ->
            drawRichText(
                canvas = canvas.nativeCanvas,
                m = measured,
                karaoke = karaokeRanges,
                karaokeHighlightColor = karaokeHighlightColor,
                karaokeProgressColor = karaokeProgressColor,
            )
        }
    }
}

/**
 * 카라오케용으로 measured.text 를 3 segment 로 나눔: 완료 / 진행 / 미진행.
 *
 * 텍스트를 공백 단위 토큰으로 한 번에 분해해 인덱스를 만들고, [words] 와 토큰을 i 번째 끼리
 * 1:1 매칭. 이 방식은 "같다 같다" 처럼 동일 단어가 반복되거나 토큰 사이 다중 공백/특수 공백이
 * 있어도 cursor 가 정확히 i 번째 토큰을 가리킨다 (indexOf-cursor 방식의 점프 버그 회피).
 *
 * words.size 가 토큰 수와 다르면 (STT 가 편집된 자막이거나 공백 분할이 다를 때) 작은 쪽까지만
 * 매칭. 어긋나는 부분은 진행 표시 안 함.
 */
private fun computeKaraokeRanges(
    fullText: String,
    words: List<WordTiming>,
    nowMs: Long,
): KaraokeRanges {
    val tokenRanges = tokenIndexRanges(fullText)
    val pairCount = minOf(tokenRanges.size, words.size)
    var doneEnd = 0
    var progressEnd = 0
    var foundProgress = false
    for (i in 0 until pairCount) {
        val (_, end) = tokenRanges[i]
        val w = words[i]
        when {
            w.endMs <= nowMs -> doneEnd = end
            w.startMs <= nowMs && nowMs < w.endMs -> {
                progressEnd = end
                foundProgress = true
            }
            else -> Unit
        }
    }
    if (!foundProgress) progressEnd = doneEnd
    return KaraokeRanges(
        doneEndIndex = doneEnd,
        progressEndIndex = progressEnd,
    )
}

/** 공백/탭/줄바꿈으로 토큰을 분리하고 (start, endExclusive) 인덱스 쌍을 반환. */
private fun tokenIndexRanges(text: String): List<Pair<Int, Int>> {
    val out = mutableListOf<Pair<Int, Int>>()
    var i = 0
    while (i < text.length) {
        // 공백 스킵
        while (i < text.length && text[i].isWhitespace()) i++
        if (i >= text.length) break
        val start = i
        while (i < text.length && !text[i].isWhitespace()) i++
        out += start to i
    }
    return out
}

private data class KaraokeRanges(
    val doneEndIndex: Int,
    val progressEndIndex: Int,
)

private data class Measured(
    val text: String,
    val style: RichTextStyle,
    val basePaint: Paint,
    val textWidth: Float,
    val ascent: Float,
    val descent: Float,
    val padLeft: Float,
    val padTop: Float,
    val padRight: Float,
    val padBottom: Float,
    /**
     * 미리 빌드된 페인트들 — 매 프레임 alloc 회피. 글로우/그림자/외곽선/채움이 모두 measure
     * 시점에 결정되므로 동일 (text, style, font size) 동안 재사용 안전. Paint 자체가 mutable
     * 이지만 drawText 만 호출 (color/maskFilter 등 변경 안 함) 이라 동시 호출 위험 없음.
     */
    val glowPaint: Paint?,
    val shadowPaints: List<Paint>,
    val strokePaintsSorted: List<Paint>,
    val fillPaint: Paint,
) {
    val canvasWidth: Float get() = padLeft + textWidth + padRight
    val canvasHeight: Float get() = padTop + ascent + descent + padBottom
    val originX: Float get() = padLeft
    val baselineY: Float get() = padTop + ascent
}

private fun measure(
    rawText: String,
    style: RichTextStyle,
    baseFontSizeSp: Float,
    density: Density,
    typefaceProvider: ((String, Int) -> Typeface)? = null,
): Measured {
    val text = applyTransform(rawText, style.transform)
    val sizePx = with(density) { (baseFontSizeSp * style.sizeScale).sp.toPx() }
    val resolvedTypeface = typefaceProvider?.invoke(style.fontPackId, style.fontWeight)
        ?: Typeface.create(
            Typeface.DEFAULT,
            if (style.fontWeight >= 700) Typeface.BOLD else Typeface.NORMAL,
        )
    val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        textSize = sizePx
        isAntiAlias = true
        isSubpixelText = true
        typeface = resolvedTypeface
        letterSpacing = style.letterSpacingEm
    }
    val width = paint.measureText(text)
    val ascent = -paint.ascent()
    val descent = paint.descent()

    // 효과 여백 계산 — 가장 큰 stroke/glow/shadow/배경 padding 의 max 가 사방 padding
    val strokeMax = style.strokes.maxOfOrNull { it.widthPx + it.blurPx } ?: 0f
    val glowMax = style.glow?.radiusPx ?: 0f
    val shadowMaxX = style.shadows.maxOfOrNull { it.offsetXPx + it.blurPx } ?: 0f
    val shadowMaxY = style.shadows.maxOfOrNull { it.offsetYPx + it.blurPx } ?: 0f
    val bgPad = backgroundPadding(style.background)

    val padX = max(strokeMax / 2f + glowMax, bgPad.x) + max(0f, shadowMaxX)
    val padY = max(strokeMax / 2f + glowMax, bgPad.y) + max(0f, shadowMaxY)

    // 페인트 캐시 빌드 — 매 frame 새로 만들지 않게.
    // apply 안에서 outer `style` (RichTextStyle) 와 Paint.style 이름 충돌이라 this.style 명시.
    val glowPaint = style.glow?.let { glow ->
        Paint(paint).apply {
            color = glow.color
            this.style = Paint.Style.FILL
            maskFilter = BlurMaskFilter(
                glow.radiusPx.coerceAtLeast(1f),
                BlurMaskFilter.Blur.NORMAL,
            )
        }
    }
    val shadowPaints = style.shadows.map { sh ->
        Paint(paint).apply {
            color = sh.color
            this.style = Paint.Style.FILL
            if (sh.blurPx > 0f) {
                maskFilter = BlurMaskFilter(sh.blurPx, BlurMaskFilter.Blur.NORMAL)
            }
        }
    }
    val strokePaintsSorted = style.strokes.sortedByDescending { it.widthPx }.map { st ->
        Paint(paint).apply {
            color = st.color
            this.style = Paint.Style.STROKE
            strokeWidth = st.widthPx
            strokeJoin = Paint.Join.ROUND
            strokeCap = Paint.Cap.ROUND
            if (st.blurPx > 0f) {
                maskFilter = BlurMaskFilter(st.blurPx, BlurMaskFilter.Blur.NORMAL)
            }
        }
    }
    val fillPaint = Paint(paint).apply {
        this.style = Paint.Style.FILL
        applyBrush(this, style.fillBrush, padX, padY + ascent, width, ascent + descent)
    }

    return Measured(
        text = text,
        style = style,
        basePaint = paint,
        textWidth = width,
        ascent = ascent,
        descent = descent,
        padLeft = padX,
        padTop = padY,
        padRight = padX,
        padBottom = padY,
        glowPaint = glowPaint,
        shadowPaints = shadowPaints,
        strokePaintsSorted = strokePaintsSorted,
        fillPaint = fillPaint,
    )
}

private data class Padding(val x: Float, val y: Float)

private fun backgroundPadding(bg: TextBackground): Padding = when (bg) {
    TextBackground.None -> Padding(8f, 4f)
    is TextBackground.Box -> Padding(bg.paddingHorizontalPx, bg.paddingVerticalPx)
    is TextBackground.Bubble -> Padding(28f, 18f)
    is TextBackground.Highlight -> Padding(6f, 4f)
    is TextBackground.Ribbon -> Padding(36f, 12f)
    is TextBackground.Card -> Padding(28f, 18f + bg.shadow.blurPx)
}

private fun drawRichText(
    canvas: android.graphics.Canvas,
    m: Measured,
    karaoke: KaraokeRanges? = null,
    karaokeHighlightColor: Int = 0xFFFFEB3B.toInt(),
    karaokeProgressColor: Int = 0xFFA6FF00.toInt(),
) {
    val originX = m.originX
    val baselineY = m.baselineY
    val width = m.textWidth
    val height = m.ascent + m.descent

    // 1) 배경
    drawBackground(canvas, m, originX, baselineY, width, height)

    // 2) 글로우 — 캐시된 paint
    m.glowPaint?.let { glowPaint ->
        val passes = if ((m.style.glow?.intensity ?: 1f) > 1f) 2 else 1
        repeat(passes) { canvas.drawText(m.text, originX, baselineY, glowPaint) }
    }

    // 3) 다중 그림자 — 캐시된 paint 들
    m.style.shadows.forEachIndexed { idx, sh ->
        val paint = m.shadowPaints[idx]
        canvas.drawText(m.text, originX + sh.offsetXPx, baselineY + sh.offsetYPx, paint)
    }

    // 4) 다중 외곽선 — 큰→작은 정렬된 캐시
    for (paint in m.strokePaintsSorted) {
        canvas.drawText(m.text, originX, baselineY, paint)
    }

    // 5) 채움 — 카라오케 모드면 segment 별로 다른 색 그리기, 아니면 단일 캐시 페인트.
    if (karaoke == null) {
        canvas.drawText(m.text, originX, baselineY, m.fillPaint)
    } else {
        drawKaraokeText(
            canvas = canvas,
            m = m,
            karaoke = karaoke,
            highlightColor = karaokeHighlightColor,
            progressColor = karaokeProgressColor,
        )
    }
}

/**
 * 카라오케 segment 별 색 페인팅. 글자 단위 width 누적으로 시작 X 좌표 계산.
 * 각 segment 는 같은 baseline + style.basePaint 위에 다른 색만 입혀 그린다.
 */
private fun drawKaraokeText(
    canvas: android.graphics.Canvas,
    m: Measured,
    karaoke: KaraokeRanges,
    highlightColor: Int,
    progressColor: Int,
) {
    val text = m.text
    val doneEnd = karaoke.doneEndIndex.coerceIn(0, text.length)
    val progressEnd = karaoke.progressEndIndex.coerceIn(doneEnd, text.length)

    fun paintFor(color: Int): Paint = Paint(m.basePaint).apply {
        style = Paint.Style.FILL
        this.color = color
    }

    // 채움 색 단색 추출 — fillBrush 가 그라디언트면 평균색이 어색하므로 흰색 fallback.
    val baseColor = (m.style.fillBrush as? TextBrush.Solid)?.color ?: 0xFFFFFFFF.toInt()

    val donePaint = paintFor(highlightColor)
    val progressPaint = paintFor(progressColor)
    val pendingPaint = paintFor(baseColor)

    val cumWidths = FloatArray(text.length + 1)
    for (i in 1..text.length) {
        cumWidths[i] = cumWidths[i - 1] + m.basePaint.measureText(text, i - 1, i)
    }

    fun drawSegment(start: Int, end: Int, paint: Paint) {
        if (start >= end) return
        val seg = text.substring(start, end)
        canvas.drawText(seg, m.originX + cumWidths[start], m.baselineY, paint)
    }

    drawSegment(0, doneEnd, donePaint)
    drawSegment(doneEnd, progressEnd, progressPaint)
    drawSegment(progressEnd, text.length, pendingPaint)
}

private fun applyBrush(
    paint: Paint, brush: TextBrush,
    x: Float, baselineY: Float, width: Float, height: Float,
) {
    when (brush) {
        is TextBrush.Solid -> paint.color = brush.color
        is TextBrush.LinearGradient -> {
            val rad = Math.toRadians(brush.angleDegrees.toDouble())
            val dx = (cos(rad) * width).toFloat()
            val dy = (sin(rad) * height).toFloat()
            paint.shader = LinearGradient(
                x, baselineY - height, x + dx, baselineY - height + dy,
                brush.startColor, brush.endColor, Shader.TileMode.CLAMP,
            )
        }
        is TextBrush.MultiStopGradient -> {
            val rad = Math.toRadians(brush.angleDegrees.toDouble())
            val dx = (cos(rad) * width).toFloat()
            val dy = (sin(rad) * height).toFloat()
            paint.shader = LinearGradient(
                x, baselineY - height, x + dx, baselineY - height + dy,
                brush.stops.map { it.color }.toIntArray(),
                brush.stops.map { it.position }.toFloatArray(),
                Shader.TileMode.CLAMP,
            )
        }
    }
}

private fun drawBackground(
    canvas: android.graphics.Canvas, m: Measured,
    x: Float, baselineY: Float, width: Float, height: Float,
) {
    when (val bg = m.style.background) {
        TextBackground.None -> Unit
        is TextBackground.Box -> {
            val rect = RectF(
                x - bg.paddingHorizontalPx,
                baselineY - m.ascent - bg.paddingVerticalPx,
                x + width + bg.paddingHorizontalPx,
                baselineY + m.descent + bg.paddingVerticalPx,
            )
            val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = bg.color }
            canvas.drawRoundRect(rect, bg.cornerRadiusPx, bg.cornerRadiusPx, paint)
        }
        is TextBackground.Bubble -> {
            val padH = 24f
            val padV = 12f
            val rect = RectF(
                x - padH, baselineY - m.ascent - padV,
                x + width + padH, baselineY + m.descent + padV,
            )
            val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = bg.color }
            canvas.drawRoundRect(rect, bg.cornerRadiusPx, bg.cornerRadiusPx, paint)
            val tail = Path()
            val tailSize = 20f
            val (cx, baseY) = when (bg.pointerSide) {
                TextBackground.Bubble.PointerSide.BOTTOM_LEFT ->
                    rect.left + 32f to rect.bottom
                TextBackground.Bubble.PointerSide.BOTTOM_RIGHT ->
                    rect.right - 32f to rect.bottom
                TextBackground.Bubble.PointerSide.TOP_LEFT ->
                    rect.left + 32f to rect.top
                TextBackground.Bubble.PointerSide.TOP_RIGHT ->
                    rect.right - 32f to rect.top
            }
            val flip = if (bg.pointerSide.name.startsWith("TOP")) -1f else 1f
            tail.moveTo(cx - tailSize / 2f, baseY)
            tail.lineTo(cx, baseY + tailSize * flip)
            tail.lineTo(cx + tailSize / 2f, baseY)
            tail.close()
            canvas.drawPath(tail, paint)
        }
        is TextBackground.Highlight -> {
            val h = (m.ascent + m.descent) * bg.heightRatio
            val offsetY = (m.ascent + m.descent) * bg.offsetYRatio
            val rect = RectF(
                x - 4f,
                baselineY - h + offsetY,
                x + width + 4f,
                baselineY + offsetY,
            )
            val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = bg.color }
            canvas.drawRect(rect, paint)
        }
        is TextBackground.Ribbon -> {
            val padH = 28f
            val padV = 8f
            val left = x - padH
            val right = x + width + padH
            val top = baselineY - m.ascent - padV
            val bottom = baselineY + m.descent + padV
            val midY = (top + bottom) / 2f
            val tail = bg.tailLength
            val path = Path().apply {
                moveTo(left, top)
                lineTo(right, top)
                lineTo(right + tail, midY)
                lineTo(right, bottom)
                lineTo(left, bottom)
                lineTo(left - tail, midY)
                close()
            }
            val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = bg.color }
            canvas.drawPath(path, paint)
        }
        is TextBackground.Card -> {
            val padH = 24f
            val padV = 14f
            val rect = RectF(
                x - padH, baselineY - m.ascent - padV,
                x + width + padH, baselineY + m.descent + padV,
            )
            val shadow = bg.shadow
            val shadowPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                color = shadow.color
                if (shadow.blurPx > 0f) {
                    maskFilter = BlurMaskFilter(shadow.blurPx, BlurMaskFilter.Blur.NORMAL)
                }
            }
            val shadowRect = RectF(rect).apply {
                offset(shadow.offsetXPx, shadow.offsetYPx)
            }
            canvas.drawRoundRect(
                shadowRect, bg.cornerRadiusPx, bg.cornerRadiusPx, shadowPaint,
            )
            val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = bg.color }
            canvas.drawRoundRect(rect, bg.cornerRadiusPx, bg.cornerRadiusPx, paint)
        }
    }
}

private fun applyTransform(text: String, transform: TextTransform): String = when (transform) {
    TextTransform.NONE -> text
    TextTransform.UPPERCASE -> text.uppercase()
    TextTransform.LOWERCASE -> text.lowercase()
    TextTransform.KOREAN_BOLD_FORM -> text
}
