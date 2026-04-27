package com.mingeek.studiopop.ui.editor.components

import android.graphics.Bitmap
import android.graphics.Canvas as AndroidCanvas
import android.graphics.Color as AndroidColor
import android.graphics.Paint
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.dp
import com.mingeek.studiopop.data.editor.MosaicKeyframe
import com.mingeek.studiopop.data.editor.MosaicMode
import com.mingeek.studiopop.data.editor.MosaicRegion
import com.mingeek.studiopop.data.editor.Timeline

/**
 * 프리뷰 위에 모자이크 영역 박스 렌더링.
 *
 * 두 가지 모드 자동 전환:
 * - **Pause (isPlaying=false)** = 편집 모드:
 *   - 회색 반투명 박스 + 테두리
 *   - MANUAL + 선택 상태면 4 모서리 리사이즈 핸들
 *   - 드래그로 이동·크기 조정
 * - **Play (isPlaying=true)** = 확인 모드:
 *   - 실제 모자이크 패턴(회색 노이즈 블록) 으로 rect 을 채워 export 와 유사한 모습 미리보기
 *   - 편집 UI(핸들·테두리·드래그) 전부 비활성화
 */
@Composable
fun PreviewMosaicOverlay(
    timeline: Timeline,
    currentOutputMs: Long,
    selectedId: String?,
    isPlaying: Boolean,
    onSelect: (String) -> Unit,
    onManualRectChange: (id: String, cx: Float, cy: Float, w: Float, h: Float) -> Unit,
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

    // 재생 시 rect 안에 붙일 모자이크 패턴 비트맵 — 한 번 생성 후 재사용.
    val mosaicPattern = remember { buildMosaicPatternBitmap() }

    BoxWithConstraints(modifier = modifier.fillMaxSize()) {
        val density = LocalDensity.current
        val widthPx = with(density) { maxWidth.toPx() }
        val heightPx = with(density) { maxHeight.toPx() }

        activeWithKf.forEach { (region, kf) ->
            val kfState by rememberUpdatedState(kf)
            val wPx = (kf.w * widthPx).coerceAtLeast(MIN_RENDER_PX)
            val hPx = (kf.h * heightPx).coerceAtLeast(MIN_RENDER_PX)
            val leftPx = (kf.cx + 1f) / 2f * widthPx - wPx / 2f
            val topPx = (1f - kf.cy) / 2f * heightPx - hPx / 2f
            val isSelected = region.id == selectedId
            val manual = region.mode == MosaicMode.MANUAL

            if (isPlaying) {
                // === 확인 모드 === rect 에 실제 모자이크 패턴 보여주기
                Image(
                    bitmap = mosaicPattern.asImageBitmap(),
                    contentDescription = null,
                    contentScale = ContentScale.FillBounds,
                    modifier = Modifier
                        .offset(
                            x = with(density) { leftPx.toDp() },
                            y = with(density) { topPx.toDp() },
                        )
                        .size(
                            width = with(density) { wPx.toDp() },
                            height = with(density) { hPx.toDp() },
                        ),
                )
            } else {
                // === 편집 모드 === 반투명 박스 + 드래그 + 핸들
                val border = if (isSelected) Color(0xFF4FC3F7) else Color.White
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
                                    val cur = kfState
                                    val newCx = (cur.cx + drag.x * 2f / widthPx).coerceIn(-1f, 1f)
                                    val newCy = (cur.cy - drag.y * 2f / heightPx).coerceIn(-1f, 1f)
                                    onManualRectChange(region.id, newCx, newCy, cur.w, cur.h)
                                }
                            } else Modifier,
                        ),
                )

                if (manual && isSelected) {
                    val corners = listOf(
                        Corner.TopLeft to (leftPx to topPx),
                        Corner.TopRight to (leftPx + wPx to topPx),
                        Corner.BottomLeft to (leftPx to topPx + hPx),
                        Corner.BottomRight to (leftPx + wPx to topPx + hPx),
                    )
                    corners.forEach { (corner, pos) ->
                        val (px, py) = pos
                        val handleSizeDp = HANDLE_SIZE_DP.dp
                        Box(
                            modifier = Modifier
                                .offset(
                                    x = with(density) { (px - HANDLE_SIZE_DP.dp.toPx() / 2f).toDp() },
                                    y = with(density) { (py - HANDLE_SIZE_DP.dp.toPx() / 2f).toDp() },
                                )
                                .size(handleSizeDp)
                                .clip(CircleShape)
                                .background(Color(0xFF4FC3F7))
                                .border(2.dp, Color.White, CircleShape)
                                .pointerInput(region.id, corner, widthPx, heightPx) {
                                    detectDragGestures { change, drag ->
                                        change.consume()
                                        if (widthPx <= 0f || heightPx <= 0f) return@detectDragGestures
                                        val dNdcX = drag.x * 2f / widthPx
                                        val dNdcY = -drag.y * 2f / heightPx
                                        val resized = resizeRect(kfState, corner, dNdcX, dNdcY)
                                        onManualRectChange(
                                            region.id,
                                            resized.cx,
                                            resized.cy,
                                            resized.w,
                                            resized.h,
                                        )
                                    }
                                },
                        )
                    }
                }
            }
        }
    }
}

