package com.mingeek.studiopop.ui.editor.components

import android.graphics.Bitmap
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
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
    sourceDurationMs: Long,
    frameStrip: List<Bitmap>,
    playheadOutputMs: Long,
    selectedSegmentId: String?,
    selectedCaptionId: String?,
    pxPerMs: Float = DEFAULT_PX_PER_MS,
    onSegmentTap: (String) -> Unit,
    onCaptionTap: (String) -> Unit,
    onPlayheadDrag: (Long) -> Unit,
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
                timeline.segments.forEach { seg ->
                    SegmentBlock(
                        segment = seg,
                        totalSourceMs = sourceDurationMs,
                        frameStrip = frameStrip,
                        isSelected = seg.id == selectedSegmentId,
                        pxPerMs = pxPerMs,
                        onTap = { onSegmentTap(seg.id) },
                    )
                    // 세그먼트 사이 구분선 (마지막 제외)
                    if (seg != timeline.segments.last()) {
                        Spacer(
                            Modifier
                                .width(SPLIT_DIVIDER_WIDTH_DP.dp)
                                .fillMaxHeight()
                                .background(Color(0xFFFFAB00))
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
private const val SPLIT_DIVIDER_WIDTH_DP = 2
private const val CAPTION_BAR_HEIGHT_DP = 22
