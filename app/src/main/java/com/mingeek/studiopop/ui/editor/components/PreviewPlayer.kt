package com.mingeek.studiopop.ui.editor.components

import android.content.Context
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.viewinterop.AndroidView
import androidx.media3.common.MediaItem
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.ui.PlayerView
import com.mingeek.studiopop.data.editor.Timeline
import com.mingeek.studiopop.data.editor.TimelineSegment
import kotlinx.coroutines.delay

/**
 * Timeline 의 **effectiveSegments()** (= cut 적용된 결과) 를 ExoPlayer MediaItem 리스트로
 * 변환해 자동 concat 재생. 프리뷰에서 실제로 cut 이 적용된 결과를 확인 가능.
 *
 * 플레이헤드는 여전히 **raw output ms** 로 표시되어야 타임라인의 cut 막대가 의미를 가지므로,
 * 폴링 시 effective player 위치 → source ms → raw output ms 로 역매핑한다.
 * 외부 seek 요청도 raw output ms → effective 위치로 매핑 (cut 구간으로 seek 시 다음 effective 시작으로 스냅).
 */
@UnstableApi
@Composable
fun PreviewPlayer(
    timeline: Timeline,
    onPositionChange: (Long) -> Unit,
    seekToOutputMs: Long?,
    isPlaying: Boolean,
    modifier: Modifier = Modifier,
) {
    val context = LocalContext.current
    val exoPlayer = remember { ExoPlayer.Builder(context).build() }

    val effectiveSegments = remember(timeline) { timeline.effectiveSegments() }

    LaunchedEffect(effectiveSegments) {
        val items = effectiveSegments.map { it.toMediaItem() }
        exoPlayer.setMediaItems(items, /* resetPosition = */ false)
        exoPlayer.prepare()
    }

    // 외부 seek 요청 반영 (raw output ms → effective 위치)
    LaunchedEffect(seekToOutputMs) {
        seekToOutputMs?.let { exoPlayer.seekToRawOutput(timeline, effectiveSegments, it) }
    }

    // 재생/일시정지
    LaunchedEffect(isPlaying) {
        exoPlayer.playWhenReady = isPlaying
    }

    // 현재 위치 폴링 → raw output ms 로 역매핑해 콜백
    LaunchedEffect(exoPlayer, effectiveSegments) {
        while (true) {
            val outputMs = exoPlayer.currentRawOutputMs(timeline, effectiveSegments)
            onPositionChange(outputMs)
            delay(50L)
        }
    }

    DisposableEffect(Unit) {
        onDispose { exoPlayer.release() }
    }

    AndroidView(
        factory = { ctx: Context ->
            PlayerView(ctx).apply {
                player = exoPlayer
                useController = false
            }
        },
        update = { it.player = exoPlayer },
        modifier = modifier,
    )
}

private fun TimelineSegment.toMediaItem(): MediaItem {
    // setStartPositionMs 는 non-negative 를 요구 — 상위 로직 버그로 음수가 흘러들어올 수 있어
    // 방어적으로 clamp 해 crash 방지.
    val start = sourceStartMs.coerceAtLeast(0L)
    val end = sourceEndMs.coerceAtLeast(start)
    return MediaItem.Builder()
        .setUri(sourceUri)
        .setClippingConfiguration(
            MediaItem.ClippingConfiguration.Builder()
                .setStartPositionMs(start)
                .setEndPositionMs(end)
                .build()
        )
        .build()
}

/**
 * raw output ms 로 seek. raw → source 변환 후 effective 세그먼트 중 해당 source 위치를 포함하는
 * 항목을 찾아 seek. cut 구간에 떨어지면 다음 effective 세그먼트의 시작으로 스냅.
 */
private fun Player.seekToRawOutput(
    timeline: Timeline,
    effective: List<TimelineSegment>,
    rawOutputMs: Long,
) {
    if (effective.isEmpty()) return
    val (rawSeg, sourceT) = timeline.mapOutputToSource(rawOutputMs) ?: run {
        seekTo(effective.lastIndex, 0L)
        return
    }
    effective.forEachIndexed { idx, eff ->
        if (eff.sourceUri == rawSeg.sourceUri && sourceT in eff.sourceStartMs..eff.sourceEndMs) {
            seekTo(idx, sourceT - eff.sourceStartMs)
            return
        }
    }
    // cut 구간이면 같은 sourceUri 의 다음 effective 시작으로 스냅
    val nextIdx = effective.indexOfFirst { eff ->
        eff.sourceUri == rawSeg.sourceUri && eff.sourceStartMs >= sourceT
    }
    if (nextIdx >= 0) {
        seekTo(nextIdx, 0L)
    } else {
        seekTo(effective.lastIndex.coerceAtLeast(0), 0L)
    }
}

/**
 * 현재 ExoPlayer 위치(effective) 를 raw output ms 로 역매핑.
 */
private fun Player.currentRawOutputMs(
    timeline: Timeline,
    effective: List<TimelineSegment>,
): Long {
    if (effective.isEmpty()) return 0L
    val idx = currentMediaItemIndex.coerceIn(0, effective.lastIndex)
    val effSeg = effective[idx]
    val sourceT = effSeg.sourceStartMs + currentPosition.coerceAtLeast(0L)
    return timeline.mapSourceToOutput(sourceT) ?: 0L
}
