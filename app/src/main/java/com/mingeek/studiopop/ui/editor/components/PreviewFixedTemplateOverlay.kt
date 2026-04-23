package com.mingeek.studiopop.ui.editor.components

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
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
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.mingeek.studiopop.data.editor.FixedTextTemplate
import com.mingeek.studiopop.data.editor.Timeline

/**
 * 프리뷰 위에 고정 텍스트 템플릿 렌더링.
 * - 현재 세그먼트 id 를 판정해 perSegmentText override 우선, 없으면 defaultText
 * - override 가 "" 이면 해당 구간에선 감춤
 * - enabled = false 면 표시 안 함
 */
@Composable
fun PreviewFixedTemplateOverlay(
    timeline: Timeline,
    currentOutputMs: Long,
    modifier: Modifier = Modifier,
) {
    val visible = remember(timeline, currentOutputMs) {
        val currentSegment = timeline.mapOutputToSource(currentOutputMs)?.first
            ?: return@remember emptyList<Pair<FixedTextTemplate, String>>()
        timeline.fixedTemplates
            .filter { it.enabled }
            .mapNotNull { template ->
                val text = template.perSegmentText[currentSegment.id] ?: template.defaultText
                if (text.isBlank()) null else template to text
            }
    }
    if (visible.isEmpty()) return

    BoxWithConstraints(modifier = modifier.fillMaxSize()) {
        visible.forEach { (template, text) ->
            val align = BiasAlignment(
                horizontalBias = template.anchor.ndcX.coerceIn(-1f, 1f),
                verticalBias = -template.anchor.ndcY.coerceIn(-1f, 1f),
            )
            val style = template.style
            val bgAlpha = style.backgroundAlpha / 255f
            val fontSize = (BASE_FONT_SIZE_SP * style.sizeScale).sp
            Box(
                modifier = Modifier
                    .align(align)
                    .padding(horizontal = 8.dp, vertical = 6.dp),
            ) {
                Text(
                    text = text,
                    color = Color(style.textColor),
                    fontSize = fontSize,
                    fontWeight = if (style.bold) FontWeight.Bold else FontWeight.Normal,
                    textAlign = TextAlign.Center,
                    modifier = Modifier
                        .background(
                            if (bgAlpha > 0f) Color.Black.copy(alpha = bgAlpha) else Color.Transparent
                        )
                        .padding(horizontal = 6.dp, vertical = 2.dp),
                )
            }
        }
    }
}

private const val BASE_FONT_SIZE_SP = 14f