/**
 * 회색 노이즈 블록으로 채워진 모자이크 패턴 비트맵. 한 번 생성해 모든 rect 에 재사용.
 * [Image] + ContentScale.FillBounds 로 rect 크기에 맞춰 늘어나므로 블록 크기는 rect 크기에 비례.
 * export 의 [com.mingeek.studiopop.data.editor.MosaicBlockOverlay] 와 유사한 느낌.
 */
private fun buildMosaicPatternBitmap(): Bitmap {
    val size = 256
    val block = 16
    val bmp = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888)
    val canvas = AndroidCanvas(bmp)
    val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.FILL }
    var seed = 1234
    var y = 0
    while (y < size) {
        var x = 0
        while (x < size) {
            seed = (seed * 1103515245 + 12345) and 0x7FFFFFFF
            val gray = 60 + (seed ushr 16) % 140
            paint.color = AndroidColor.rgb(gray, gray, gray)
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

private enum class Corner { TopLeft, TopRight, BottomLeft, BottomRight }

private data class Rect(val cx: Float, val cy: Float, val w: Float, val h: Float)

private fun resizeRect(kf: MosaicKeyframe, corner: Corner, dNdcX: Float, dNdcY: Float): Rect {
    val halfW = kf.w / 2f
    val halfH = kf.h / 2f

    var left = kf.cx - halfW
    var right = kf.cx + halfW
    var top = kf.cy + halfH
    var bottom = kf.cy - halfH

    when (corner) {
        Corner.TopLeft -> {
            left = (left + dNdcX).coerceIn(-1f, right - MIN_SIZE_NDC)
            top = (top + dNdcY).coerceIn(bottom + MIN_SIZE_NDC, 1f)
        }
        Corner.TopRight -> {
            right = (right + dNdcX).coerceIn(left + MIN_SIZE_NDC, 1f)
            top = (top + dNdcY).coerceIn(bottom + MIN_SIZE_NDC, 1f)
        }
        Corner.BottomLeft -> {
            left = (left + dNdcX).coerceIn(-1f, right - MIN_SIZE_NDC)
            bottom = (bottom + dNdcY).coerceIn(-1f, top - MIN_SIZE_NDC)
        }
        Corner.BottomRight -> {
            right = (right + dNdcX).coerceIn(left + MIN_SIZE_NDC, 1f)
            bottom = (bottom + dNdcY).coerceIn(-1f, top - MIN_SIZE_NDC)
        }
    }

    val newW = right - left
    val newH = top - bottom
    val newCx = (left + right) / 2f
    val newCy = (top + bottom) / 2f
    return Rect(newCx, newCy, newW, newH)
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

private const val HANDLE_SIZE_DP = 20
private const val MIN_SIZE_NDC = 0.04f
private const val MIN_RENDER_PX = 12f
