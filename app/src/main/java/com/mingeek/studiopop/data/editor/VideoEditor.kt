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
import androidx.media3.transformer.Effects
import androidx.media3.transformer.ExportException
import androidx.media3.transformer.ExportResult
import androidx.media3.transformer.ProgressHolder
import androidx.media3.transformer.Transformer
import com.mingeek.studiopop.data.caption.Cue
import com.google.common.collect.ImmutableList
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import java.io.File
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

/**
 * Media3 Transformer 기반 영상 트림 + 자막 번인 내보내기.
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
        /**
         * 번인할 자막 큐. 시각은 **원본 영상 기준**.
         * null/빈 리스트이면 자막 없이 내보냄.
         */
        val captionCues: List<Cue>? = null,
        /**
         * 목표 비율 (가로/세로). 예: 9/16f = 숏츠.
         * null 이면 원본 비율 유지. 비-null 이면 center-crop 방식으로 변환.
         */
        val aspectRatio: Float? = null,
    )

    suspend fun export(
        spec: EditSpec,
        onProgress: (Float) -> Unit = {},
    ): Result<File> = withContext(Dispatchers.Main) {
        runCatching {
            val outFile = File(
                outputDir,
                "edit_${System.currentTimeMillis()}.mp4",
            )

            val mediaItem = MediaItem.Builder()
                .setUri(spec.sourceUri)
                .setClippingConfiguration(
                    MediaItem.ClippingConfiguration.Builder()
                        .setStartPositionMs(spec.trimStartMs)
                        .setEndPositionMs(spec.trimEndMs)
                        .build()
                )
                .build()

            val videoEffects: ImmutableList<Effect> = buildVideoEffects(spec)

            val editedMediaItem = EditedMediaItem.Builder(mediaItem)
                .setEffects(Effects(emptyList(), videoEffects))
                .build()

            val transformer = Transformer.Builder(context).build()

            awaitExport(transformer, editedMediaItem, outFile, onProgress)
            outFile
        }
    }

    private fun buildVideoEffects(spec: EditSpec): ImmutableList<Effect> {
        val builder = ImmutableList.Builder<Effect>()

        // 1) 비율 변환 (자막 오버레이보다 먼저 적용해야 크롭 후 영역에 자막이 올라감)
        spec.aspectRatio?.let { ratio ->
            builder.add(
                Presentation.createForAspectRatio(
                    ratio,
                    Presentation.LAYOUT_SCALE_TO_FIT_WITH_CROP,
                )
            )
        }

        // 2) 자막 번인 (트림 기준으로 오프셋 보정한 큐)
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

    private suspend fun awaitExport(
        transformer: Transformer,
        editedMediaItem: EditedMediaItem,
        outFile: File,
        onProgress: (Float) -> Unit,
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
        transformer.start(editedMediaItem, outFile.absolutePath)
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
