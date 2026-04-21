package com.mingeek.studiopop.ui.editor.components

import android.graphics.Bitmap
import android.net.Uri
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.mingeek.studiopop.data.editor.Timeline
import com.mingeek.studiopop.data.editor.TimelineCaption
import com.mingeek.studiopop.data.editor.TimelineSegment

/**
 * 썸네일 스트립 + 세그먼트 경계 + 자막 막대 + 플레이헤드를 렌더링하는 타임라인.
 *
 * 좌표 체계: 타임라인 x 축 = "출력 ms". pxPerMs 로 변환.
 */
@Composable
fun TimelineView(
    timeline: Timeline,
    /** 영상 Uri → (총 길이 ms, 프레임 스트립) 맵. 각 세그먼트가 자기 sourceUri 로 조회. */
    frameStrips: Map<Uri, Pair<Long, List<Bitmap>>>,
    playheadOutputMs: Long,
    selectedSegmentId: String?,
    selectedCaptionId: String?,
    pxPerMs: Float = DEFAULT_PX_PER_MS,
    onSegmentTap: (String) -> Unit,
    onCaptionTap: (String) -> Unit,
    onPlayheadDrag: (Long) -> Unit,
    onDividerDrag: (prevSegId: String, nextSegId: String, sourceDeltaMs: Long) -> Unit,
    modifier: Modifier = Modifier,
) {
    val density = LocalDensity.current
    val totalWidthDp: Dp = with(density) { (timeline.outputDurationMs * pxPerMs).toDp() }

    Box(
        modifier = modifier
            .height(TIMELINE_HEIGHT_DP.dp)
            .horizontalScroll(rememberScrollState()),
    ) {
        Box(modifier = Modifier.width(totalWidthDp).fillMaxHeight()) {
            // 1) 세그먼트 + 썸네일 레이어
            Row(modifier = Modifier.fillMaxHeight()) {
                timeline.segments.forEachIndexed { idx, seg ->
                    val (totalSourceMs, strip) = frameStrips[seg.sourceUri]
                        ?: (seg.sourceEndMs to emptyList())
                    SegmentBlock(
                        segment = seg,
                        totalSourceMs = totalSourceMs,
                        frameStrip = strip,
                        isSelected = seg.id == selectedSegmentId,
                        pxPerMs = pxPerMs,
                        onTap = { onSegmentTap(seg.id) },
                    )
                    // 세그먼트 사이 드래그 가능 경계(마지막 제외)
                    if (idx < timeline.segments.lastIndex) {
                        DraggableDivider(
                            prevSegId = seg.id,
                            nextSegId = timeline.segments[idx + 1].id,
                            pxPerMs = pxPerMs,
                            onDrag = onDividerDrag,
                        )
                    }
                }
            }

            // 2) 자막 막대 레이어
            Box(modifier = Modifier.fillMaxHeight()) {
                timeline.captions.forEach { cap ->
                    CaptionBar(
                        caption = cap,
                        timeline = timeline,
                        pxPerMs = pxPerMs,
                        isSelected = cap.id == selectedCaptionId,
                        onTap = { onCaptionTap(cap.id) },
                    )
                }
            }

            // 3) 플레이헤드 + 드래그 캐치 레이어
            Box(
                modifier = Modifier
                    .fillMaxHeight()
                    .width(totalWidthDp)
                    .pointerInput(timeline.outputDurationMs) {
                        detectTapGestures(
                            onTap = { offset ->
                                val ms = (offset.x / pxPerMs).toLong()
                                    .coerceIn(0L, timeline.outputDurationMs)
                                onPlayheadDrag(ms)
                            },
                            onPress = { offset ->
                                val ms = (offset.x / pxPerMs).toLong()
                                    .coerceIn(0L, timeline.outputDurationMs)
                                onPlayheadDrag(ms)
                            },
                        )
                    }
            ) {
                Canvas(modifier = Modifier.fillMaxHeight().width(totalWidthDp)) {
                    val x = playheadOutputMs * pxPerMs
                    drawLine(
                        color = Color(0xFFFF4081),
                        start = Offset(x, 0f),
                        end = Offset(x, size.height),
                        strokeWidth = with(density) { 2.dp.toPx() },
                    )
                    // 플레이헤드 헤드 (삼각형 대신 작은 사각형)
                    drawRect(
                        color = Color(0xFFFF4081),
                        topLeft = Offset(x - with(density) { 6.dp.toPx() }, 0f),
                        size = Size(with(density) { 12.dp.toPx() }, with(density) { 10.dp.toPx() }),
                    )
                }
            }
        }
    }
}

/**
 * 두 세그먼트 사이의 드래그 가능 경계.
 * 가로 드래그 시 픽셀 delta 를 ms 로 변환해 [onDrag] 호출.
 * 히트 영역은 넓게(14dp), 가운데에 얇은(3dp) 노란색 선을 그려 시각화.
 */
