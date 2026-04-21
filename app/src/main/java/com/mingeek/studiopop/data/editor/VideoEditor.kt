package com.mingeek.studiopop.data.editor

import android.content.Context
import android.net.Uri
import android.os.Handler
import android.os.Looper
import androidx.media3.common.Effect
import androidx.media3.common.MediaItem
import androidx.media3.common.util.UnstableApi
import androidx.media3.effect.OverlayEffect
import androidx.media3.effect.Presentation
import androidx.media3.effect.TextureOverlay
import androidx.media3.transformer.Composition
import androidx.media3.transformer.EditedMediaItem
import androidx.media3.transformer.EditedMediaItemSequence
import androidx.media3.transformer.Effects
import androidx.media3.transformer.ExportException
import androidx.media3.transformer.ExportResult
import androidx.media3.transformer.ProgressHolder
import androidx.media3.transformer.Transformer
import com.google.common.collect.ImmutableList
import com.mingeek.studiopop.data.caption.Cue
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import java.io.File
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

/**
 * Media3 Transformer 래퍼.
 *
 * - [export]         : 단일 트림 + 비율 변환 + 자막 번인 (숏츠 재활용).
 * - [exportTimeline] : 여러 세그먼트 concat + 멀티 레이어 자막/텍스트 + 전환 + BGM.
 */
