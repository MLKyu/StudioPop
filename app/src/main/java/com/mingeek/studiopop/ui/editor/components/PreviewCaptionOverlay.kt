package com.mingeek.studiopop.ui.editor.components

import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
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
 * 동작:
 * - **2D 드래그** 로 anchorX/anchorY 동시 조정 — 가로/세로 자유 위치.
 * - **탭** 으로 해당 자막의 편집 시트 오픈 (겹친 자막을 골라 편집할 수 있게).
 * - 효과 적용된 자막([excludeCaptionIds]) 은 [com.mingeek.studiopop.ui.text.RichTextOverlay]
 *   가 시각 렌더를 담당하므로 여기선 **투명 hit zone** 만 깔아 같은 위치에서 드래그/탭이 가능하게 함.
 *
 * 겹침 처리: 같은 시각·같은 위치에 두 자막이 있으면 Compose hit-test 가 위 항목만 잡아 아래
 * 항목을 못 골랐던 문제를 — 시각 위에 작은 stagger offset 을 주고, 탭 시 시트가 가장 위 자막
 * 부터 차례로 열리는 방식으로 해소. 사용자는 미리보기에서 탭하거나 타임라인에서 직접 선택 가능.
 */
@Composable
fun PreviewCaptionOverlay(
    timeline: Timeline,
    currentOutputMs: Long,
    onCaptionAnchorChange: (id: String, anchorX: Float, anchorY: Float) -> Unit,
    onTextLayerAnchorChange: (id: String, anchorX: Float, anchorY: Float) -> Unit,
    onCaptionTap: (String) -> Unit = {},
    onTextLayerTap: (String) -> Unit = {},
    modifier: Modifier = Modifier,
    /**
     * 효과 적용된 자막 id 집합. 시각 렌더는 RichTextOverlay 가 하므로 본 컴포저는 투명 hit zone
     * 만 제공 — 텍스트는 그리지 않지만 드래그/탭 인터랙션은 동일하게 가능.
     */
    excludeCaptionIds: Set<String> = emptySet(),
) {
    val active = remember(timeline, currentOutputMs, excludeCaptionIds) {
        val sourceTime = timeline.mapOutputToSource(currentOutputMs)?.second
            ?: return@remember emptyList()
        buildList {
            timeline.captions.forEach {
                if (sourceTime in it.sourceStartMs..it.sourceEndMs) {
                    val effective = it.id in excludeCaptionIds
                    add(OverlayItem(it.id, Kind.CAPTION, it.text, it.style, effective))
                }
            }
            timeline.textLayers.forEach {
                if (sourceTime in it.sourceStartMs..it.sourceEndMs) {
                    add(OverlayItem(it.id, Kind.TEXT_LAYER, it.text, it.style, false))
                }
            }
        }
    }

    if (active.isEmpty()) return

    BoxWithConstraints(modifier = modifier.fillMaxSize()) {
        val density = LocalDensity.current
        val containerWidthPx = with(density) { maxWidth.toPx() }
        val containerHeightPx = with(density) { maxHeight.toPx() }

        active.forEachIndexed { index, item ->
            val align = anchorToAlignment(item.style.anchorX, item.style.anchorY)
            val bgAlpha = item.style.backgroundAlpha / 255f
            // 비효과 자막은 14sp 기본 — 기존 시각과 동일. 효과 자막은 RichTextOverlay 가 28sp 로
            // 그리므로 투명 hit zone 도 28sp 로 키워야 시각 영역과 hit 영역이 일치 (전체가 드래그/탭
            // 가능). sizeScale 은 양쪽 동일하게 곱.
            val fontSize = if (item.effective) {
                (EFFECTIVE_FONT_SIZE_SP * item.style.sizeScale).sp
            } else {
                (BASE_FONT_SIZE_SP * item.style.sizeScale).sp
            }

            // 같은 위치에 여러 자막이 겹치면 미세 stagger — 위 자막을 살짝 옆/위로 밀어내 아래
            // 자막의 hit zone 이 노출되게. 시각적으로도 둘 다 인식 가능.
            val staggerDp = (index * STAGGER_STEP_DP).dp

            // 드래그 콜백이 *현재* anchor 를 읽도록 rememberUpdatedState — pointerInput 의 suspend
            // 람다는 key 안 변하면 재시작 안 돼서 캡쳐된 item 의 anchor 가 stale. 이 래핑 없이는 첫
            // event 의 delta 만 반영되고 이후 event 는 동일 base + delta 로 같은 자리에 머무름.
            val latestAnchorX by rememberUpdatedState(item.style.anchorX)
            val latestAnchorY by rememberUpdatedState(item.style.anchorY)
            val onTapLatest by rememberUpdatedState(
                when (item.kind) {
                    Kind.CAPTION -> { -> onCaptionTap(item.id) }
                    Kind.TEXT_LAYER -> { -> onTextLayerTap(item.id) }
                }
            )
            val onAnchorChangeLatest by rememberUpdatedState(
                when (item.kind) {
                    Kind.CAPTION -> { x: Float, y: Float -> onCaptionAnchorChange(item.id, x, y) }
                    Kind.TEXT_LAYER -> { x: Float, y: Float -> onTextLayerAnchorChange(item.id, x, y) }
                }
            )

            val dragModifier = Modifier
                .pointerInput(item.id) {
                    detectTapGestures(onTap = { onTapLatest() })
                }
                .pointerInput(item.id) {
                    detectDragGestures { change, drag ->
                        change.consume()
                        if (containerHeightPx <= 0f || containerWidthPx <= 0f) {
                            return@detectDragGestures
                        }
                        // Compose 픽셀 delta → NDC delta. y 는 Compose(아래+) ↔ NDC(아래-) 부호 뒤집기.
                        val dx = drag.x * 2f / containerWidthPx
                        val dy = -drag.y * 2f / containerHeightPx
                        val newX = (latestAnchorX + dx).coerceIn(-1f, 1f)
                        val newY = (latestAnchorY + dy).coerceIn(-1f, 1f)
                        onAnchorChangeLatest(newX, newY)
                    }
                }

            if (item.effective) {
                // 효과 자막: 투명 hit zone 만 — 시각은 RichTextOverlay 가 그림. font size 가 RichText
                // 와 일치해 visible 영역 전체가 드래그/탭 반응.
                Text(
                    text = item.text.ifBlank { " " },
                    color = Color.Transparent,
                    fontSize = fontSize,
                    fontWeight = if (item.style.bold) FontWeight.Bold else FontWeight.Normal,
                    textAlign = TextAlign.Center,
                    modifier = Modifier
                        .align(align)
                        .padding(start = staggerDp, top = staggerDp)
                        .padding(horizontal = 8.dp, vertical = 4.dp)
                        .padding(horizontal = 6.dp, vertical = 2.dp)
                        .then(dragModifier),
                )
            } else {
                Text(
                    text = item.text.ifBlank { " " },
                    color = Color(item.style.textColor),
                    fontSize = fontSize,
                    fontWeight = if (item.style.bold) FontWeight.Bold else FontWeight.Normal,
                    textAlign = TextAlign.Center,
                    modifier = Modifier
                        .align(align)
                        .padding(start = staggerDp, top = staggerDp)
                        .padding(horizontal = 8.dp, vertical = 4.dp)
                        .background(
                            if (bgAlpha > 0f) Color.Black.copy(alpha = bgAlpha) else Color.Transparent
                        )
                        .padding(horizontal = 6.dp, vertical = 2.dp)
                        .then(dragModifier),
                )
            }
        }
    }
}

