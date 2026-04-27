package com.mingeek.studiopop.data.editor

import androidx.core.net.toUri
import com.squareup.moshi.JsonClass
import com.squareup.moshi.Moshi

/**
 * Timeline ↔ JSON 문자열 변환.
 * Timeline 의 Uri 를 String 으로 바꾼 DTO 를 Moshi 로 직렬화해 Room 에 저장 가능한 텍스트로 만듬.
 *
 * 스키마가 바뀌면 [SCHEMA_VERSION] 을 올리고 fromJson 에서 마이그레이션 처리 가능.
 * MVP 는 version mismatch 시 null 반환(기존 편집 상태 포기, 새 타임라인으로 시작).
 */
object TimelineSerializer {

    private const val SCHEMA_VERSION = 1

    private val moshi: Moshi = Moshi.Builder().build()
    private val snapshotAdapter = moshi.adapter(TimelineSnapshotDto::class.java)
    private val styleAdapter = moshi.adapter(CaptionStyleDto::class.java)

    fun toJson(timeline: Timeline): String =
        snapshotAdapter.toJson(TimelineSnapshotDto.from(timeline))

    fun fromJson(json: String): Timeline? = try {
        val dto = snapshotAdapter.fromJson(json) ?: return null
        if (dto.schemaVersion != SCHEMA_VERSION) null
        else dto.toTimeline()
    } catch (_: Throwable) {
        null
    }

    /** CaptionStyle 단독 직렬화 — FixedTemplatePreset 에서 사용. */
    fun styleToJson(style: CaptionStyle): String =
        styleAdapter.toJson(CaptionStyleDto.from(style))

    fun styleFromJson(json: String): CaptionStyle? = try {
        styleAdapter.fromJson(json)?.toStyle()
    } catch (_: Throwable) {
        null
    }
}

/** 루트 스냅샷. schemaVersion 으로 앞으로 깨지는 변경 감지. */
@JsonClass(generateAdapter = true)
data class TimelineSnapshotDto(
    val schemaVersion: Int,
    val segments: List<SegmentDto>,
    val captions: List<CaptionDto>,
    val textLayers: List<TextLayerDto>,
    val imageLayers: List<ImageLayerDto>,
    val sfxClips: List<SfxClipDto>,
    val mosaicRegions: List<MosaicRegionDto>,
    val fixedTemplates: List<FixedTemplateDto>,
    val cutRanges: List<CutRangeDto>,
    val transitions: TransitionDto,
    val audioTrack: AudioTrackDto?,
    /** 기본값 포함 — 기존(v1) JSON 에 없으면 Moshi 가 default 로 채움. */
    val originalVolume: Float = 1f,
    val extractCenterChannel: Boolean = false,
) {
    fun toTimeline(): Timeline = Timeline(
        segments = segments.map { it.toModel() },
        captions = captions.map { it.toModel() },
        textLayers = textLayers.map { it.toModel() },
        imageLayers = imageLayers.map { it.toModel() },
        sfxClips = sfxClips.map { it.toModel() },
        mosaicRegions = mosaicRegions.map { it.toModel() },
        fixedTemplates = fixedTemplates.map { it.toModel() },
        cutRanges = cutRanges.map { it.toModel() },
        transitions = transitions.toModel(),
        audioTrack = audioTrack?.toModel(),
        originalVolume = originalVolume,
        extractCenterChannel = extractCenterChannel,
    )

    companion object {
        fun from(t: Timeline) = TimelineSnapshotDto(
            schemaVersion = 1,
            segments = t.segments.map { SegmentDto.from(it) },
            captions = t.captions.map { CaptionDto.from(it) },
            textLayers = t.textLayers.map { TextLayerDto.from(it) },
            imageLayers = t.imageLayers.map { ImageLayerDto.from(it) },
            sfxClips = t.sfxClips.map { SfxClipDto.from(it) },
            mosaicRegions = t.mosaicRegions.map { MosaicRegionDto.from(it) },
            fixedTemplates = t.fixedTemplates.map { FixedTemplateDto.from(it) },
            cutRanges = t.cutRanges.map { CutRangeDto.from(it) },
            transitions = TransitionDto.from(t.transitions),
            audioTrack = t.audioTrack?.let { AudioTrackDto.from(it) },
            originalVolume = t.originalVolume,
            extractCenterChannel = t.extractCenterChannel,
        )
    }
}

