package com.mingeek.studiopop.ui.editor.components

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.dp
import com.mingeek.studiopop.data.editor.MosaicKeyframe
import com.mingeek.studiopop.data.editor.MosaicMode
import com.mingeek.studiopop.data.editor.MosaicRegion
import com.mingeek.studiopop.data.editor.Timeline

/**
 * 프리뷰 위에 모자이크 영역 박스 렌더링.
 * - MANUAL: 드래그 가능한 회색 반투명 박스
 * - AUTO_FACE: 현재 시각 보간 rect 표시 (편집 불가, 시각 확인용)
 */
@Composable
fun PreviewMosaicOverlay(
    timeline: Timeline,
    currentOutputMs: Long,
    selectedId: String?,
    onSelect: (String) -> Unit,
    onManualMove: (id: String, cx: Float, cy: Float) -> Unit,
    modifier: Modifier = Modifier,
) {
    val activeWithKf = remember(timeline, currentOutputMs) {
        val sourceTime = timeline.mapOutputToSource(currentOutputMs)?.second
            ?: return@remember emptyList()
        timeline.mosaicRegions
            .filter { sourceTime in it.sourceStartMs..it.sourceEndMs }
            .mapNotNull { region ->
                val kf = interpolate(region, sourceTime) ?: return@mapNotNull null
                region to kf
            }
    }
    if (activeWithKf.isEmpty()) return

    BoxWithConstraints(modifier = modifier.fillMaxSize()) {
        val density = LocalDensity.current
        val widthPx = with(density) { maxWidth.toPx() }
        val heightPx = with(density) { maxHeight.toPx() }

        activeWithKf.forEach { (region, kf) ->
            // NDC(-1..1) → Compose offset(px)
            val wPx = (kf.w * widthPx).coerceAtLeast(20f)
            val hPx = (kf.h * heightPx).coerceAtLeast(20f)
            val leftPx = (kf.cx + 1f) / 2f * widthPx - wPx / 2f
            val topPx = (1f - kf.cy) / 2f * heightPx - hPx / 2f

            val border = if (region.id == selectedId) Color(0xFF4FC3F7) else Color.White
            val manual = region.mode == MosaicMode.MANUAL

            Box(
                modifier = Modifier
                    .offset(
                        x = with(density) { leftPx.toDp() },
                        y = with(density) { topPx.toDp() },
                    )
                    .size(
                        width = with(density) { wPx.toDp() },
                        height = with(density) { hPx.toDp() },
                    )
                    .background(Color(0xFF9E9E9E).copy(alpha = 0.55f))
                    .border(2.dp, border)
                    .then(
                        if (manual) Modifier.pointerInput(region.id, widthPx, heightPx) {
                            detectDragGestures(
                                onDragStart = { onSelect(region.id) },
                            ) { change, drag ->
                                change.consume()
                                if (widthPx <= 0f || heightPx <= 0f) return@detectDragGestures
                                val newCx = (kf.cx + drag.x * 2f / widthPx).coerceIn(-1f, 1f)
                                val newCy = (kf.cy - drag.y * 2f / heightPx).coerceIn(-1f, 1f)
                                onManualMove(region.id, newCx, newCy)
                            }
                        } else Modifier,
                    ),
            )
        }
    }
}

private fun interpolate(region: MosaicRegion, sourceMs: Long): MosaicKeyframe? {
    val kfs = region.keyframes.sortedBy { it.sourceTimeMs }
    if (kfs.isEmpty()) return null
    if (sourceMs <= kfs.first().sourceTimeMs) return kfs.first()
    if (sourceMs >= kfs.last().sourceTimeMs) return kfs.last()
    var prev = kfs.first()
    for (i in 1 until kfs.size) {
        val next = kfs[i]
        if (sourceMs in prev.sourceTimeMs..next.sourceTimeMs) {
            val span = (next.sourceTimeMs - prev.sourceTimeMs).coerceAtLeast(1L)
            val t = (sourceMs - prev.sourceTimeMs).toFloat() / span.toFloat()
            return MosaicKeyframe(
                sourceTimeMs = sourceMs,
                cx = prev.cx + (next.cx - prev.cx) * t,
                cy = prev.cy + (next.cy - prev.cy) * t,
                w = prev.w + (next.w - prev.w) * t,
                h = prev.h + (next.h - prev.h) * t,
            )
        }
        prev = next
    }
    return kfs.last()
}
