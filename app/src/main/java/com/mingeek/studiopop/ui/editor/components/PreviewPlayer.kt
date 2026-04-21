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
 * Timeline 세그먼트를 ExoPlayer MediaItem 리스트로 변환해 자동 concat 재생하는 Compose 프리뷰.
 * 세그먼트별로 sourceUri 가 달라도 이어서 재생됨.
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

    // 프리뷰는 raw segments 재생 (cut 은 export 에서만 적용됨 — 시각 힌트만 타임라인에 노출)
    LaunchedEffect(timeline.segments) {
        val items = timeline.segments.map { it.toMediaItem() }
        exoPlayer.setMediaItems(items, /* resetPosition = */ false)
        exoPlayer.prepare()
    }

    // 외부 seek 요청 반영
    LaunchedEffect(seekToOutputMs) {
        seekToOutputMs?.let { exoPlayer.seekToOutput(timeline, it) }
    }

    // 재생/일시정지
    LaunchedEffect(isPlaying) {
        exoPlayer.playWhenReady = isPlaying
    }

    // 현재 위치 폴링 → output ms 로 변환해 콜백
    LaunchedEffect(exoPlayer) {
        while (true) {
            val outputMs = exoPlayer.currentOutputMs(timeline)
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

private fun TimelineSegment.toMediaItem(): MediaItem =
    MediaItem.Builder()
        .setUri(sourceUri)
        .setClippingConfiguration(
            MediaItem.ClippingConfiguration.Builder()
                .setStartPositionMs(sourceStartMs)
                .setEndPositionMs(sourceEndMs)
                .build()
        )
        .build()

/**
 * output 기준 ms 로 seek. 해당 세그먼트 인덱스 + segment 내 offset 으로 변환.
 */
private fun Player.seekToOutput(timeline: Timeline, outputMs: Long) {
    var accumulated = 0L
    timeline.segments.forEachIndexed { idx, seg ->
        val d = seg.durationMs
        if (outputMs in accumulated..(accumulated + d)) {
            seekTo(idx, outputMs - accumulated)
            return
        }
        accumulated += d
    }
    // 범위 밖이면 끝으로
    seekTo(timeline.segments.lastIndex.coerceAtLeast(0), 0L)
}

/**
 * 현재 ExoPlayer 위치를 타임라인 output ms 로 변환.
 */
private fun Player.currentOutputMs(timeline: Timeline): Long {
    val idx = currentMediaItemIndex.coerceIn(0, timeline.segments.lastIndex.coerceAtLeast(0))
    val before = timeline.segments.take(idx).sumOf { it.durationMs }
    return before + currentPosition.coerceAtLeast(0L)
}