@JsonClass(generateAdapter = true)
data class CaptionStyleDto(
    val presetName: String,
    val anchorY: Float,
    val anchorX: Float,
    val sizeScale: Float,
) {
    fun toStyle(): CaptionStyle = CaptionStyle(
        preset = runCatching { CaptionPreset.valueOf(presetName) }.getOrDefault(CaptionPreset.CLEAN),
        anchorY = anchorY,
        anchorX = anchorX,
        sizeScale = sizeScale,
    )

    companion object {
        fun from(s: CaptionStyle) = CaptionStyleDto(
            presetName = s.preset.name,
            anchorY = s.anchorY,
            anchorX = s.anchorX,
            sizeScale = s.sizeScale,
        )
    }
}

@JsonClass(generateAdapter = true)
data class SegmentDto(
    val id: String,
    val sourceUri: String,
    val sourceStartMs: Long,
    val sourceEndMs: Long,
) {
    fun toModel() = TimelineSegment(id, sourceUri.toUri(), sourceStartMs, sourceEndMs)
    companion object {
        fun from(s: TimelineSegment) = SegmentDto(s.id, s.sourceUri.toString(), s.sourceStartMs, s.sourceEndMs)
    }
}

@JsonClass(generateAdapter = true)
data class CaptionDto(
    val id: String,
    val sourceStartMs: Long,
    val sourceEndMs: Long,
    val text: String,
    val style: CaptionStyleDto,
) {
    fun toModel() = TimelineCaption(id, sourceStartMs, sourceEndMs, text, style.toStyle())
    companion object {
        fun from(c: TimelineCaption) = CaptionDto(c.id, c.sourceStartMs, c.sourceEndMs, c.text, CaptionStyleDto.from(c.style))
    }
}

@JsonClass(generateAdapter = true)
data class TextLayerDto(
    val id: String,
    val sourceStartMs: Long,
    val sourceEndMs: Long,
    val text: String,
    val style: CaptionStyleDto,
) {
    fun toModel() = TextLayer(id, sourceStartMs, sourceEndMs, text, style.toStyle())
    companion object {
        fun from(l: TextLayer) = TextLayerDto(l.id, l.sourceStartMs, l.sourceEndMs, l.text, CaptionStyleDto.from(l.style))
    }
}

@JsonClass(generateAdapter = true)
data class ImageLayerDto(
    val id: String,
    val sourceStartMs: Long,
    val sourceEndMs: Long,
    val imageUri: String,
    val centerX: Float,
    val centerY: Float,
    val scale: Float,
    val rotationDeg: Float,
    val alpha: Float,
) {
    fun toModel() = ImageLayer(
        id = id,
        sourceStartMs = sourceStartMs,
        sourceEndMs = sourceEndMs,
        imageUri = imageUri.toUri(),
        centerX = centerX,
        centerY = centerY,
        scale = scale,
        rotationDeg = rotationDeg,
        alpha = alpha,
    )
    companion object {
        fun from(l: ImageLayer) = ImageLayerDto(
            id = l.id,
            sourceStartMs = l.sourceStartMs,
            sourceEndMs = l.sourceEndMs,
            imageUri = l.imageUri.toString(),
            centerX = l.centerX,
            centerY = l.centerY,
            scale = l.scale,
            rotationDeg = l.rotationDeg,
            alpha = l.alpha,
        )
    }
}

@JsonClass(generateAdapter = true)
data class SfxClipDto(
    val id: String,
    val sourceStartMs: Long,
    val sourceEndMs: Long,
    val audioUri: String,
    val label: String,
    val volume: Float,
) {
    fun toModel() = SfxClip(
        id = id,
        sourceStartMs = sourceStartMs,
        sourceEndMs = sourceEndMs,
        audioUri = audioUri.toUri(),
        label = label,
        volume = volume,
    )
    companion object {
        fun from(c: SfxClip) = SfxClipDto(c.id, c.sourceStartMs, c.sourceEndMs, c.audioUri.toString(), c.label, c.volume)
    }
}

