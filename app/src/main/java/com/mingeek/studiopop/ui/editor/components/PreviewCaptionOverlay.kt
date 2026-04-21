package com.mingeek.studiopop.ui.editor.components

import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.BiasAlignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.mingeek.studiopop.data.editor.CaptionStyle
import com.mingeek.studiopop.data.editor.Timeline

/**
 * PlayerView 위에 겹쳐 그리는 자막/텍스트 레이어 오버레이.
 *
 * 추가 기능: 각 오버레이 아이템을 **세로로 드래그**하면 해당 아이템의 anchorY
 * 가 실시간 업데이트되어 편집 중 위치 조정이 가능.
 */
@Composable
fun PreviewCaptionOverlay(
    timeline: Timeline,
    currentOutputMs: Long,
    onCaptionAnchorChange: (String, Float) -> Unit,
    onTextLayerAnchorChange: (String, Float) -> Unit,
    modifier: Modifier = Modifier,
) {
    val active = remember(timeline, currentOutputMs) {
        val sourceTime = timeline.mapOutputToSource(currentOutputMs)?.second
            ?: return@remember emptyList()
        buildList {
            timeline.captions.forEach {
                if (sourceTime in it.sourceStartMs..it.sourceEndMs) {
                    add(OverlayItem(it.id, Kind.CAPTION, it.text, it.style))
                }
            }
            timeline.textLayers.forEach {
                if (sourceTime in it.sourceStartMs..it.sourceEndMs) {
                    add(OverlayItem(it.id, Kind.TEXT_LAYER, it.text, it.style))
                }
            }
        }
    }

    if (active.isEmpty()) return

    BoxWithConstraints(modifier = modifier.fillMaxSize()) {
        val density = LocalDensity.current
        val containerHeightPx = with(density) { maxHeight.toPx() }

        active.forEach { item ->
            val align = anchorToAlignment(item.style.anchorY)
            val bgAlpha = item.style.backgroundAlpha / 255f
            val fontSize = (BASE_FONT_SIZE_SP * item.style.sizeScale).sp

            Text(
                text = item.text.ifBlank { " " },
                color = Color(item.style.textColor),
                fontSize = fontSize,
                fontWeight = if (item.style.bold) FontWeight.Bold else FontWeight.Normal,
                textAlign = TextAlign.Center,
                modifier = Modifier
                    .align(align)
                    .padding(horizontal = 8.dp, vertical = 4.dp)
                    .background(
                        if (bgAlpha > 0f) Color.Black.copy(alpha = bgAlpha) else Color.Transparent
                    )
                    .padding(horizontal = 6.dp, vertical = 2.dp)
                    .pointerInput(item.id, containerHeightPx) {
                        // 세로 드래그: y 픽셀 delta → anchorY delta.
                        // Compose y 는 아래가 +, anchorY 는 아래가 -1 이라 부호 뒤집음.
                        detectDragGestures { change, drag ->
                            change.consume()
                            if (containerHeightPx <= 0f) return@detectDragGestures
                            val anchorDelta = -drag.y * 2f / containerHeightPx
                            val newAnchor = (item.style.anchorY + anchorDelta).coerceIn(-1f, 1f)
                            when (item.kind) {
                                Kind.CAPTION    -> onCaptionAnchorChange(item.id, newAnchor)
                                Kind.TEXT_LAYER -> onTextLayerAnchorChange(item.id, newAnchor)
                            }
                        }
                    },
            )
        }
    }
}

private enum class Kind { CAPTION, TEXT_LAYER }

private data class OverlayItem(
    val id: String,
    val kind: Kind,
    val text: String,
    val style: CaptionStyle,
)

/**
 * NDC anchorY (-1 bottom, 1 top) → Compose BiasAlignment(horizontalBias=0, verticalBias).
 */
private fun anchorToAlignment(anchorY: Float): Alignment {
    val clipped = anchorY.coerceIn(-1f, 1f)
    return BiasAlignment(horizontalBias = 0f, verticalBias = -clipped)
}

private const val BASE_FONT_SIZE_SP = 14f
