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
import androidx.media3.common.VideoSize
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.ui.AspectRatioFrameLayout
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
    /**
     * 디코더가 인식한 영상의 화면 표시용 가로세로 비율 (rotation·PAR 반영). 영상 size 가
     * 결정되거나 다음 세그먼트로 바뀔 때마다 호출. 메타데이터(MediaMetadataRetriever) 가 실패하는
     * content:// 등 케이스에서도 항상 정확한 값을 받을 수 있어 프리뷰 박스 비율 동기화의 정답값.
     */
    onVideoAspectChange: (Float) -> Unit = {},
    modifier: Modifier = Modifier,
) {
    val context = LocalContext.current
    val exoPlayer = remember { ExoPlayer.Builder(context).build() }
    /**
     * BGM / 추출된 vocals WAV 등 [Timeline.audioTrack] 을 프리뷰에서 들리게 하는 서브 플레이어.
     * 메인과 재생·정지 상태만 sync. seek 는 메인만 — BGM 은 자기 페이스로 loop (export 와 유사).
     */
    val bgmPlayer = remember {
        ExoPlayer.Builder(context).build().apply {
            repeatMode = Player.REPEAT_MODE_ONE
        }
    }

    val effectiveSegments = remember(timeline) { timeline.effectiveSegments() }
    val effectiveRawStarts = remember(timeline) { timeline.effectiveRawOutputStarts() }

    LaunchedEffect(effectiveSegments) {
        val items = effectiveSegments.map { it.toMediaItem() }
        exoPlayer.setMediaItems(items, /* resetPosition = */ false)
        exoPlayer.prepare()
    }

    // 원본 볼륨 / 대체 여부 → 메인 player.volume. ExoPlayer.volume 은 0..1 로 clamp.
    // (>1 증폭은 ExoPlayer 단독으론 안 되고 AudioProcessor 체인 커스터마이즈가 필요 — export 는 정확 반영.)
    val originalReplace = timeline.audioTrack?.replaceOriginal == true
    LaunchedEffect(timeline.originalVolume, originalReplace) {
        exoPlayer.volume = if (originalReplace) 0f
            else timeline.originalVolume.coerceIn(0f, 1f)
    }

    // BGM / vocal 추출 WAV 소스 교체.
    val bgmUri = timeline.audioTrack?.uri?.toString()
    LaunchedEffect(bgmUri) {
        if (bgmUri != null) {
            bgmPlayer.setMediaItem(MediaItem.fromUri(bgmUri))
            bgmPlayer.prepare()
            bgmPlayer.seekTo(0L)
        } else {
            bgmPlayer.stop()
            bgmPlayer.clearMediaItems()
        }
    }

    // BGM 볼륨
    val bgmVolume = timeline.audioTrack?.volume ?: 0f
    LaunchedEffect(bgmVolume, bgmUri) {
        bgmPlayer.volume = bgmVolume.coerceIn(0f, 1f)
    }

    // 외부 seek 요청 반영 (raw output ms → effective 위치). BGM 은 평소 자기 페이스로 loop 하되
    // "처음으로" 되감기(output=0) 시엔 BGM 도 0 으로 맞춰 export 와 동일한 시작 느낌 제공.
    LaunchedEffect(seekToOutputMs) {
        seekToOutputMs?.let {
            exoPlayer.seekToRawOutput(effectiveSegments, effectiveRawStarts, it)
            if (it == 0L) bgmPlayer.seekTo(0L)
        }
    }

    // 재생/일시정지 — 양쪽 동시 제어. bgm 은 audioTrack 있을 때만 재생.
    LaunchedEffect(isPlaying, bgmUri) {
        exoPlayer.playWhenReady = isPlaying
        bgmPlayer.playWhenReady = isPlaying && bgmUri != null
    }

    // 현재 위치 폴링 → raw output ms 로 역매핑해 콜백
    LaunchedEffect(exoPlayer, effectiveSegments) {
        while (true) {
            val outputMs = exoPlayer.currentRawOutputMs(effectiveSegments, effectiveRawStarts)
            onPositionChange(outputMs)
            delay(50L)
        }
    }

    // ExoPlayer 의 라이프사이클 정리: 반드시 단일 DisposableEffect 로 묶어 dispose 순서를 명시.
    // (LIFO 순서 문제 회피 — removeListener 는 release 이전에 일어나야 안전.)
    // listener: 디코더가 영상 size 를 인식할 때마다(첫 프레임 직전 + 클립 자동 전환 시) 콜백 호출.
    // VideoSize 는 회전 반영된 출력 좌표계 W/H 이고 pixelWidthHeightRatio 로 비정사각 픽셀(아나모픽
    // 등)까지 보정 — 프리뷰 박스 비율의 ground truth.
    DisposableEffect(Unit) {
        val listener = object : Player.Listener {
            override fun onVideoSizeChanged(videoSize: VideoSize) {
                if (videoSize.width <= 0 || videoSize.height <= 0) return
                val par = if (videoSize.pixelWidthHeightRatio > 0f)
                    videoSize.pixelWidthHeightRatio else 1f
                val ratio = (videoSize.width * par) / videoSize.height.toFloat()
                if (ratio > 0f && ratio.isFinite()) onVideoAspectChange(ratio)
            }
        }
        exoPlayer.addListener(listener)
        onDispose {
            exoPlayer.removeListener(listener)
            exoPlayer.release()
            bgmPlayer.release()
        }
    }

    AndroidView(
        factory = { ctx: Context ->
            PlayerView(ctx).apply {
                player = exoPlayer
                useController = false
                // 외곽 Compose Box 가 이미 소스 영상 비율로 그려지므로 surface 는 박스를 꽉 채우면 됨.
                // FIT 모드는 surface_view 가 다른 크기로 늘어나는 일부 단말 케이스를 막아주는 보험.
                resizeMode = AspectRatioFrameLayout.RESIZE_MODE_FIT
            }
        },
        update = {
            it.player = exoPlayer
            it.resizeMode = AspectRatioFrameLayout.RESIZE_MODE_FIT
        },
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
 * raw output ms 로 seek. effectiveRawOutputStarts 기반 인덱스 매핑 —
 * 같은 sourceUri 의 raw 세그먼트가 여러 개여도 인덱스로 구분되어 안전.
 * rawOutputMs 가 cut 구간(effective 사이 gap) 에 떨어지면 다음 effective 시작으로 스냅.
 */
private fun Player.seekToRawOutput(
    effective: List<TimelineSegment>,
    rawStarts: List<Long>,
    rawOutputMs: Long,
) {
    if (effective.isEmpty()) return
    effective.forEachIndexed { idx, eff ->
        val start = rawStarts[idx]
        val end = start + eff.durationMs
        if (rawOutputMs in start..end) {
            seekTo(idx, (rawOutputMs - start).coerceAtLeast(0L))
            return
        }
    }
    val nextIdx = rawStarts.indexOfFirst { it >= rawOutputMs }
    if (nextIdx >= 0) {
        seekTo(nextIdx, 0L)
    } else {
        seekTo(effective.lastIndex.coerceAtLeast(0), 0L)
    }
}

/**
 * 현재 ExoPlayer 위치(effective) 를 raw output ms 로 역매핑.
 * `currentMediaItemIndex` 기반이라 같은 URI 가 여러 번 붙어도 올바른 raw 좌표로 매핑됨.
 */
private fun Player.currentRawOutputMs(
    effective: List<TimelineSegment>,
    rawStarts: List<Long>,
): Long {
    if (effective.isEmpty()) return 0L
    val idx = currentMediaItemIndex.coerceIn(0, effective.lastIndex)
    return rawStarts[idx] + currentPosition.coerceAtLeast(0L)
}