@Composable
private fun DraggableDivider(
    prevSegId: String,
    nextSegId: String,
    pxPerMs: Float,
    onDrag: (String, String, Long) -> Unit,
) {
    var isDragging by remember { mutableStateOf(false) }
    val density = LocalDensity.current

    Box(
        modifier = Modifier
            .width(DIVIDER_HIT_WIDTH_DP.dp)
            .fillMaxHeight()
            .pointerInput(prevSegId, nextSegId, pxPerMs) {
                detectDragGestures(
                    onDragStart = { isDragging = true },
                    onDragEnd   = { isDragging = false },
                    onDragCancel = { isDragging = false },
                ) { change, dragAmount ->
                    change.consume()
                    val deltaMs = (dragAmount.x / pxPerMs).toLong()
                    if (deltaMs != 0L) onDrag(prevSegId, nextSegId, deltaMs)
                }
            },
        contentAlignment = androidx.compose.ui.Alignment.Center,
    ) {
        Box(
            modifier = Modifier
                .width(if (isDragging) 6.dp else 3.dp)
                .fillMaxHeight()
                .background(if (isDragging) Color(0xFFFFC400) else Color(0xFFFFAB00)),
        )
    }
}

@Composable
private fun SegmentBlock(
    segment: TimelineSegment,
    totalSourceMs: Long,
    frameStrip: List<Bitmap>,
    isSelected: Boolean,
    pxPerMs: Float,
    onTap: () -> Unit,
) {
    val density = LocalDensity.current
    val widthDp = with(density) { (segment.durationMs * pxPerMs).toDp() }

    // 이 세그먼트에 해당하는 썸네일 인덱스 범위 계산.
    // frameStrip 은 백그라운드로 생성되므로 초기에는 비어 있을 수 있음 → 가드 필수.
    val n = frameStrip.size
    val hasFrames = n > 0 && totalSourceMs > 0
    val step: Long = if (hasFrames) (totalSourceMs / n).coerceAtLeast(1L) else 0L
    val framesForSeg: List<Bitmap> = if (!hasFrames) {
        emptyList()
    } else {
        val startIdx = (segment.sourceStartMs / step).toInt().coerceIn(0, n - 1)
        val endIdx = ((segment.sourceEndMs - 1) / step).toInt().coerceIn(startIdx, n - 1)
        frameStrip.subList(startIdx, endIdx + 1)
    }

    Box(
        modifier = Modifier
            .width(widthDp)
            .fillMaxHeight()
            .clip(RoundedCornerShape(6.dp))
            .then(
                if (isSelected)
                    Modifier.border(
                        2.dp,
                        MaterialTheme.colorScheme.primary,
                        RoundedCornerShape(6.dp)
                    )
                else Modifier
            )
            .clickable { onTap() }
            .background(MaterialTheme.colorScheme.surfaceVariant),
    ) {
        // 썸네일 균등 배치
        if (framesForSeg.isNotEmpty()) {
            Row(modifier = Modifier.fillMaxHeight()) {
                framesForSeg.forEach { bmp ->
                    Image(
                        bitmap = bmp.asImageBitmap(),
                        contentDescription = null,
                        contentScale = ContentScale.Crop,
                        modifier = Modifier
                            .fillMaxHeight()
                            .width(widthDp / framesForSeg.size),
                    )
                }
            }
        } else {
            Text(
                "segment",
                modifier = Modifier.padding(6.dp),
                style = MaterialTheme.typography.labelSmall,
            )
        }
    }
}

@Composable
private fun CaptionBar(
    caption: TimelineCaption,
    timeline: Timeline,
    pxPerMs: Float,
    isSelected: Boolean,
    onTap: () -> Unit,
) {
    val density = LocalDensity.current
    val startOutputMs = timeline.mapSourceToOutput(caption.sourceStartMs) ?: return
    val endOutputMs = timeline.mapSourceToOutput(caption.sourceEndMs) ?: return
    if (endOutputMs <= startOutputMs) return

    val widthDp = with(density) { ((endOutputMs - startOutputMs) * pxPerMs).toDp() }
    val leftDp = with(density) { (startOutputMs * pxPerMs).toDp() }

    Box(
        modifier = Modifier
            .padding(start = leftDp, top = 4.dp)
            .height(CAPTION_BAR_HEIGHT_DP.dp)
            .width(widthDp)
            .clip(RoundedCornerShape(4.dp))
            .background(
                if (isSelected) MaterialTheme.colorScheme.primary
                else MaterialTheme.colorScheme.tertiary.copy(alpha = 0.85f)
            )
            .clickable { onTap() },
    ) {
        Text(
            caption.text.ifBlank { "…" },
            style = MaterialTheme.typography.labelSmall,
            color = Color.White,
            maxLines = 1,
            modifier = Modifier.padding(horizontal = 4.dp, vertical = 2.dp),
        )
    }
}

private const val DEFAULT_PX_PER_MS = 0.15f // 1초 = 150px
private const val TIMELINE_HEIGHT_DP = 120
private const val DIVIDER_HIT_WIDTH_DP = 14
private const val CAPTION_BAR_HEIGHT_DP = 22
