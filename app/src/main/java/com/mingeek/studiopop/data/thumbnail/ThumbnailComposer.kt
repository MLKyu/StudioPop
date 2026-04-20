package com.mingeek.studiopop.data.thumbnail

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.Shader
import android.graphics.Typeface
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileOutputStream

class ThumbnailComposer {

    data class Spec(
        val mainText: String,
        val subText: String = "",
        val mainColor: Int = Color.WHITE,
        val subColor: Int = Color.WHITE,
        val outlineColor: Int = Color.BLACK,
        val gradientOverlay: Boolean = true,
    )

    /**
     * 소스 Bitmap 을 1280x720 으로 리사이즈하고 텍스트/그라데이션을 올린 새 Bitmap 을 만든다.
     */
    suspend fun compose(source: Bitmap, spec: Spec): Bitmap = withContext(Dispatchers.Default) {
        val target = Bitmap.createScaledBitmap(source, WIDTH, HEIGHT, true)
        val mutable = target.copy(Bitmap.Config.ARGB_8888, true)
        val canvas = Canvas(mutable)

        if (spec.gradientOverlay) {
            drawBottomGradient(canvas)
        }

        drawMainText(canvas, spec)
        drawSubText(canvas, spec)

        mutable
    }

    suspend fun saveAsPng(bitmap: Bitmap, outFile: File): File = withContext(Dispatchers.IO) {
        FileOutputStream(outFile).use { out ->
            bitmap.compress(Bitmap.CompressFormat.PNG, 100, out)
        }
        outFile
    }

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

    private fun drawMainText(canvas: Canvas, spec: Spec) {
        if (spec.mainText.isBlank()) return
        val fillPaint = textPaint(
            size = MAIN_TEXT_SIZE_PX,
            color = spec.mainColor,
            bold = true,
            stroke = false,
        )
        val strokePaint = textPaint(
            size = MAIN_TEXT_SIZE_PX,
            color = spec.outlineColor,
            bold = true,
            stroke = true,
        )
        val lines = wrapLines(spec.mainText, fillPaint, WIDTH - 2 * MARGIN_X)
        val totalH = lines.size * MAIN_TEXT_SIZE_PX * 1.1f
        var y = HEIGHT - MARGIN_Y - totalH + MAIN_TEXT_SIZE_PX
        for (line in lines) {
            val tw = fillPaint.measureText(line)
            val x = (WIDTH - tw) / 2f
            canvas.drawText(line, x, y, strokePaint)
            canvas.drawText(line, x, y, fillPaint)
            y += MAIN_TEXT_SIZE_PX * 1.1f
        }
    }

    private fun drawSubText(canvas: Canvas, spec: Spec) {
        if (spec.subText.isBlank()) return
        val paint = textPaint(
            size = SUB_TEXT_SIZE_PX,
            color = spec.subColor,
            bold = false,
            stroke = false,
        )
        val strokePaint = textPaint(
            size = SUB_TEXT_SIZE_PX,
            color = spec.outlineColor,
            bold = false,
            stroke = true,
        )
        val tw = paint.measureText(spec.subText)
        val x = (WIDTH - tw) / 2f
        val y = MARGIN_Y + SUB_TEXT_SIZE_PX
        canvas.drawText(spec.subText, x, y, strokePaint)
        canvas.drawText(spec.subText, x, y, paint)
    }

    private fun textPaint(size: Float, color: Int, bold: Boolean, stroke: Boolean): Paint {
        return Paint(Paint.ANTI_ALIAS_FLAG).apply {
            textSize = size
            this.color = color
            typeface = Typeface.create(Typeface.DEFAULT, if (bold) Typeface.BOLD else Typeface.NORMAL)
            if (stroke) {
                style = Paint.Style.STROKE
                strokeWidth = size * 0.12f
            }
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
        // 한글 등 공백이 없는 긴 문장은 그대로 한 줄로
        if (result.isEmpty()) result += text
        return result
    }

    companion object {
        const val WIDTH = 1280
        const val HEIGHT = 720
        private const val MAIN_TEXT_SIZE_PX = 96f
        private const val SUB_TEXT_SIZE_PX = 48f
        private const val MARGIN_X = 40f
        private const val MARGIN_Y = 40f
    }
}
