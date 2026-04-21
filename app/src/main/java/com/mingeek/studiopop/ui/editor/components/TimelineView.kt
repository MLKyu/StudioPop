package com.mingeek.studiopop.ui.editor.components

import android.graphics.Bitmap
import android.net.Uri
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.ui.Alignment
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
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
import com.mingeek.studiopop.data.editor.TextLayer
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
    onTextLayerTap: (String) -> Unit,
    onPlayheadDrag: (Long) -> Unit,
    onDividerDrag: (prevSegId: String, nextSegId: String, sourceDeltaMs: Long) -> Unit,
    onCaptionResize: (id: String, startDeltaMs: Long, endDeltaMs: Long) -> Unit,
    onTextLayerResize: (id: String, startDeltaMs: Long, endDeltaMs: Long) -> Unit,
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
            // [최하층] 플레이헤드 탭/드래그 캡처.
            // bars·segments 보다 먼저 그려져 아래 레이어가 되므로, 위의 interactive
            // 요소(bar·segment·handle) 가 이벤트를 먼저 받고 안 쓰면 여기로 fall-through.
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .pointerInput(timeline.outputDurationMs, pxPerMs) {
                        awaitEachGesture {
                            val down = awaitFirstDown()
                            val initMs = (down.position.x / pxPerMs).toLong()
                                .coerceIn(0L, timeline.outputDurationMs)
                            onPlayheadDrag(initMs)
                            while (true) {
                                val event = awaitPointerEvent()
                                val c = event.changes.firstOrNull() ?: break
                                if (!c.pressed) break
                                val ms = (c.position.x / pxPerMs).toLong()
                                    .coerceIn(0L, timeline.outputDurationMs)
                                onPlayheadDrag(ms)
                                c.consume()
                            }
                        }
                    }
            )

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

            // 2) 텍스트 레이어 (상단 라인)
            Box(modifier = Modifier.fillMaxHeight()) {
                timeline.textLayers.forEach { layer ->
                    OverlayBar(
                        id = layer.id,
                        text = layer.text.ifBlank { "텍스트" },
                        sourceStartMs = layer.sourceStartMs,
                        sourceEndMs = layer.sourceEndMs,
                        timeline = timeline,
                        pxPerMs = pxPerMs,
                        topDp = TEXT_LAYER_BAR_TOP_DP.dp,
                        barColor = Color(0xFFFFB300),
                        isSelected = layer.id == selectedCaptionId,
                        onTap = { onTextLayerTap(layer.id) },
                        onResize = { s, e -> onTextLayerResize(layer.id, s, e) },
                    )
                }
            }

            // 3) 자막 막대 (하단 라인)
            Box(modifier = Modifier.fillMaxHeight()) {
                timeline.captions.forEach { cap ->
                    OverlayBar(
                        id = cap.id,
                        text = cap.text.ifBlank { "…" },
                        sourceStartMs = cap.sourceStartMs,
                        sourceEndMs = cap.sourceEndMs,
                        timeline = timeline,
                        pxPerMs = pxPerMs,
                        topDp = CAPTION_BAR_TOP_DP.dp,
                        barColor = MaterialTheme.colorScheme.tertiary.copy(alpha = 0.85f),
                        isSelected = cap.id == selectedCaptionId,
                        onTap = { onCaptionTap(cap.id) },
                        onResize = { s, e -> onCaptionResize(cap.id, s, e) },
                    )
                }
            }

            // [최상단 — visual] 플레이헤드 핑크 라인. pointerInput 없어 이벤트 통과.
            Canvas(modifier = Modifier.fillMaxHeight().width(totalWidthDp)) {
                val x = playheadOutputMs * pxPerMs
                drawLine(
                    color = Color(0xFFFF4081),
                    start = Offset(x, with(density) { DONGLE_SIZE_DP.dp.toPx() }),
                    end = Offset(x, size.height),
                    strokeWidth = with(density) { 2.dp.toPx() },
                )
            }

            // [최상단 — interactive] 드래그 가능한 플레이헤드 dongle.
            //
            // 주의: pointerInput 의 람다는 key 가 바뀔 때만 새로 만들어지므로, 안쪽
            // playheadOutputMs 참조가 stale 하면 델타가 항상 초기값 기준으로
            // 계산돼 첫 이벤트 이후 고정된다. rememberUpdatedState 로 최신값 참조.
            val latestPlayhead by rememberUpdatedState(playheadOutputMs)
            val latestTotal by rememberUpdatedState(timeline.outputDurationMs)
            val latestPxPerMs by rememberUpdatedState(pxPerMs)
            val playheadXDp = with(density) { (playheadOutputMs * pxPerMs).toDp() }

            // 터치 타깃은 40dp (WCAG 최소), 내부에 18dp 원형 dongle 을 그려 시각 유지.
            Box(
                modifier = Modifier
                    .offset(x = playheadXDp - (DONGLE_TOUCH_DP / 2).dp)
                    .size(DONGLE_TOUCH_DP.dp)
                    .pointerInput(Unit) {
                        detectDragGestures { change, drag ->
                            change.consume()
                            val deltaMs = (drag.x / latestPxPerMs).toLong()
                            val newMs = (latestPlayhead + deltaMs)
                                .coerceIn(0L, latestTotal)
                            onPlayheadDrag(newMs)
                        }
                    },
                contentAlignment = Alignment.TopCenter,
            ) {
                Box(
                    modifier = Modifier
                        .size(DONGLE_SIZE_DP.dp)
                        .clip(CircleShape)
                        .background(Color(0xFFFF4081))
                )
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

/**
 * 자막·텍스트 레이어 공용 막대.
 * 양끝 [HANDLE_WIDTH_DP] 영역은 드래그해서 시간 범위를 조절 가능.
 * 가운데 탭 하면 편집 시트 오픈.
 */
@Composable
private fun OverlayBar(
    id: String,
    text: String,
    sourceStartMs: Long,
    sourceEndMs: Long,
    timeline: Timeline,
    pxPerMs: Float,
    topDp: androidx.compose.ui.unit.Dp,
    barColor: Color,
    isSelected: Boolean,
    onTap: () -> Unit,
    onResize: (startDeltaMs: Long, endDeltaMs: Long) -> Unit,
) {
    val density = LocalDensity.current
    val startOutputMs = timeline.mapSourceToOutput(sourceStartMs) ?: return
    val endOutputMs = timeline.mapSourceToOutput(sourceEndMs) ?: return
    if (endOutputMs <= startOutputMs) return

    val widthDp = with(density) { ((endOutputMs - startOutputMs) * pxPerMs).toDp() }
    val leftDp = with(density) { (startOutputMs * pxPerMs).toDp() }
    val canResize = widthDp > (HANDLE_WIDTH_DP * 2 + 8).dp

    Box(
        modifier = Modifier
            .padding(start = leftDp, top = topDp)
            .height(BAR_HEIGHT_DP.dp)
            .width(widthDp)
            .clip(RoundedCornerShape(4.dp))
            .background(if (isSelected) MaterialTheme.colorScheme.primary else barColor),
    ) {
        Row(modifier = Modifier.fillMaxHeight()) {
            if (canResize) {
                ResizeHandle(
                    modifier = Modifier.width(HANDLE_WIDTH_DP.dp).fillMaxHeight(),
                    pxPerMs = pxPerMs,
                ) { deltaMs -> onResize(deltaMs, 0L) }
            }
            Box(
                modifier = Modifier
                    .weight(1f)
                    .fillMaxHeight()
                    .clickable { onTap() },
            ) {
                Text(
                    text,
                    style = MaterialTheme.typography.labelSmall,
                    color = Color.White,
                    maxLines = 1,
                    modifier = Modifier.padding(horizontal = 4.dp, vertical = 2.dp),
                )
            }
            if (canResize) {
                ResizeHandle(
                    modifier = Modifier.width(HANDLE_WIDTH_DP.dp).fillMaxHeight(),
                    pxPerMs = pxPerMs,
                ) { deltaMs -> onResize(0L, deltaMs) }
            }
        }
    }
}

@Composable
private fun ResizeHandle(
    modifier: Modifier = Modifier,
    pxPerMs: Float,
    onDrag: (deltaMs: Long) -> Unit,
) {
    Box(
        modifier = modifier
            .background(Color.White.copy(alpha = 0.3f))
            .pointerInput(pxPerMs) {
                detectDragGestures { change, drag ->
                    change.consume()
                    val dms = (drag.x / pxPerMs).toLong()
                    if (dms != 0L) onDrag(dms)
                }
            }
    )
}

private const val DEFAULT_PX_PER_MS = 0.15f // 1초 = 150px
private const val TIMELINE_HEIGHT_DP = 120
private const val DIVIDER_HIT_WIDTH_DP = 14
private const val BAR_HEIGHT_DP = 22
private const val HANDLE_WIDTH_DP = 10
private const val DONGLE_SIZE_DP = 18            // 플레이헤드 dongle 시각 지름
private const val DONGLE_TOUCH_DP = 40           // 플레이헤드 dongle 터치 타깃 (WCAG min)
private const val TEXT_LAYER_BAR_TOP_DP = 4      // 상단 라인
private const val CAPTION_BAR_TOP_DP = 30        // 하단 라인 (TextLayer 바로 아래)
