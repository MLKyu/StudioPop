package com.mingeek.studiopop.ui.editor.components

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import kotlin.math.abs

/**
 * 프리뷰 위에 얹는 전환(fade-to-black) 오버레이.
 * [boundariesMs] 각 경계의 ±[halfDurationMs] 범위에서 알파가 0→1→0 으로 변함.
 * Media3 의 [com.mingeek.studiopop.data.editor.FadeAtBoundariesOverlay] 와 동일한 공식 —
 * export 결과와 시각적으로 일치하게 프리뷰에서도 확인 가능.
 */
@Composable
fun PreviewTransitionOverlay(
    boundariesMs: List<Long>,
    currentOutputMs: Long,
    halfDurationMs: Long,
    peakAlpha: Float,
    modifier: Modifier = Modifier,
) {
    if (boundariesMs.isEmpty() || halfDurationMs <= 0L || peakAlpha <= 0f) return
    val closestDist = boundariesMs.minOf { abs(it - currentOutputMs) }
    val ramp = if (closestDist < halfDurationMs) {
        1f - (closestDist.toFloat() / halfDurationMs.toFloat())
    } else 0f
    val alpha = (ramp * peakAlpha).coerceIn(0f, 1f)
    if (alpha <= 0f) return
    Box(modifier = modifier.background(Color.Black.copy(alpha = alpha)))
}
