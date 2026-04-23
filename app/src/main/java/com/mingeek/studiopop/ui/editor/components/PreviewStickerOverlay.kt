package com.mingeek.studiopop.ui.editor.components

import androidx.compose.foundation.border
import androidx.compose.foundation.gestures.detectTransformGestures
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.dp
import coil.compose.AsyncImage
import com.mingeek.studiopop.data.editor.ImageLayer
import com.mingeek.studiopop.data.editor.Timeline
import java.io.File

/**
 * 프리뷰 위에 짤(ImageLayer) 렌더링.
 * 현재 playhead (source-time 변환 후) 가 layer 시간 범위에 있을 때만 표시.
 *
 * 제스처: [detectTransformGestures] 로 드래그 + 핀치(크기) + 회전 동시 처리.
 * - 한 손가락 드래그 → centerX/Y 이동
 * - 두 손가락 pinch → scale
 * - 두 손가락 회전 → rotationDeg
 * 탭/드래그 시작 시 자동 선택.
 */
@Composable
fun PreviewStickerOverlay(
    timeline: Timeline,
    currentOutputMs: Long,
    selectedId: String?,
    onSelect: (String) -> Unit,
    onTransform: (
        id: String,
        newCenterX: Float,
        newCenterY: Float,
        zoomDelta: Float,
        rotationDelta: Float,
    ) -> Unit,
    modifier: Modifier = Modifier,
) {
    val active = remember(timeline, currentOutputMs) {
        val sourceTime = timeline.mapOutputToSource(currentOutputMs)?.second
            ?: return@remember emptyList()
        timeline.imageLayers.filter { sourceTime in it.sourceStartMs..it.sourceEndMs }
    }
    if (active.isEmpty()) return

    BoxWithConstraints(modifier = modifier.fillMaxSize()) {
        val density = LocalDensity.current
        val widthPx = with(density) { maxWidth.toPx() }
        val heightPx = with(density) { maxHeight.toPx() }

        active.forEach { layer ->
            val sizeDp = with(density) {
                (layer.scale.coerceIn(0.05f, 1f) * widthPx).toDp()
            }
            val sizePx = with(density) { sizeDp.toPx() }
            val offsetXPx = (layer.centerX + 1f) / 2f * widthPx - sizePx / 2f
            val offsetYPx = (1f - layer.centerY) / 2f * heightPx - sizePx / 2f

            AsyncImage(
                model = File(layer.imageUri.path ?: ""),
                contentDescription = null,
                contentScale = ContentScale.Fit,
                modifier = Modifier
                    .offset(
                        x = with(density) { offsetXPx.toDp() },
                        y = with(density) { offsetYPx.toDp() },
                    )
                    .size(sizeDp)
                    .rotate(layer.rotationDeg)
                    .then(
                        if (layer.id == selectedId) {
                            Modifier.border(2.dp, Color(0xFF4FC3F7))
                        } else Modifier
                    )
                    .pointerInput(layer.id, widthPx, heightPx) {
                        detectTransformGestures { _, pan, zoom, rotation ->
                            if (widthPx <= 0f || heightPx <= 0f) return@detectTransformGestures
                            onSelect(layer.id)
                            // 제스처 시작 시점의 layer 가 아니라 "현재 상태" 에서 누적 업데이트.
                            // layer 는 composable 스냅샷이라 같은 제스처 동안 오래된 값일 수 있어 pan 은 delta 로만 적용.
                            val newX = (layer.centerX + pan.x * 2f / widthPx).coerceIn(-1f, 1f)
                            val newY = (layer.centerY - pan.y * 2f / heightPx).coerceIn(-1f, 1f)
                            onTransform(layer.id, newX, newY, zoom, rotation)
                        }
                    },
            )
        }
    }
}
