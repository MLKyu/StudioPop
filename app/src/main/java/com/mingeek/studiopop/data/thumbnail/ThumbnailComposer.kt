package com.mingeek.studiopop.data.thumbnail

import android.graphics.Bitmap
import android.graphics.BlurMaskFilter
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import android.graphics.Shader
import android.graphics.Typeface
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileOutputStream

/**
 * 1280×720 썸네일 합성기. [ThumbnailVariant] 한 개 → Bitmap 한 장.
 *
 * 처리 순서:
 * 1) source 를 (variant.subjectCrop 적용 후) 1280×720 으로 스케일
 * 2) subject 강조 (BORDER/GLOW)
 * 3) 하단 그라데이션
 * 4) 데코(BOX/OUTLINE/ARROW/GLOW) + 텍스트
 */
class ThumbnailComposer {

    suspend fun compose(source: Bitmap, variant: ThumbnailVariant): Bitmap =
        withContext(Dispatchers.Default) {
            val cropped = applyCrop(source, variant)
            val target = Bitmap.createScaledBitmap(cropped, WIDTH, HEIGHT, true)
            val mutable = target.copy(Bitmap.Config.ARGB_8888, true)
            // 중간 Bitmap 은 즉시 해제해 힙 압력 완화 (minSdk 24 에선 pixel data 가 Java heap)
            if (target !== mutable) target.recycle()
            if (cropped !== source && cropped !== target) cropped.recycle()
            val canvas = Canvas(mutable)

            drawSubjectEmphasis(canvas, source, variant)
            drawBottomGradient(canvas)
            drawMainBlock(canvas, variant)
            drawSubText(canvas, variant)

            mutable
        }

    suspend fun saveAsPng(bitmap: Bitmap, outFile: File): File = withContext(Dispatchers.IO) {
        FileOutputStream(outFile).use { out ->
            bitmap.compress(Bitmap.CompressFormat.PNG, 100, out)
        }
        outFile
    }

    // --- 크롭/스케일 ---------------------------------------------------------

    private fun applyCrop(source: Bitmap, variant: ThumbnailVariant): Bitmap {
        val crop = variant.subjectCrop ?: return source
        val padded = expandCropTo16x9(crop, source.width, source.height)
        return Bitmap.createBitmap(source, padded.left, padded.top, padded.width(), padded.height())
    }

    /** 얼굴 박스를 16:9 비율로 확장하면서 source 경계 안에 클램프. */
    private fun expandCropTo16x9(crop: android.graphics.Rect, srcW: Int, srcH: Int): android.graphics.Rect {
        val ratio = WIDTH.toFloat() / HEIGHT
        val cx = (crop.left + crop.right) / 2f
        val cy = (crop.top + crop.bottom) / 2f
        val baseW = crop.width().coerceAtLeast(1)
        val baseH = crop.height().coerceAtLeast(1)
        // 얼굴이 너무 크게 잡히는 걸 방지하려고 약간 더 넓혀줌 (1.6배)
        val targetH = (maxOf(baseH, (baseW / ratio).toInt()) * 1.6f).toInt()
        val targetW = (targetH * ratio).toInt()
        var left = (cx - targetW / 2f).toInt().coerceAtLeast(0)
        var top = (cy - targetH / 2f).toInt().coerceAtLeast(0)
        var right = (left + targetW).coerceAtMost(srcW)
        var bottom = (top + targetH).coerceAtMost(srcH)
        // 경계에 부딪힌 만큼 반대쪽으로 밀기
        if (right - left < targetW) left = (right - targetW).coerceAtLeast(0)
        if (bottom - top < targetH) top = (bottom - targetH).coerceAtLeast(0)
        right = (left + targetW).coerceAtMost(srcW)
        bottom = (top + targetH).coerceAtMost(srcH)
        return android.graphics.Rect(left, top, right, bottom)
    }

    // --- subject 강조 ------------------------------------------------------

