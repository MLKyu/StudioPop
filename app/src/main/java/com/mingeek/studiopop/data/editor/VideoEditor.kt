package com.mingeek.studiopop.data.editor

import android.content.Context
import android.media.MediaMetadataRetriever
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
import com.mingeek.studiopop.data.media.MediaStoreVideoPublisher
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
    /**
     * export 성공 시 결과 영상을 MediaStore.Video 에 복사 등록해 갤러리/PhotoPicker 에 노출.
     * null 이거나 Android 9 이하면 no-op. 실패해도 export 결과는 그대로 반환.
     */
    private val mediaStorePublisher: MediaStoreVideoPublisher? = null,
    /** SFX 지연 재생용 무음 WAV 생성기. 미지정 시 context.cacheDir 에 생성. */
    private val silenceGenerator: SilenceAudioGenerator =
        SilenceAudioGenerator(context.cacheDir),
) {

    /** 주어진 ms 길이의 무음 오디오 EditedMediaItem 생성. SFX 앞에 prefix 로 사용. */
    private fun buildSilenceAudioItem(durationMs: Long): EditedMediaItem {
        val uri = silenceGenerator.generate(durationMs)
        return EditedMediaItem.Builder(
            MediaItem.Builder()
                .setUri(uri)
                .setClippingConfiguration(
                    MediaItem.ClippingConfiguration.Builder()
                        .setStartPositionMs(0L)
                        .setEndPositionMs(durationMs)
                        .build()
                )
                .build()
        )
            .setRemoveVideo(true)
            .build()
    }

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
            publishToGalleryQuietly(outFile, "StudioPop_edit_${System.currentTimeMillis()}")
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
        /**
         * R5c3b: BGM 자동 더킹 KeyframeTrack. 비거나 null 이면 기존 단일 볼륨([VolumeAudioProcessor])
         * 그대로. 트랙이 들어오면 [DuckingAudioProcessor] 로 시간 가변 볼륨 적용.
         */
        bgmDuckingTrack: com.mingeek.studiopop.data.keyframe.KeyframeTrack<Float>? = null,
        /**
         * R6: 적용할 컬러 LUT id (예: BuiltinLuts.CINEMATIC.id). null/미등록 이면 LUT 미적용.
         * [SyntheticCubeLuts] 가 코드로 5종 큐브를 만들어 [LutColorEffect] 가 Media3
         * SingleColorLut 으로 변환 — 영상의 색감이 자막/오버레이 합성 전 원본 픽셀에 적용됨.
         */
        lutId: String? = null,
        /** 0..1. LUT 강도 — 1=완전 적용, 0=원본. null 이면 1f. */
        lutIntensity: Float = 1f,
        /**
         * R6: 호출 측이 [com.mingeek.studiopop.data.effects.EffectStack] 을 미리
         * Media3 [Effect] 로 변환해 넘긴 결과 — Ken Burns / Zoom Punch 같은 시간 가변 카메라 무브.
         * LUT 다음, 종횡비/오버레이 이전에 체인에 합류 (raw 픽셀에 카메라 무브 적용 → 그 위에
         * 자막/오버레이).
         */
        videoFxEffects: List<Effect> = emptyList(),
    ): Result<File> = withContext(Dispatchers.Main) {
        runCatching {
            val effective = timeline.effectiveSegments()
            require(effective.isNotEmpty()) { "빈 타임라인은 export 불가 (cut 으로 전부 제거됐거나 세그먼트 없음)" }
            val outFile = File(outputDir, "edit_${System.currentTimeMillis()}.mp4")

            val removeOriginalAudio = timeline.audioTrack?.replaceOriginal == true
            // 원본 오디오 AudioProcessor 체인: (center 추출) → (볼륨 조절). 모두 조건부.
            val originalAudioProcessors = buildList<androidx.media3.common.audio.AudioProcessor> {
                if (timeline.extractCenterChannel) add(CenterChannelAudioProcessor())
                if (kotlin.math.abs(timeline.originalVolume - 1f) > 0.001f) {
                    add(VolumeAudioProcessor(timeline.originalVolume))
                }
            }
            val videoItems = effective.map { seg ->
                val start = seg.sourceStartMs.coerceAtLeast(0L)
                val end = seg.sourceEndMs.coerceAtLeast(start)
                val mediaItem = MediaItem.Builder()
                    .setUri(seg.sourceUri)
                    .setClippingConfiguration(
                        MediaItem.ClippingConfiguration.Builder()
                            .setStartPositionMs(start)
                            .setEndPositionMs(end)
                            .build()
                    )
                    .build()
                EditedMediaItem.Builder(mediaItem)
                    .setRemoveAudio(removeOriginalAudio)
                    .apply {
                        if (!removeOriginalAudio && originalAudioProcessors.isNotEmpty()) {
                            setEffects(
                                Effects(originalAudioProcessors, ImmutableList.of())
                            )
                        }
                    }
                    .build()
            }
            val videoSequence = EditedMediaItemSequence(videoItems)

            val sequences = mutableListOf(videoSequence)

            // BGM: 영상 전체 길이에 맞춰 한 번만 재생 (필요 시 loop). 볼륨 배율 적용 가능.
            timeline.audioTrack?.let { track ->
                val bgmBuilder = EditedMediaItem.Builder(
                    MediaItem.fromUri(track.uri)
                ).setRemoveVideo(true)
                // BGM 오디오 프로세서 체인: (자동 더킹) → (단일 볼륨 배율).
                // 두 가지 모두 있을 수 있음 — 더킹은 음성 위에서 -6dB, 단일 볼륨은 사용자 슬라이더.
                val bgmProcessors = buildList<androidx.media3.common.audio.AudioProcessor> {
                    val duck = bgmDuckingTrack
                    if (duck != null && !duck.isEmpty) {
                        add(DuckingAudioProcessor(duck))
                    }
                    if (kotlin.math.abs(track.volume - 1f) > 0.001f) {
                        add(VolumeAudioProcessor(track.volume))
                    }
                }
                if (bgmProcessors.isNotEmpty()) {
                    bgmBuilder.setEffects(Effects(bgmProcessors, ImmutableList.of()))
                }
                sequences += EditedMediaItemSequence(
                    listOf(bgmBuilder.build()),
                    /* isLooping = */ true,
                )
            }

            // 효과음(SFX): 각 클립마다 [무음 패딩 + SFX 클립] 으로 된 별도 sequence 추가.
            // Media3 는 여러 audio sequence 를 자동 믹싱하므로 원본 오디오·BGM 과 겹쳐 재생됨.
            // 무음 패딩으로 "출력 시각 T 에 정확히 재생" 을 구현.
            val totalOutputMs = effective.sumOf { it.durationMs }
            for (sfx in timeline.sfxClips) {
                val windows = timeline.rangeToOutputWindows(sfx.sourceStartMs, sfx.sourceEndMs)
                val trigger = windows.firstOrNull()?.first ?: continue
                val silencePrefixMs = trigger.coerceIn(0L, totalOutputMs)
                val sfxItems = buildList {
                    if (silencePrefixMs > 0) add(buildSilenceAudioItem(silencePrefixMs))
                    val sfxBuilder = EditedMediaItem.Builder(MediaItem.fromUri(sfx.audioUri))
                        .setRemoveVideo(true)
                    if (kotlin.math.abs(sfx.volume - 1f) > 0.001f) {
                        sfxBuilder.setEffects(
                            Effects(listOf(VolumeAudioProcessor(sfx.volume)), ImmutableList.of())
                        )
                    }
                    add(sfxBuilder.build())
                }
                sequences += EditedMediaItemSequence(sfxItems)
            }

            // 출력 프레임 해상도 — 첫 effective 세그먼트의 비디오 해상도 기준.
            // 모자이크 오버레이가 setScale 을 정확히 계산하려면 필요.
            val (frameW, frameH) = withContext(Dispatchers.IO) {
                readFrameSize(effective.first().sourceUri)
            } ?: DEFAULT_FRAME_SIZE
            val overlays = buildOverlayList(timeline, frameW, frameH)
            // 효과 체인 순서:
            //  1. LUT — raw 영상 픽셀의 색감 변환. 오버레이 가독성 보호 위해 가장 앞.
            //  2. videoFxEffects (Ken Burns / Zoom Punch) — 카메라 무브, 색감과 무관해 LUT 뒤.
            //  3. Presentation (종횡비) — 출력 프레임 크기 결정.
            //  4. OverlayEffect (자막/짤/모자이크) — 시청자에게 보이는 마지막 합성.
            val lutEffect = LutColorEffect.forLutId(lutId, lutIntensity)
            val videoEffects = ImmutableList.Builder<Effect>().apply {
                lutEffect?.let { add(it) }
                videoFxEffects.forEach { add(it) }
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
            publishToGalleryQuietly(outFile, "StudioPop_edit_${System.currentTimeMillis()}")
            outFile
        }
    }

    /**
     * export 결과를 갤러리에 등록. MediaStore publisher 가 없거나 pre-Q 면 no-op.
     * 등록 실패는 export 자체를 깨뜨리지 않도록 swallow.
     */
    private suspend fun publishToGalleryQuietly(file: File, displayName: String) {
        val publisher = mediaStorePublisher ?: return
        runCatching { publisher.publish(file, displayName) }
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
     * - 짤(ImageLayer) 은 레이어별 ImageStickerOverlay
     * - 모자이크(MosaicRegion) 은 MosaicBlockOverlay
     * - 고정 텍스트 템플릿은 세그먼트별 cue 로 펼쳐 CaptionOverlay
     * - 전환이 켜져 있으면 FadeAtBoundariesOverlay 추가
     */
    private fun buildOverlayList(
        timeline: Timeline,
        frameWidthPx: Int,
        frameHeightPx: Int,
    ): List<TextureOverlay> {
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

        // 고정 텍스트 템플릿: 각 세그먼트 시간대마다 텍스트(기본 또는 override) 로 펼침
        val fixedTemplateCues = timeline.fixedTemplatesToOutputCuesByStyle()
        for ((style, cues) in fixedTemplateCues) {
            if (cues.isNotEmpty()) {
                overlays += CaptionOverlay(cues, style)
            }
        }

        // 짤(이미지 스티커): 각 레이어별 ImageStickerOverlay
        for (layer in timeline.imageLayers) {
            val windows = timeline.rangeToOutputWindows(layer.sourceStartMs, layer.sourceEndMs)
            if (windows.isNotEmpty()) {
                overlays += ImageStickerOverlay(layer, windows)
            }
        }

        // 모자이크: 각 영역별 MosaicBlockOverlay. 프레임 해상도로 정확한 scale 계산.
        for (region in timeline.mosaicRegions) {
            val windows = timeline.rangeToOutputWindows(region.sourceStartMs, region.sourceEndMs)
            if (windows.isNotEmpty() && region.keyframes.isNotEmpty()) {
                overlays += MosaicBlockOverlay(
                    region = region,
                    activeWindowsMs = windows,
                    sourceStartMs = region.sourceStartMs,
                    frameWidthPx = frameWidthPx,
                    frameHeightPx = frameHeightPx,
                )
            }
        }

        // 전환: 실제로 concat 되는 effective 세그먼트 경계에 페이드 적용
        val effectiveSegs = timeline.effectiveSegments()
        if (timeline.transitions.enabled && effectiveSegs.size > 1) {
            val boundaries = mutableListOf<Long>()
            var acc = 0L
            for (i in 0 until effectiveSegs.size - 1) {
                acc += effectiveSegs[i].durationMs
                boundaries += acc
            }
            overlays += FadeAtBoundariesOverlay(
                boundariesMs = boundaries,
                halfDurationMs = timeline.transitions.halfDurationMs.coerceAtLeast(50L),
                peakAlpha = timeline.transitions.peakAlpha,
            )
        }

        return overlays
    }


    /**
     * 주어진 effective 세그먼트를 감싸는 raw 세그먼트 찾기.
     * CutRange 로 쪼개진 조각이어도 원본 raw 세그먼트의 id 로 override 를 조회할 수 있게 하려는 용도.
     */
    private fun Timeline.rawSegmentContaining(effective: TimelineSegment): TimelineSegment? =
        segments.firstOrNull { raw ->
            raw.sourceUri == effective.sourceUri &&
                effective.sourceStartMs >= raw.sourceStartMs &&
                effective.sourceEndMs <= raw.sourceEndMs
        }

    /**
     * 고정 텍스트 템플릿을 effective 세그먼트별로 펼쳐 (style, cues) 맵 생성.
     * 각 effective 세그먼트 duration 만큼의 cue 한 개씩. override 는 해당 effective 를 감싸는
     * raw 세그먼트 id 기준으로 조회 — cut 때문에 쪼개진 sub-segment 도 부모의 override 를 공유.
     * 빈 문자열 cue 는 제외(해당 구간엔 아무것도 안 보임).
     */
    private fun Timeline.fixedTemplatesToOutputCuesByStyle(): Map<CaptionStyle, List<Cue>> {
        if (fixedTemplates.isEmpty()) return emptyMap()
        val cuesByStyle = mutableMapOf<CaptionStyle, MutableList<Cue>>()
        for (template in fixedTemplates) {
            if (!template.enabled) continue
            val style = template.resolvedStyle()
            val bucket = cuesByStyle.getOrPut(style) { mutableListOf() }
            var accumulated = 0L
            for (seg in effectiveSegments()) {
                val parent = rawSegmentContaining(seg)
                val text = parent?.id?.let { template.perSegmentText[it] } ?: template.defaultText
                if (text.isNotBlank()) {
                    bucket += Cue(
                        index = bucket.size + 1,
                        startMs = accumulated,
                        endMs = accumulated + seg.durationMs,
                        text = text,
                    )
                }
                accumulated += seg.durationMs
            }
        }
        return cuesByStyle
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
     * (sourceStart, sourceEnd, text) 리스트를 **effective** 세그먼트 구성에 맞춰 출력 Cue 로 변환.
     * effective 기준이라 CutRange 로 제거된 구간의 캡션은 자동으로 빠지고, 나머지는 올바른 출력 시각으로 매핑됨.
     * 한 입력이 여러 effective 세그먼트에 걸치면 조각으로 분리됨.
     */
    private fun Timeline.captionsToOutputCues(
        inputs: List<Pair<Pair<Long, Long>, String>>,
    ): List<Cue> {
        val result = mutableListOf<Cue>()
        var accumulated = 0L
        for (seg in effectiveSegments()) {
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

    /**
     * 주어진 영상 URI 의 해상도(회전 반영) 를 반환. 실패 시 null.
     */
    private fun readFrameSize(uri: Uri): Pair<Int, Int>? {
        val retriever = MediaMetadataRetriever()
        return try {
            retriever.setDataSource(context, uri)
            val w = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_WIDTH)
                ?.toIntOrNull()
            val h = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_HEIGHT)
                ?.toIntOrNull()
            val rotation = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_ROTATION)
                ?.toIntOrNull() ?: 0
            if (w == null || h == null || w <= 0 || h <= 0) null
            else if (rotation == 90 || rotation == 270) h to w
            else w to h
        } catch (_: Exception) {
            null
        } finally {
            runCatching { retriever.release() }
        }
    }

    companion object {
        private const val PROGRESS_POLL_MS = 200L
        /** 프레임 해상도를 읽지 못할 때의 fallback (1080p landscape). */
        private val DEFAULT_FRAME_SIZE = 1920 to 1080
    }
}
