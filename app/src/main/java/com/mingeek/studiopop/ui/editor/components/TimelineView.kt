package com.mingeek.studiopop.ui.editor.components

import android.graphics.Bitmap
import android.net.Uri
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.gestures.detectDragGesturesAfterLongPress
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.scrollBy
import androidx.compose.foundation.horizontalScroll
import androidx.compose.ui.input.pointer.PointerEventType
import androidx.compose.ui.input.pointer.pointerInput
import kotlinx.coroutines.launch
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
import com.mingeek.studiopop.data.editor.ImageLayer
import com.mingeek.studiopop.data.editor.MosaicRegion
import com.mingeek.studiopop.data.editor.SfxClip
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
    selectedCaptionId: String?,
    selectedImageLayerId: String? = null,
    selectedMosaicId: String? = null,
    pxPerMs: Float = DEFAULT_PX_PER_MS,
    /** 타임라인 높이. null 이면 orientation 에 따라 자동 (세로 200, 가로 260). */
    heightDp: Dp? = null,
    onCaptionTap: (String) -> Unit,
    onTextLayerTap: (String) -> Unit,
    onImageLayerTap: (String) -> Unit = {},
    onMosaicTap: (String) -> Unit = {},
    onSfxTap: (String) -> Unit = {},
    onPlayheadDrag: (Long) -> Unit,
    onDividerDrag: (prevSegId: String, nextSegId: String, sourceDeltaMs: Long) -> Unit,
    onCaptionResize: (id: String, startDeltaMs: Long, endDeltaMs: Long) -> Unit,
    onTextLayerResize: (id: String, startDeltaMs: Long, endDeltaMs: Long) -> Unit,
    onImageLayerResize: (id: String, startDeltaMs: Long, endDeltaMs: Long) -> Unit = { _, _, _ -> },
    onMosaicResize: (id: String, startDeltaMs: Long, endDeltaMs: Long) -> Unit = { _, _, _ -> },
    onCaptionTranslate: (id: String, deltaMs: Long) -> Unit,
    onTextLayerTranslate: (id: String, deltaMs: Long) -> Unit,
    onImageLayerTranslate: (id: String, deltaMs: Long) -> Unit = { _, _ -> },
    onMosaicTranslate: (id: String, deltaMs: Long) -> Unit = { _, _ -> },
    onSfxTranslate: (id: String, deltaMs: Long) -> Unit = { _, _ -> },
    onCutRangeTap: (String) -> Unit,
    onCutRangeResize: (id: String, startDeltaMs: Long, endDeltaMs: Long) -> Unit,
    onCutRangeTranslate: (id: String, deltaMs: Long) -> Unit,
    modifier: Modifier = Modifier,
) {
    val density = LocalDensity.current
    val config = androidx.compose.ui.platform.LocalConfiguration.current
    val resolvedHeight = heightDp ?: if (
        config.orientation == android.content.res.Configuration.ORIENTATION_LANDSCAPE
    ) TIMELINE_HEIGHT_LANDSCAPE_DP.dp else TIMELINE_HEIGHT_PORTRAIT_DP.dp
    val totalWidthDp: Dp = with(density) { (timeline.outputDurationMs * pxPerMs).toDp() }

    // DeX/마우스 사용자를 위해 세로 휠 스크롤을 가로로 매핑. 터치 환경에서는 영향 없음.
    val scrollState = androidx.compose.foundation.rememberScrollState()
    val scope = androidx.compose.runtime.rememberCoroutineScope()

    Box(
        modifier = modifier
            .height(resolvedHeight)
            .horizontalScroll(scrollState)
            .pointerInput(Unit) {
                awaitPointerEventScope {
                    while (true) {
                        val event = awaitPointerEvent()
                        if (event.type == PointerEventType.Scroll) {
                            val dy = event.changes.firstOrNull()?.scrollDelta?.y ?: 0f
                            if (dy != 0f) {
                                scope.launch {
                                    scrollState.scrollBy(dy * WHEEL_SCROLL_FACTOR)
                                }
                                event.changes.forEach { it.consume() }
                            }
                        }
                    }
                }
            },
    ) {
        Box(modifier = Modifier.width(totalWidthDp).fillMaxHeight()) {
            // 참고: 빈 타임라인 영역 탭은 아무 동작 안 함. 플레이헤드 이동은 오직
            // 하단의 dongle 드래그로만. 덕분에 부모 horizontalScroll 이 가로 스와이프를
            // 자연스럽게 소비해 타임라인 스크롤이 가능해짐.

            // 1) 세그먼트 + 썸네일 레이어
            Row(modifier = Modifier.fillMaxHeight()) {
                timeline.segments.forEachIndexed { idx, seg ->
                    val (totalSourceMs, strip) = frameStrips[seg.sourceUri]
                        ?: (seg.sourceEndMs to emptyList())
                    SegmentBlock(
                        segment = seg,
                        totalSourceMs = totalSourceMs,
                        frameStrip = strip,
                        pxPerMs = pxPerMs,
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
                        onTranslate = { d -> onTextLayerTranslate(layer.id, d) },
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
                        onTranslate = { d -> onCaptionTranslate(cap.id, d) },
                    )
                }
            }

            // 4) 삭제 범위 (빨간 라인) — 탭하면 제거(복원), 핸들·롱프레스로 조정
            Box(modifier = Modifier.fillMaxHeight()) {
                timeline.cutRanges.forEach { cut ->
                    OverlayBar(
                        id = cut.id,
                        text = "삭제",
                        sourceStartMs = cut.sourceStartMs,
                        sourceEndMs = cut.sourceEndMs,
                        timeline = timeline,
                        pxPerMs = pxPerMs,
                        topDp = CUT_BAR_TOP_DP.dp,
                        barColor = Color(0xFFE53935).copy(alpha = 0.85f),
                        isSelected = false,
                        onTap = { onCutRangeTap(cut.id) },
                        onResize = { s, e -> onCutRangeResize(cut.id, s, e) },
                        onTranslate = { d -> onCutRangeTranslate(cut.id, d) },
                    )
                }
            }

            // 5) 짤(ImageLayer) 레인 — 탭 선택, 양끝 핸들 리사이즈, 롱프레스 드래그 이동
            Box(modifier = Modifier.fillMaxHeight()) {
                timeline.imageLayers.forEach { img ->
                    OverlayBar(
                        id = img.id,
                        text = "🖼 짤",
                        sourceStartMs = img.sourceStartMs,
                        sourceEndMs = img.sourceEndMs,
                        timeline = timeline,
                        pxPerMs = pxPerMs,
                        topDp = STICKER_BAR_TOP_DP.dp,
                        barColor = Color(0xFF7E57C2).copy(alpha = 0.85f),
                        isSelected = img.id == selectedImageLayerId,
                        onTap = { onImageLayerTap(img.id) },
                        onResize = { s, e -> onImageLayerResize(img.id, s, e) },
                        onTranslate = { d -> onImageLayerTranslate(img.id, d) },
                    )
                }
            }

            // 6) 모자이크 레인 — 탭 선택, 양끝 핸들로 시간 범위 조정, 롱프레스 드래그 이동
            Box(modifier = Modifier.fillMaxHeight()) {
                timeline.mosaicRegions.forEach { region ->
                    OverlayBar(
                        id = region.id,
                        text = "🟦 모자이크",
                        sourceStartMs = region.sourceStartMs,
                        sourceEndMs = region.sourceEndMs,
                        timeline = timeline,
                        pxPerMs = pxPerMs,
                        topDp = MOSAIC_BAR_TOP_DP.dp,
                        barColor = Color(0xFF546E7A).copy(alpha = 0.85f),
                        isSelected = region.id == selectedMosaicId,
                        onTap = { onMosaicTap(region.id) },
                        onResize = { s, e -> onMosaicResize(region.id, s, e) },
                        onTranslate = { d -> onMosaicTranslate(region.id, d) },
                    )
                }
            }

            // 7) SFX 레인 — 탭/롱프레스 드래그로 시간 이동 (리사이즈는 오디오 길이 고정)
            Box(modifier = Modifier.fillMaxHeight()) {
                timeline.sfxClips.forEach { clip ->
                    OverlayBar(
                        id = clip.id,
                        text = "🔔 ${clip.label.ifBlank { "SFX" }}",
                        sourceStartMs = clip.sourceStartMs,
                        sourceEndMs = clip.sourceEndMs,
                        timeline = timeline,
                        pxPerMs = pxPerMs,
                        topDp = SFX_BAR_TOP_DP.dp,
                        barColor = Color(0xFF26A69A).copy(alpha = 0.85f),
                        isSelected = false,
                        onTap = { onSfxTap(clip.id) },
                        // SFX 는 오디오 길이가 고정이라 start/end delta 는 양쪽 모두 적용해 전체 이동
                        onResize = { s, _ -> onSfxTranslate(clip.id, s) },
                        onTranslate = { d -> onSfxTranslate(clip.id, d) },
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
            // stale closure 대응: pointerInput 람다는 key 변경 시에만 재생성되므로
            // 최신 playhead 값 참조를 위해 rememberUpdatedState 사용.
            val latestPlayhead by rememberUpdatedState(playheadOutputMs)
            val latestTotal by rememberUpdatedState(timeline.outputDurationMs)
            val latestPxPerMs by rememberUpdatedState(pxPerMs)

            // Edge clamp: playhead 가 타임라인의 시작/끝에 있을 때 40dp 터치 박스의
            // 절반이 부모 Box 바깥으로 나가 hit test 에 걸리지 않는 문제를 방지.
            // 터치 박스 자체는 항상 [0, totalWidth - touchSize] 안에 위치시키고,
            // 시각 원(18dp)은 박스 내부에서 실제 playhead 위치로 offset 해서 line 과 정렬 유지.
            val playheadPx = playheadOutputMs * pxPerMs
            val touchSizePx = with(density) { DONGLE_TOUCH_DP.dp.toPx() }
            val totalPx = with(density) { totalWidthDp.toPx() }
            val maxLeftPx = (totalPx - touchSizePx).coerceAtLeast(0f)
            val clampedLeftPx = (playheadPx - touchSizePx / 2f).coerceIn(0f, maxLeftPx)
            val dongleLeftDp = with(density) { clampedLeftPx.toDp() }

            val circleHalfPx = with(density) { (DONGLE_SIZE_DP / 2).dp.toPx() }
            val circleOffsetInBoxPx = (playheadPx - clampedLeftPx) - circleHalfPx
            val circleOffsetInBoxDp = with(density) { circleOffsetInBoxPx.toDp() }

            Box(
                modifier = Modifier
                    .offset(x = dongleLeftDp)
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
            ) {
                Box(
                    modifier = Modifier
                        .offset(x = circleOffsetInBoxDp)
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

/**
 * 세그먼트 = 순수 시각. 사용자 조작은 플레이헤드 seek(바깥 캡처) / 경계 드래그
 * (DraggableDivider) / 분할 버튼 / 삭제 버튼 경유로만. 이 블록 자체는 pointerInput
 * 도 clickable 도 없어서 아래 seek 캡처 레이어로 이벤트가 fall-through 함.
 */
@Composable
private fun SegmentBlock(
    segment: TimelineSegment,
    totalSourceMs: Long,
    frameStrip: List<Bitmap>,
    pxPerMs: Float,
) {
    val density = LocalDensity.current
    val widthDp = with(density) { (segment.durationMs * pxPerMs).toDp() }

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
 * - 가운데 탭 → [onTap] (편집 시트 오픈)
 * - 가운데 길게 누른 뒤 드래그 → [onTranslate] (duration 유지한 채 전체 평행이동)
 * - 양끝 [HANDLE_WIDTH_DP] 핸들 가로 드래그 → [onResize] (시작/끝 개별 조정)
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
    onTranslate: (deltaMs: Long) -> Unit,
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
                    // 두 개의 pointerInput 을 별도 체이닝:
                    //   ① 단순 탭 → 편집 시트
                    //   ② 롱프레스 후 드래그 → 평행이동 (duration 유지)
                    .pointerInput(id) {
                        detectTapGestures(onTap = { onTap() })
                    }
                    .pointerInput(id, pxPerMs) {
                        detectDragGesturesAfterLongPress { change, drag ->
                            change.consume()
                            val deltaMs = (drag.x / pxPerMs).toLong()
                            if (deltaMs != 0L) onTranslate(deltaMs)
                        }
                    },
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
/**
 * 세로 모드 기본 높이. 썸네일 + 7개 레인(텍스트/자막/삭제/짤/모자이크/SFX/예비) 수용.
 * 각 레인 간격은 26dp.
 */
private const val TIMELINE_HEIGHT_PORTRAIT_DP = 220
/** 가로 모드 기본 높이 (vertical 여유 공간 활용) */
private const val TIMELINE_HEIGHT_LANDSCAPE_DP = 280
private const val DIVIDER_HIT_WIDTH_DP = 14
private const val BAR_HEIGHT_DP = 22
private const val HANDLE_WIDTH_DP = 10
private const val DONGLE_SIZE_DP = 18            // 플레이헤드 dongle 시각 지름
private const val DONGLE_TOUCH_DP = 40           // 플레이헤드 dongle 터치 타깃 (WCAG min)
private const val TEXT_LAYER_BAR_TOP_DP = 4      // 상단 라인
private const val CAPTION_BAR_TOP_DP = 30        // 자막
private const val CUT_BAR_TOP_DP = 56            // 삭제 범위
private const val STICKER_BAR_TOP_DP = 82        // 짤(ImageLayer)
private const val MOSAIC_BAR_TOP_DP = 108        // 모자이크
private const val SFX_BAR_TOP_DP = 134           // 효과음
/** 마우스 휠 1 노치당 가로 스크롤 픽셀 배수. 너무 작으면 한참 돌려야 하고 너무 크면 급이동. */
private const val WHEEL_SCROLL_FACTOR = 60f
