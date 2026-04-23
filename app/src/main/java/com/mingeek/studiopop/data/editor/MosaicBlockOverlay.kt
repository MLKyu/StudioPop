package com.mingeek.studiopop.data.editor

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import androidx.media3.common.util.UnstableApi
import androidx.media3.effect.BitmapOverlay
import androidx.media3.effect.OverlaySettings

/**
 * 한 [MosaicRegion] 을 Media3 파이프라인에서 그리는 오버레이.
 *
 * 구현 전략:
 * - 회색톤 노이즈 격자 비트맵을 한 번 만들어 "모자이크 패치" 로 사용
 * - 매 프레임 keyframe 을 선형 보간해 (cx, cy, w, h) 를 계산
 * - setBackgroundFrameAnchor 로 rect 중심을 프레임 NDC 에 배치
 * - setScale 로 rect 크기에 맞춰 비트맵을 늘림
 * - 활성 구간 밖이면 alpha 0
 *
 * 정확한 프레임 픽셀 평균 기반 픽셀화는 GlEffect 레벨 프레임 처리가 필요해 Media3 의
 * [BitmapOverlay] 만으론 구현 불가 — 현실적인 수준에서 "얼굴을 식별 불가능하게 가리는"
 * 시각적 효과에 집중.
 */
@UnstableApi
class MosaicBlockOverlay(
    private val region: MosaicRegion,
    private val activeWindowsMs: List<LongRange>,
    private val sourceStartMs: Long,
) : BitmapOverlay() {

    private val bitmap: Bitmap by lazy { buildPatternBitmap(region.blockSizePx) }

    override fun getBitmap(presentationTimeUs: Long): Bitmap = bitmap

    override fun getOverlaySettings(presentationTimeUs: Long): OverlaySettings {
        val ms = presentationTimeUs / 1000
        val activeWindow = activeWindowsMs.firstOrNull { ms in it }
        val alpha = if (activeWindow != null) 1f else 0f
        val sourceMs = if (activeWindow != null) {
            sourceStartMs + (ms - activeWindow.first)
        } else {
            sourceStartMs
        }
        val kf = interpolate(sourceMs)
        return OverlaySettings.Builder()
            .setBackgroundFrameAnchor(kf.cx.coerceIn(-1f, 1f), kf.cy.coerceIn(-1f, 1f))
            // NDC 기준 w,h (0..2) → FadeOverlay 와 동일하게 큰 scale 로 변환.
            // 비트맵 네이티브는 [PATTERN_PX] px, 출력 frame 은 런타임 가변이라 정확도는 근사.
            // 경험적 scale factor: rect 의 NDC 크기(절반폭) × SCALE_FACTOR.
            .setScale(
                (kf.w.coerceIn(0f, 2f) * SCALE_FACTOR),
                (kf.h.coerceIn(0f, 2f) * SCALE_FACTOR),
            )
            .setAlphaScale(alpha)
            .build()
    }

    /**
     * 키프레임 보간. 리스트가 비어있으면 전체 화면 덮개(cx=0,cy=0,w=0,h=0) 반환.
     */
    private fun interpolate(sourceMs: Long): MosaicKeyframe {
        val kfs = region.keyframes
        if (kfs.isEmpty()) return MosaicKeyframe(0L, 0f, 0f, 0f, 0f)
        val sorted = kfs.sortedBy { it.sourceTimeMs }
        if (sourceMs <= sorted.first().sourceTimeMs) return sorted.first()
        if (sourceMs >= sorted.last().sourceTimeMs) return sorted.last()
        var prev = sorted.first()
        for (i in 1 until sorted.size) {
            val next = sorted[i]
            if (sourceMs in prev.sourceTimeMs..next.sourceTimeMs) {
                val span = (next.sourceTimeMs - prev.sourceTimeMs).coerceAtLeast(1L)
                val t = (sourceMs - prev.sourceTimeMs).toFloat() / span.toFloat()
                return MosaicKeyframe(
                    sourceTimeMs = sourceMs,
                    cx = lerp(prev.cx, next.cx, t),
                    cy = lerp(prev.cy, next.cy, t),
                    w = lerp(prev.w, next.w, t),
                    h = lerp(prev.h, next.h, t),
                )
            }
            prev = next
        }
        return sorted.last()
    }

    private fun lerp(a: Float, b: Float, t: Float): Float = a + (b - a) * t

    private fun buildPatternBitmap(blockPx: Int): Bitmap {
        val bmp = Bitmap.createBitmap(PATTERN_PX, PATTERN_PX, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bmp)
        val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.FILL }
        val block = blockPx.coerceAtLeast(6)
        var seed = 1234
        var y = 0
        while (y < PATTERN_PX) {
            var x = 0
            while (x < PATTERN_PX) {
                seed = (seed * 1103515245 + 12345) and 0x7FFFFFFF
                val gray = 60 + (seed ushr 16) % 140
                paint.color = Color.rgb(gray, gray, gray)
                canvas.drawRect(
                    x.toFloat(), y.toFloat(),
                    (x + block).toFloat(), (y + block).toFloat(),
                    paint,
                )
                x += block
            }
            y += block
        }
        return bmp
    }

    companion object {
        private const val PATTERN_PX = 256

        /**
         * setScale 경험값. 비트맵 native 크기(256px) 를 프레임 NDC w (0..2) 에 맞추기 위한 배수.
         * 1080p 프레임(약 1920x1080) 기준 1920/256 = 7.5. 9:16 세로 에서도 1080/256 ≈ 4.2 라
         * 비율별 편차는 있지만 MVP 단계에선 감수.
         */
        private const val SCALE_FACTOR = 4f
    }
}
