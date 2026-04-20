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
 * - [exportTimeline] : 여러 세그먼트를 concat + source-time 자막을 출력 기준으로 재매핑.
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
     * 여러 세그먼트를 이어붙여 export. 자막은 source-time 기준으로 저장돼 있다가
     * 출력 타임라인 시각으로 재계산해 번인.
     */
    suspend fun exportTimeline(
        sourceUri: Uri,
        timeline: Timeline,
        aspectRatio: Float? = null,
        onProgress: (Float) -> Unit = {},
    ): Result<File> = withContext(Dispatchers.Main) {
        runCatching {
            require(timeline.segments.isNotEmpty()) { "빈 타임라인은 export 불가" }
            val outFile = File(outputDir, "edit_${System.currentTimeMillis()}.mp4")

            val editedItems = timeline.segments.map { seg ->
                val mediaItem = MediaItem.Builder()
                    .setUri(sourceUri)
                    .setClippingConfiguration(
                        MediaItem.ClippingConfiguration.Builder()
                            .setStartPositionMs(seg.sourceStartMs)
                            .setEndPositionMs(seg.sourceEndMs)
                            .build()
                    )
                    .build()
                EditedMediaItem.Builder(mediaItem).build()
            }

            val sequence = EditedMediaItemSequence(editedItems)
            val outputCues = timeline.toOutputCues()
            val compEffects = buildCompositionEffects(aspectRatio, outputCues)

            val composition = Composition.Builder(listOf(sequence))
                .setEffects(Effects(emptyList(), compEffects))
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

    private fun buildCompositionEffects(
        aspectRatio: Float?,
        outputCues: List<Cue>,
    ): ImmutableList<Effect> {
        val builder = ImmutableList.Builder<Effect>()
        aspectRatio?.let { ratio ->
            builder.add(
                Presentation.createForAspectRatio(
                    ratio,
                    Presentation.LAYOUT_SCALE_TO_FIT_WITH_CROP,
                )
            )
        }
        if (outputCues.isNotEmpty()) {
            builder.add(OverlayEffect(ImmutableList.of(CaptionOverlay(outputCues))))
        }
        return builder.build()
    }

    /**
     * source-time 자막 → 출력 타임라인 기준으로 재계산.
     * 한 자막이 여러 세그먼트에 걸치면 조각으로 나뉨.
     */
    private fun Timeline.toOutputCues(): List<Cue> {
        if (captions.isEmpty()) return emptyList()
        val result = mutableListOf<Cue>()
        var accumulated = 0L
        for (seg in segments) {
            for (cap in captions) {
                val overlapStart = maxOf(cap.sourceStartMs, seg.sourceStartMs)
                val overlapEnd = minOf(cap.sourceEndMs, seg.sourceEndMs)
                if (overlapEnd > overlapStart) {
                    val outStart = accumulated + (overlapStart - seg.sourceStartMs)
                    val outEnd = accumulated + (overlapEnd - seg.sourceStartMs)
                    result += Cue(
                        index = result.size + 1,
                        startMs = outStart,
                        endMs = outEnd,
                        text = cap.text,
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