    /** 크롭 후의 좌표계에서 얼굴 위치를 다시 그려 강조. */
    private fun drawSubjectEmphasis(
        canvas: Canvas,
        source: Bitmap,
        variant: ThumbnailVariant,
    ) {
        if (variant.subjectEmphasis == SubjectEmphasis.NONE) return
        val originalCrop = variant.subjectCrop ?: return
        // source-좌표 얼굴 박스 → padded(=cropped 영역) 좌표 → 1280×720 좌표
        val padded = expandCropTo16x9(originalCrop, source.width, source.height)
        val faceInCrop = RectF(
            (originalCrop.left - padded.left).toFloat(),
            (originalCrop.top - padded.top).toFloat(),
            (originalCrop.right - padded.left).toFloat(),
            (originalCrop.bottom - padded.top).toFloat(),
        )
        val sx = WIDTH.toFloat() / padded.width()
        val sy = HEIGHT.toFloat() / padded.height()
        val faceOnTarget = RectF(
            faceInCrop.left * sx,
            faceInCrop.top * sy,
            faceInCrop.right * sx,
            faceInCrop.bottom * sy,
        )

        when (variant.subjectEmphasis) {
            SubjectEmphasis.BORDER -> {
                val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                    style = Paint.Style.STROKE
                    strokeWidth = 8f
                    color = Color.parseColor("#FF1744")
                }
                canvas.drawRoundRect(faceOnTarget, 12f, 12f, paint)
            }
            SubjectEmphasis.GLOW -> {
                val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                    style = Paint.Style.STROKE
                    strokeWidth = 24f
                    color = variant.accentColor
                    maskFilter = BlurMaskFilter(20f, BlurMaskFilter.Blur.NORMAL)
                }
                canvas.drawRoundRect(faceOnTarget, 24f, 24f, paint)
            }
            SubjectEmphasis.ZOOM_PUNCH, SubjectEmphasis.NONE -> Unit
        }
    }

    // --- 배경 그라데이션 ---------------------------------------------------

    private fun drawBottomGradient(canvas: Canvas) {
        val paint = Paint().apply {
            shader = LinearGradient(
                0f, HEIGHT * 0.55f,
                0f, HEIGHT.toFloat(),
                intArrayOf(Color.TRANSPARENT, Color.argb(200, 0, 0, 0)),
                null,
                Shader.TileMode.CLAMP,
            )
        }
        canvas.drawRect(0f, HEIGHT * 0.55f, WIDTH.toFloat(), HEIGHT.toFloat(), paint)
    }

    // --- 메인 텍스트 + 데코 -----------------------------------------------

    private fun drawMainBlock(canvas: Canvas, v: ThumbnailVariant) {
        if (v.mainText.isBlank()) return
        val textSize = MAIN_TEXT_SIZE_PX * v.sizeScale
        val fillPaint = textPaint(textSize, v.mainColor, bold = true, stroke = false)
        val strokePaint = textPaint(textSize, Color.BLACK, bold = true, stroke = true)
        val maxWidth = WIDTH - 2 * MARGIN_X
        val lines = wrapLines(v.mainText, fillPaint, maxWidth)
        val lineH = textSize * 1.1f
        val totalH = lines.size * lineH

        // anchor 별 시작 좌표 계산
        val (originX, originY) = computeAnchor(v.anchor, totalH, lines, fillPaint)

        // 데코 먼저 (텍스트 뒤에 그릴 박스/글로우)
        when (v.decoration) {
            DecorationStyle.BOX -> drawTextBox(canvas, lines, fillPaint, originX, originY, lineH, v.accentColor)
            DecorationStyle.GLOW -> drawTextGlow(canvas, lines, fillPaint, originX, originY, lineH, v.accentColor)
            DecorationStyle.ARROW -> drawArrowMarkers(canvas, lines, fillPaint, originX, originY, lineH, v.accentColor)
            DecorationStyle.OUTLINE, DecorationStyle.NONE -> Unit
        }

        // 텍스트 본체 (OUTLINE 데코면 더 굵게)
        val effectiveStroke = if (v.decoration == DecorationStyle.OUTLINE) {
            textPaint(textSize, v.accentColor, bold = true, stroke = true).apply {
                strokeWidth = textSize * 0.22f
            }
        } else strokePaint
        var y = originY
        for (line in lines) {
            val tw = fillPaint.measureText(line)
            val x = lineXForAnchor(v.anchor, tw, originX)
            canvas.drawText(line, x, y, effectiveStroke)
            canvas.drawText(line, x, y, fillPaint)
            y += lineH
        }
    }

    private fun computeAnchor(
        anchor: TextAnchor,
        totalH: Float,
        lines: List<String>,
        paint: Paint,
    ): Pair<Float, Float> {
        // baseline 기준 첫 줄 y. originX 는 영역의 left 기준.
        val widestW = lines.maxOf { paint.measureText(it) }
        val textSize = paint.textSize
        return when (anchor) {
            TextAnchor.TOP_LEFT -> MARGIN_X to (MARGIN_Y + textSize)
            TextAnchor.TOP_RIGHT -> (WIDTH - MARGIN_X - widestW) to (MARGIN_Y + textSize)
            TextAnchor.BOTTOM_LEFT -> MARGIN_X to (HEIGHT - MARGIN_Y - totalH + textSize)
            TextAnchor.BOTTOM_RIGHT -> (WIDTH - MARGIN_X - widestW) to (HEIGHT - MARGIN_Y - totalH + textSize)
            TextAnchor.CENTER -> ((WIDTH - widestW) / 2f) to ((HEIGHT - totalH) / 2f + textSize)
        }
    }

    private fun lineXForAnchor(anchor: TextAnchor, lineWidth: Float, originX: Float): Float = when (anchor) {
        TextAnchor.TOP_LEFT, TextAnchor.BOTTOM_LEFT -> originX
        TextAnchor.TOP_RIGHT, TextAnchor.BOTTOM_RIGHT -> originX // already right-aligned via originX
        TextAnchor.CENTER -> (WIDTH - lineWidth) / 2f
    }

    private fun drawTextBox(
        canvas: Canvas, lines: List<String>, paint: Paint,
        originX: Float, originY: Float, lineH: Float, color: Int,
    ) {
        val boxPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { this.color = color }
        var y = originY
        for (line in lines) {
            val tw = paint.measureText(line)
            val pad = paint.textSize * 0.15f
            val left = (originX - pad).coerceAtLeast(0f)
            val top = (y - paint.textSize - pad * 0.4f)
            val right = left + tw + pad * 2
            val bottom = y + pad * 0.4f
            canvas.drawRoundRect(RectF(left, top, right, bottom), 8f, 8f, boxPaint)
            y += lineH
        }
    }

    private fun drawTextGlow(
        canvas: Canvas, lines: List<String>, paint: Paint,
        originX: Float, originY: Float, lineH: Float, color: Int,
    ) {
        val glow = Paint(paint).apply {
            this.color = color
            maskFilter = BlurMaskFilter(28f, BlurMaskFilter.Blur.NORMAL)
            style = Paint.Style.FILL
        }
        var y = originY
        for (line in lines) {
            val tw = paint.measureText(line)
            val x = if (originX >= WIDTH - MARGIN_X - tw - 1f) WIDTH - MARGIN_X - tw else originX
            canvas.drawText(line, x, y, glow)
            y += lineH
        }
    }

    private fun drawArrowMarkers(
        canvas: Canvas, lines: List<String>, paint: Paint,
        originX: Float, originY: Float, lineH: Float, color: Int,
    ) {
        val arrowPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            this.color = color
            style = Paint.Style.FILL
        }
        // 첫 줄 옆에 화살표 한 개만 (왼쪽이면 오른쪽 가리킴)
        val firstLine = lines.firstOrNull() ?: return
        val tw = paint.measureText(firstLine)
        val midY = originY - paint.textSize * 0.4f
        val size = paint.textSize * 0.7f
        val leftAnchored = originX <= WIDTH / 2f
        val path = Path()
        if (leftAnchored) {
            // 왼쪽 텍스트 → 오른쪽으로 화살표 (텍스트 뒤쪽 ▶)
            val ax = originX + tw + 16f
            path.moveTo(ax, midY - size / 2f)
            path.lineTo(ax + size, midY)
            path.lineTo(ax, midY + size / 2f)
        } else {
            // 오른쪽 텍스트 → 왼쪽으로 화살표 ◀
            val ax = originX - 16f
            path.moveTo(ax, midY - size / 2f)
            path.lineTo(ax - size, midY)
            path.lineTo(ax, midY + size / 2f)
        }
        path.close()
        canvas.drawPath(path, arrowPaint)
    }

    private fun drawSubText(canvas: Canvas, v: ThumbnailVariant) {
        if (v.subText.isBlank()) return
        val paint = textPaint(SUB_TEXT_SIZE_PX, v.accentColor, bold = false, stroke = false)
        val strokePaint = textPaint(SUB_TEXT_SIZE_PX, Color.BLACK, bold = false, stroke = true)
        val tw = paint.measureText(v.subText)
        val x = (WIDTH - tw) / 2f
        // 메인 anchor 와 겹치지 않게: BOTTOM 계열 → 상단, 그 외 → 하단
        val y = when (v.anchor) {
            TextAnchor.BOTTOM_LEFT, TextAnchor.BOTTOM_RIGHT -> MARGIN_Y + SUB_TEXT_SIZE_PX
            else -> HEIGHT - MARGIN_Y
        }
        canvas.drawText(v.subText, x, y, strokePaint)
        canvas.drawText(v.subText, x, y, paint)
    }

    private fun textPaint(size: Float, color: Int, bold: Boolean, stroke: Boolean): Paint =
        Paint(Paint.ANTI_ALIAS_FLAG).apply {
            textSize = size
            this.color = color
            typeface = Typeface.create(Typeface.DEFAULT, if (bold) Typeface.BOLD else Typeface.NORMAL)
            if (stroke) {
                style = Paint.Style.STROKE
                strokeWidth = size * 0.12f
            }
        }

    private fun wrapLines(text: String, paint: Paint, maxWidth: Float): List<String> {
        val result = mutableListOf<String>()
        val words = text.split(' ')
        var current = StringBuilder()
        for (w in words) {
            val candidate = if (current.isEmpty()) w else "$current $w"
            if (paint.measureText(candidate) > maxWidth && current.isNotEmpty()) {
                result += current.toString()
                current = StringBuilder(w)
            } else {
                current = StringBuilder(candidate)
            }
        }
        if (current.isNotEmpty()) result += current.toString()
        if (result.isEmpty()) result += text
        return result
    }

    companion object {
        const val WIDTH = 1280
        const val HEIGHT = 720
        private const val MAIN_TEXT_SIZE_PX = 110f
        private const val SUB_TEXT_SIZE_PX = 48f
        private const val MARGIN_X = 48f
        private const val MARGIN_Y = 48f
    }
}
