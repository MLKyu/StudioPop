package com.mingeek.studiopop.ui.editor.components

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.detectTransformGestures
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
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
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
import kotlin.math.atan2
import kotlin.math.hypot

/**
 * 프리뷰 위에 짤(ImageLayer) 렌더링.
 *
 * **두 가지 입력 경로 병행** (DeX/마우스 + 터치 모두 지원):
 * - **터치 사용자**: 이미지 자체를 `detectTransformGestures` — 한 손가락 드래그(이동) +
 *   두 손가락 pinch(크기) + 두 손가락 회전
 * - **마우스/DeX 사용자**: 선택 시 우하단 resize 핸들(드래그 → 크기) + 상단 rotation 핸들
 *   (드래그 → 회전). 마우스 단일 포인터로도 모든 조작 가능.
 *
 * 재생 중엔 모든 편집 UI 숨김 — 결과물 확인 모드.
 */
@Composable
fun PreviewStickerOverlay(
    timeline: Timeline,
    currentOutputMs: Long,
    selectedId: String?,
    isPlaying: Boolean,
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
            val layerState by rememberUpdatedState(layer)
            val isSelected = layer.id == selectedId
            val sizeDp = with(density) {
                (layer.scale.coerceIn(0.05f, 1f) * widthPx).toDp()
            }
            val sizePx = with(density) { sizeDp.toPx() }
            val centerXPx = (layer.centerX + 1f) / 2f * widthPx
            val centerYPx = (1f - layer.centerY) / 2f * heightPx
            val offsetXPx = centerXPx - sizePx / 2f
            val offsetYPx = centerYPx - sizePx / 2f

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
                        if (!isPlaying && isSelected) Modifier.border(2.dp, Color(0xFF4FC3F7))
                        else Modifier
                    )
                    .then(
                        if (isPlaying) Modifier
                        else Modifier
                            .pointerInput(layer.id) {
                                detectTapGestures(onTap = { onSelect(layer.id) })
                            }
                            .pointerInput(layer.id, widthPx, heightPx) {
                                // 터치 멀티포인터용. 마우스 단일 포인터에선 pan 만 작동(zoom/rot=0).
                                detectTransformGestures { _, pan, zoom, rotation ->
                                    if (widthPx <= 0f || heightPx <= 0f) return@detectTransformGestures
                                    onSelect(layer.id)
                                    val cur = layerState
                                    val newX = (cur.centerX + pan.x * 2f / widthPx).coerceIn(-1f, 1f)
                                    val newY = (cur.centerY - pan.y * 2f / heightPx).coerceIn(-1f, 1f)
                                    onTransform(layer.id, newX, newY, zoom, rotation)
                                }
                            },
                    ),
            )

            // 편집 핸들 — 선택 + 재생 안 할 때만. 마우스 단일 포인터로 조작 가능.
            if (!isPlaying && isSelected) {
                // Resize 핸들 (우하단, 비회전 bounding box 기준). 중심-핸들 거리 변화를 zoom factor 로.
                val resizeHandleX = offsetXPx + sizePx
                val resizeHandleY = offsetYPx + sizePx
                StickerHandle(
                    xPx = resizeHandleX,
                    yPx = resizeHandleY,
                    color = Color(0xFF4FC3F7),
                    onDrag = { dragX, dragY ->
                        val curCenterX = (layerState.centerX + 1f) / 2f * widthPx
                        val curCenterY = (1f - layerState.centerY) / 2f * heightPx
                        val curSizePx = layerState.scale.coerceIn(0.05f, 1f) * widthPx
                        val curHandleX = curCenterX + curSizePx / 2f
                        val curHandleY = curCenterY + curSizePx / 2f
                        val curDist = hypot(curHandleX - curCenterX, curHandleY - curCenterY)
                        val newDist = hypot(
                            (curHandleX + dragX) - curCenterX,
                            (curHandleY + dragY) - curCenterY,
                        )
                        val zoom = if (curDist > 0.1f) (newDist / curDist) else 1f
                        onTransform(
                            layerState.id,
                            layerState.centerX,
                            layerState.centerY,
                            zoom,
                            0f,
                        )
                    },
                )
                // Rotation 핸들 (상단 위로 24dp 돌출). 중심 기준 각도 변화를 회전 delta 로.
                val rotHandleOffset = with(density) { ROTATION_HANDLE_OFFSET_DP.dp.toPx() }
                val rotHandleX = offsetXPx + sizePx / 2f
                val rotHandleY = offsetYPx - rotHandleOffset
                StickerHandle(
                    xPx = rotHandleX,
                    yPx = rotHandleY,
                    color = Color(0xFFFF8A65),
                    onDrag = { dragX, dragY ->
                        val curCenterX = (layerState.centerX + 1f) / 2f * widthPx
                        val curCenterY = (1f - layerState.centerY) / 2f * heightPx
                        val curSizePx = layerState.scale.coerceIn(0.05f, 1f) * widthPx
                        val curHandleX = curCenterX
                        val curHandleY = curCenterY - curSizePx / 2f - rotHandleOffset
                        val prevAngle = atan2(
                            curHandleY - curCenterY,
                            curHandleX - curCenterX,
                        )
                        val newAngle = atan2(
                            (curHandleY + dragY) - curCenterY,
                            (curHandleX + dragX) - curCenterX,
                        )
                        val deltaDeg = Math.toDegrees((newAngle - prevAngle).toDouble()).toFloat()
                        onTransform(
                            layerState.id,
                            layerState.centerX,
                            layerState.centerY,
                            1f,
                            deltaDeg,
                        )
                    },
                )
            }
        }
    }
}

@Composable
private fun StickerHandle(
    xPx: Float,
    yPx: Float,
    color: Color,
    onDrag: (dragX: Float, dragY: Float) -> Unit,
) {
    val density = LocalDensity.current
    val sizeDp = HANDLE_SIZE_DP.dp
    val halfPx = with(density) { sizeDp.toPx() / 2f }
    Box(
        modifier = Modifier
            .offset(
                x = with(density) { (xPx - halfPx).toDp() },
                y = with(density) { (yPx - halfPx).toDp() },
            )
            .size(sizeDp)
            .clip(CircleShape)
            .background(color)
            .border(2.dp, Color.White, CircleShape)
            .pointerInput(Unit) {
                detectDragGestures { change, drag ->
                    change.consume()
                    onDrag(drag.x, drag.y)
                }
            },
    )
}

private const val HANDLE_SIZE_DP = 20
private const val ROTATION_HANDLE_OFFSET_DP = 32
