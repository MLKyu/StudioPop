package com.mingeek.studiopop.ui.editor.components

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.BiasAlignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.material3.Text
import com.mingeek.studiopop.data.editor.CaptionStyle
import com.mingeek.studiopop.data.editor.Timeline

/**
 * 미리보기 PlayerView 위에 겹쳐 그리는 자막/텍스트 레이어 오버레이.
 *
 * 동작 원리:
 *  - [currentOutputMs] 를 [Timeline.mapOutputToSource] 로 source 시각으로 변환
 *  - captions / textLayers 중 해당 source 시각에 활성화된 아이템을 수집
 *  - 각 아이템의 [CaptionStyle.anchorY] (NDC -1..1) 를 Compose BiasAlignment 로 변환
 *
 * 주의: Export 는 여전히 Media3 OverlayEffect 로 고품질 합성. 이 오버레이는
 * 프리뷰 전용이라 위치·폰트 크기는 근사.
 */
@Composable
fun PreviewCaptionOverlay(
    timeline: Timeline,
    currentOutputMs: Long,
    modifier: Modifier = Modifier,
) {
    val active = remember(timeline, currentOutputMs) {
        val sourceTime = timeline.mapOutputToSource(currentOutputMs)?.second
            ?: return@remember emptyList()
        buildList {
            timeline.captions.forEach {
                if (sourceTime in it.sourceStartMs..it.sourceEndMs) {
                    add(OverlayItem(it.text, it.style))
                }
            }
            timeline.textLayers.forEach {
                if (sourceTime in it.sourceStartMs..it.sourceEndMs) {
                    add(OverlayItem(it.text, it.style))
                }
            }
        }
    }

    if (active.isEmpty()) return
    Box(modifier = modifier.fillMaxSize()) {
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
                    .padding(horizontal = 6.dp, vertical = 2.dp),
            )
        }
    }
}

private data class OverlayItem(val text: String, val style: CaptionStyle)

/**
 * NDC anchorY (-1 bottom, 1 top) → Compose BiasAlignment(horizontalBias=0, verticalBias).
 * Compose 의 verticalBias 는 반대 방향(-1 top, 1 bottom) 이라 부호 뒤집음.
 */
private fun anchorToAlignment(anchorY: Float): Alignment {
    val clipped = anchorY.coerceIn(-1f, 1f)
    return BiasAlignment(horizontalBias = 0f, verticalBias = -clipped)
}

private const val BASE_FONT_SIZE_SP = 14f