@JsonClass(generateAdapter = true)
data class MosaicKeyframeDto(
    val sourceTimeMs: Long,
    val cx: Float,
    val cy: Float,
    val w: Float,
    val h: Float,
) {
    fun toModel() = MosaicKeyframe(sourceTimeMs, cx, cy, w, h)
    companion object {
        fun from(k: MosaicKeyframe) = MosaicKeyframeDto(k.sourceTimeMs, k.cx, k.cy, k.w, k.h)
    }
}

@JsonClass(generateAdapter = true)
data class MosaicRegionDto(
    val id: String,
    val sourceStartMs: Long,
    val sourceEndMs: Long,
    val modeName: String,
    val keyframes: List<MosaicKeyframeDto>,
    val blockSizePx: Int,
) {
    fun toModel() = MosaicRegion(
        id = id,
        sourceStartMs = sourceStartMs,
        sourceEndMs = sourceEndMs,
        mode = runCatching { MosaicMode.valueOf(modeName) }.getOrDefault(MosaicMode.MANUAL),
        keyframes = keyframes.map { it.toModel() },
        blockSizePx = blockSizePx,
    )
    companion object {
        fun from(r: MosaicRegion) = MosaicRegionDto(
            id = r.id,
            sourceStartMs = r.sourceStartMs,
            sourceEndMs = r.sourceEndMs,
            modeName = r.mode.name,
            keyframes = r.keyframes.map { MosaicKeyframeDto.from(it) },
            blockSizePx = r.blockSizePx,
        )
    }
}

@JsonClass(generateAdapter = true)
data class FixedTemplateDto(
    val id: String,
    val label: String,
    val anchorName: String,
    val defaultText: String,
    val style: CaptionStyleDto,
    val perSegmentText: Map<String, String>,
    val enabled: Boolean,
) {
    fun toModel() = FixedTextTemplate(
        id = id,
        label = label,
        anchor = runCatching { TemplateAnchor.valueOf(anchorName) }.getOrDefault(TemplateAnchor.TOP_LEFT),
        defaultText = defaultText,
        style = style.toStyle(),
        perSegmentText = perSegmentText,
        enabled = enabled,
    )
    companion object {
        fun from(t: FixedTextTemplate) = FixedTemplateDto(
            id = t.id,
            label = t.label,
            anchorName = t.anchor.name,
            defaultText = t.defaultText,
            style = CaptionStyleDto.from(t.style),
            perSegmentText = t.perSegmentText,
            enabled = t.enabled,
        )
    }
}

@JsonClass(generateAdapter = true)
data class CutRangeDto(
    val id: String,
    val sourceUri: String,
    val sourceStartMs: Long,
    val sourceEndMs: Long,
) {
    fun toModel() = CutRange(id, sourceUri.toUri(), sourceStartMs, sourceEndMs)
    companion object {
        fun from(c: CutRange) = CutRangeDto(c.id, c.sourceUri.toString(), c.sourceStartMs, c.sourceEndMs)
    }
}

@JsonClass(generateAdapter = true)
data class TransitionDto(
    val enabled: Boolean,
    val kindName: String,
) {
    fun toModel() = TransitionSettings(
        enabled = enabled,
        kind = runCatching { TransitionKind.valueOf(kindName) }.getOrDefault(TransitionKind.FADE_TO_BLACK),
    )
    companion object {
        fun from(t: TransitionSettings) = TransitionDto(t.enabled, t.kind.name)
    }
}

@JsonClass(generateAdapter = true)
data class AudioTrackDto(
    val uri: String,
    val replaceOriginal: Boolean,
    /** 기존 JSON 호환: 없으면 기본 1.0 (무조정). */
    val volume: Float = 1f,
    /** v2 이전 JSON 에 없음 — default false 로 호환. */
    val isVocalExtraction: Boolean = false,
) {
    fun toModel() = AudioTrack(
        uri = uri.toUri(),
        replaceOriginal = replaceOriginal,
        volume = volume,
        isVocalExtraction = isVocalExtraction,
    )
    companion object {
        fun from(a: AudioTrack) = AudioTrackDto(
            uri = a.uri.toString(),
            replaceOriginal = a.replaceOriginal,
            volume = a.volume,
            isVocalExtraction = a.isVocalExtraction,
        )
    }
}