@UnstableApi
class VideoEditor(
    private val context: Context,
    private val outputDir: File,
) {

    data class EditSpec(
        val sourceUri: Uri,
        val trimStartMs: Long,
        val trimEndMs: Long,
        val captionCues: List<Cue>? = null,
        val aspectRatio: Float? = null,
    )

    suspend fun export(
        spec: EditSpec,
        onProgress: (Float) -> Unit = {},
    ): Result<File> = withContext(Dispatchers.Main) {
        runCatching {
            val outFile = File(outputDir, "edit_${System.currentTimeMillis()}.mp4")

            val mediaItem = MediaItem.Builder()
                .setUri(spec.sourceUri)
                .setClippingConfiguration(
                    MediaItem.ClippingConfiguration.Builder()
                        .setStartPositionMs(spec.trimStartMs)
                        .setEndPositionMs(spec.trimEndMs)
                        .build()
                )
                .build()

            val videoEffects: ImmutableList<Effect> = buildSimpleVideoEffects(spec)
            val editedMediaItem = EditedMediaItem.Builder(mediaItem)
                .setEffects(Effects(emptyList(), videoEffects))
                .build()

            val transformer = Transformer.Builder(context).build()
            awaitExport(transformer, outFile, onProgress) {
                transformer.start(editedMediaItem, outFile.absolutePath)
            }
            outFile
        }
    }

    /**
     * 타임라인 기반 export.
     * - 세그먼트들을 (각 segment.sourceUri 의 [start, end] 구간) concat
     * - 자막(style별 그룹) 과 텍스트 레이어를 멀티 오버레이로 합성
     * - TransitionSettings.enabled 이면 세그먼트 경계에서 페이드-투-블랙
     * - AudioTrack 이 있으면 원본 오디오 제거 + BGM sequence 추가
     */
    suspend fun exportTimeline(
        timeline: Timeline,
        aspectRatio: Float? = null,
        onProgress: (Float) -> Unit = {},
    ): Result<File> = withContext(Dispatchers.Main) {
        runCatching {
            require(timeline.segments.isNotEmpty()) { "빈 타임라인은 export 불가" }
            val outFile = File(outputDir, "edit_${System.currentTimeMillis()}.mp4")

            val removeOriginalAudio = timeline.audioTrack?.replaceOriginal == true
            val videoItems = timeline.segments.map { seg ->
                val mediaItem = MediaItem.Builder()
                    .setUri(seg.sourceUri)
                    .setClippingConfiguration(
                        MediaItem.ClippingConfiguration.Builder()
                            .setStartPositionMs(seg.sourceStartMs)
                            .setEndPositionMs(seg.sourceEndMs)
                            .build()
                    )
                    .build()
                EditedMediaItem.Builder(mediaItem)
                    .setRemoveAudio(removeOriginalAudio)
                    .build()
            }
            val videoSequence = EditedMediaItemSequence(videoItems)

            val sequences = mutableListOf(videoSequence)

            // BGM: 영상 전체 길이에 맞춰 한 번만 재생 (필요 시 loop)
            timeline.audioTrack?.let { track ->
                val bgmItem = EditedMediaItem.Builder(
                    MediaItem.fromUri(track.uri)
                ).setRemoveVideo(true).build()
                sequences += EditedMediaItemSequence(listOf(bgmItem), /* isLooping = */ true)
            }

            val overlays = buildOverlayList(timeline)
            val videoEffects = ImmutableList.Builder<Effect>().apply {
                aspectRatio?.let {
                    add(
                        Presentation.createForAspectRatio(
                            it,
                            Presentation.LAYOUT_SCALE_TO_FIT_WITH_CROP,
                        )
                    )
                }
                if (overlays.isNotEmpty()) {
                    add(OverlayEffect(ImmutableList.copyOf(overlays)))
                }
            }.build()

            val composition = Composition.Builder(sequences.toList())
                .setEffects(Effects(emptyList(), videoEffects))
                .build()

            val transformer = Transformer.Builder(context).build()
            awaitExport(transformer, outFile, onProgress) {
                transformer.start(composition, outFile.absolutePath)
            }
            outFile
        }
    }

    private fun buildSimpleVideoEffects(spec: EditSpec): ImmutableList<Effect> {
        val builder = ImmutableList.Builder<Effect>()
        spec.aspectRatio?.let { ratio ->
            builder.add(
                Presentation.createForAspectRatio(
                    ratio,
                    Presentation.LAYOUT_SCALE_TO_FIT_WITH_CROP,
                )
            )
        }
        val cues = spec.captionCues.orEmpty()
        if (cues.isNotEmpty()) {
            val shifted = cues
                .filter { it.endMs > spec.trimStartMs && it.startMs < spec.trimEndMs }
                .map { c ->
                    c.copy(
                        startMs = (c.startMs - spec.trimStartMs).coerceAtLeast(0L),
                        endMs = (c.endMs - spec.trimStartMs)
                            .coerceAtMost(spec.trimEndMs - spec.trimStartMs),
                    )
                }
            if (shifted.isNotEmpty()) {
                builder.add(OverlayEffect(ImmutableList.of(CaptionOverlay(shifted))))
            }
        }
        return builder.build()
    }

    /**
     * 타임라인으로부터 프레임 오버레이 리스트 생성.
     * - 같은 스타일의 자막은 하나의 CaptionOverlay 로 묶음
     * - 텍스트 레이어도 각자 CaptionOverlay 로 추가
     * - 전환이 켜져 있으면 FadeAtBoundariesOverlay 추가
     */
    private fun buildOverlayList(timeline: Timeline): List<TextureOverlay> {
        val overlays = mutableListOf<TextureOverlay>()

        // 자막: style 별 그룹핑 → 그룹마다 CaptionOverlay
        val captionCuesByStyle = timeline.captionsToOutputCuesByStyle()
        for ((style, cues) in captionCuesByStyle) {
            if (cues.isNotEmpty()) {
                overlays += CaptionOverlay(cues, style)
            }
        }

        // 텍스트 레이어: 레이어별 (style 이 다양할 수 있어서) 각자 오버레이
        val textLayerOutputs = timeline.textLayersToOutputs()
        for ((style, cues) in textLayerOutputs) {
            if (cues.isNotEmpty()) {
                overlays += CaptionOverlay(cues, style)
            }
        }

        // 전환
        if (timeline.transitions.enabled && timeline.segments.size > 1) {
            val boundaries = mutableListOf<Long>()
            var acc = 0L
            for (i in 0 until timeline.segments.size - 1) {
                acc += timeline.segments[i].durationMs
                boundaries += acc
            }
            overlays += FadeAtBoundariesOverlay(
                boundariesMs = boundaries,
                halfDurationMs = (timeline.transitions.durationMs / 2).coerceAtLeast(50L),
            )
        }

        return overlays
    }

    /**
     * 자막을 스타일별로 그룹핑해 각 그룹을 출력 시간 기준 Cue 로 변환.
     */
    private fun Timeline.captionsToOutputCuesByStyle(): Map<CaptionStyle, List<Cue>> {
        if (captions.isEmpty()) return emptyMap()
        val groups = captions.groupBy { it.style }
        return groups.mapValues { (_, caps) ->
            captionsToOutputCues(caps.map { it.sourceStartMs to it.sourceEndMs to it.text })
        }
    }

    /**
     * 텍스트 레이어 → 스타일별 출력 Cue.
     */
    private fun Timeline.textLayersToOutputs(): Map<CaptionStyle, List<Cue>> {
        if (textLayers.isEmpty()) return emptyMap()
        val groups = textLayers.groupBy { it.style }
        return groups.mapValues { (_, layers) ->
            captionsToOutputCues(layers.map { it.sourceStartMs to it.sourceEndMs to it.text })
        }
    }

    /**
     * (sourceStart, sourceEnd, text) 리스트를 현재 세그먼트 구성에 맞춰 출력 Cue 로 변환.
     * 한 입력이 여러 세그먼트에 걸치면 조각으로 분리됨.
     */
    private fun Timeline.captionsToOutputCues(
        inputs: List<Pair<Pair<Long, Long>, String>>,
    ): List<Cue> {
        val result = mutableListOf<Cue>()
        var accumulated = 0L
        for (seg in segments) {
            for ((range, text) in inputs) {
                val (srcStart, srcEnd) = range
                val overlapStart = maxOf(srcStart, seg.sourceStartMs)
                val overlapEnd = minOf(srcEnd, seg.sourceEndMs)
                if (overlapEnd > overlapStart) {
                    val outStart = accumulated + (overlapStart - seg.sourceStartMs)
                    val outEnd = accumulated + (overlapEnd - seg.sourceStartMs)
                    result += Cue(
                        index = result.size + 1,
                        startMs = outStart,
                        endMs = outEnd,
                        text = text,
                    )
                }
            }
            accumulated += seg.durationMs
        }
        return result.sortedBy { it.startMs }
    }

    private suspend fun awaitExport(
        transformer: Transformer,
        outFile: File,
        onProgress: (Float) -> Unit,
        start: () -> Unit,
    ): ExportResult = suspendCancellableCoroutine { cont ->
        val handler = Handler(Looper.getMainLooper())
        val progressHolder = ProgressHolder()

        val progressRunnable = object : Runnable {
            override fun run() {
                if (!cont.isActive) return
                val state = transformer.getProgress(progressHolder)
                if (state == Transformer.PROGRESS_STATE_AVAILABLE) {
                    onProgress(progressHolder.progress / 100f)
                }
                handler.postDelayed(this, PROGRESS_POLL_MS)
            }
        }

        val listener = object : Transformer.Listener {
            override fun onCompleted(composition: Composition, exportResult: ExportResult) {
                handler.removeCallbacks(progressRunnable)
                onProgress(1f)
                if (cont.isActive) cont.resume(exportResult)
            }

            override fun onError(
                composition: Composition,
                exportResult: ExportResult,
                exportException: ExportException,
            ) {
                handler.removeCallbacks(progressRunnable)
                runCatching { outFile.delete() }
                if (cont.isActive) cont.resumeWithException(exportException)
            }
        }

        transformer.addListener(listener)
        start()
        handler.postDelayed(progressRunnable, PROGRESS_POLL_MS)

        cont.invokeOnCancellation {
            handler.removeCallbacks(progressRunnable)
            runCatching { transformer.cancel() }
            runCatching { outFile.delete() }
        }
    }

    companion object {
        private const val PROGRESS_POLL_MS = 200L
    }
}
