package com.mingeek.studiopop.ui.editor.components

import androidx.compose.foundation.border
import androidx.compose.foundation.gestures.detectDragGestures
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
 * 현재 playhead (source-time 변환 후) 이 layer 시간 범위에 있을 때만 표시.
 *
 * 드래그: 짤 중심(centerX/Y) 을 NDC (-1..1) 로 조정 — 프레임 내에서 자유 이동.
 * 탭: [onSelect] 호출 → 바깥에서 선택 상태 관리.
 */
@Composable
fun PreviewStickerOverlay(
    timeline: Timeline,
    currentOutputMs: Long,
    selectedId: String?,
    onSelect: (String) -> Unit,
    onMove: (id: String, centerX: Float, centerY: Float) -> Unit,
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
            // NDC (-1..1) 에서 -1=좌/하, +1=우/상. Compose 좌표는 y-down 이라 변환 필요.
            val sizeDp = with(density) {
                (layer.scale.coerceIn(0.05f, 1f) * widthPx).toDp()
            }
            val offsetXPx = (layer.centerX + 1f) / 2f * widthPx - with(density) { sizeDp.toPx() / 2f }
            val offsetYPx = (1f - layer.centerY) / 2f * heightPx - with(density) { sizeDp.toPx() / 2f }

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
                        detectDragGestures(
                            onDragStart = { onSelect(layer.id) },
                        ) { change, drag ->
                            change.consume()
                            if (widthPx <= 0f || heightPx <= 0f) return@detectDragGestures
                            // Compose y-down delta → NDC y-up delta
                            val newX = (layer.centerX + drag.x * 2f / widthPx).coerceIn(-1f, 1f)
                            val newY = (layer.centerY - drag.y * 2f / heightPx).coerceIn(-1f, 1f)
                            onMove(layer.id, newX, newY)
                        }
                    },
            )
        }
    }
}
