package com.mingeek.studiopop.data.ai

import android.graphics.Bitmap
import android.graphics.Color
import kotlin.math.max
import kotlin.math.min

/**
 * 키프레임 비트맵들로부터 영상 톤을 추정. 평균 RGB · 표준편차 기반의 빠른 휴리스틱이며, 정밀한
 * 컬러 그레이딩이 아닌 "이 영상이 어두운지 밝은지, 채도가 높은지 낮은지, 따뜻한지 차가운지" 같은
 * 4축의 대략적 위치만 판정한다 (LUT 추천 등 굵은 결정에 충분).
 *
 * 출력 [ToneEstimate] 의 각 축은 -1..+1 정규화 — 0 이 중간, +1 이 강한 양의 톤.
 *
 * 알고리즘:
 *  - brightness: 평균 luminance Y' = 0.299R + 0.587G + 0.114B → 0..255 → -1..1
 *  - saturation: 픽셀별 max - min(RGB) 평균 → 0..255 → -1..1
 *  - contrast: luminance 의 표준편차 → 0..128 → -1..1 (큰 std = 고대비)
 *  - warmth: (avgR - avgB) / 128 → -1..1 (R 우세 → +)
 */
object ToneEstimator {

    /**
     * 입력 [bitmaps] 들의 톤을 평균해 단일 [ToneEstimate] 반환.
     * 각 비트맵은 다운샘플(샘플링 step) 해서 비싼 픽셀 순회 비용을 줄인다.
     *
     * @param sampleStep 픽셀 샘플링 간격. 1=모든 픽셀, 4=4픽셀당 1개. 기본 4 (1280×720 → 약 5만
     *                   샘플로 빠른 통계).
     */
    fun estimate(bitmaps: List<Bitmap>, sampleStep: Int = 4): ToneEstimate {
        if (bitmaps.isEmpty()) {
            return ToneEstimate(brightness = 0f, saturation = 0f, contrast = 0f, warmth = 0f)
        }
        var sumR = 0.0; var sumG = 0.0; var sumB = 0.0
        var sumLum = 0.0; var sumLumSq = 0.0
        var sumSat = 0.0
        var count = 0L

        for (bmp in bitmaps) {
            val w = bmp.width
            val h = bmp.height
            val pixels = IntArray(w * h)
            bmp.getPixels(pixels, 0, w, 0, 0, w, h)
            var i = 0
            while (i < pixels.size) {
                val px = pixels[i]
                val r = Color.red(px)
                val g = Color.green(px)
                val b = Color.blue(px)
                val lum = 0.299 * r + 0.587 * g + 0.114 * b
                val sat = max(r, max(g, b)) - min(r, min(g, b))
                sumR += r; sumG += g; sumB += b
                sumLum += lum; sumLumSq += lum * lum
                sumSat += sat
                count++
                i += sampleStep
            }
        }
        if (count == 0L) {
            return ToneEstimate(brightness = 0f, saturation = 0f, contrast = 0f, warmth = 0f)
        }
        val avgR = sumR / count
        val avgG = sumG / count
        val avgB = sumB / count
        val avgLum = sumLum / count
        val avgSat = sumSat / count
        val variance = (sumLumSq / count) - (avgLum * avgLum)
        val std = if (variance > 0.0) kotlin.math.sqrt(variance) else 0.0

        return ToneEstimate(
            brightness = ((avgLum / 127.5) - 1.0).toFloat().coerceIn(-1f, 1f),
            saturation = ((avgSat / 127.5) - 1.0).toFloat().coerceIn(-1f, 1f),
            contrast = ((std / 64.0) - 1.0).toFloat().coerceIn(-1f, 1f),
            warmth = ((avgR - avgB) / 128.0).toFloat().coerceIn(-1f, 1f),
        )
    }
}