private enum class Kind { CAPTION, TEXT_LAYER }

private data class OverlayItem(
    val id: String,
    val kind: Kind,
    val text: String,
    val style: CaptionStyle,
    /** RichTextOverlay 가 시각을 담당. 본 오버레이는 투명 hit zone 만 제공. */
    val effective: Boolean,
)

/**
 * NDC anchor (-1..1) → Compose BiasAlignment.
 * NDC y 는 위가 +1 이지만 Compose 는 아래가 +1 이라 verticalBias 부호를 뒤집음.
 */
private fun anchorToAlignment(anchorX: Float, anchorY: Float): Alignment {
    val ax = anchorX.coerceIn(-1f, 1f)
    val ay = anchorY.coerceIn(-1f, 1f)
    return BiasAlignment(horizontalBias = ax, verticalBias = -ay)
}

private const val BASE_FONT_SIZE_SP = 14f
/** RichTextOverlay 의 baseFontSizeSp(28) 와 일치 — 효과 자막의 투명 hit zone 영역이 시각 영역과 매치. */
private const val EFFECTIVE_FONT_SIZE_SP = 28f
/** 같은 위치에 여러 자막이 쌓일 때 위 항목을 살짝 비켜 그려 아래 항목 hit zone 노출. */
private const val STAGGER_STEP_DP = 8
