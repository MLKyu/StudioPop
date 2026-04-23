package com.mingeek.studiopop.data.editor

import android.net.Uri
import java.util.UUID

/**
 * 시간 범위를 갖는 타임라인 레이어의 공통 인터페이스.
 * 새 오버레이 타입(도형, 화살표, 필터 등)을 추가할 때 이걸 구현하면
 * 공통 유틸(captionsToOutputCues, 리사이즈, 평행이동 등)을 그대로 재사용 가능.
 *
 * 모든 시각은 **원본 영상(source) 기준** — export/preview 시 Timeline 구성에 맞춰
 * output 시각으로 매핑됨.
 */
interface TimedLayer {
    val id: String
    val sourceStartMs: Long
    val sourceEndMs: Long
}

/**
 * 타임라인의 한 조각. **특정 sourceUri** 영상의 [sourceStartMs, sourceEndMs] 구간.
 * 출력 영상은 [TimelineSegment] 들을 순서대로 이어붙인 결과.
 *
 * 세그먼트마다 sourceUri 가 다를 수 있어 여러 영상을 하나의 타임라인에 concat 가능.
 */
data class TimelineSegment(
    val id: String = UUID.randomUUID().toString(),
    val sourceUri: Uri,
    val sourceStartMs: Long,
    val sourceEndMs: Long,
) {
    val durationMs: Long get() = (sourceEndMs - sourceStartMs).coerceAtLeast(0L)
}

/**
 * 자막 큐. 시각은 **원본 영상(source) 기준** 으로 저장.
 * (익스포트·프리뷰 시 타임라인 구성에 따라 output 시각으로 매핑)
 */
data class TimelineCaption(
    override val id: String = UUID.randomUUID().toString(),
    override val sourceStartMs: Long,
    override val sourceEndMs: Long,
    val text: String,
    val style: CaptionStyle = CaptionStyle.DEFAULT,
) : TimedLayer

/**
 * 영상 위에 올라가는 독립 텍스트 레이어.
 * 자막(TimelineCaption) 과 달리 "타이틀/CTA/강조" 등 화면 구성용 오버레이.
 * 시각은 자막과 같은 방식(source-time) 으로 저장.
 */
data class TextLayer(
    override val id: String = UUID.randomUUID().toString(),
    override val sourceStartMs: Long,
    override val sourceEndMs: Long,
    val text: String,
    val style: CaptionStyle = CaptionStyle.DEFAULT.copy(anchorY = 0.8f), // 기본 상단
) : TimedLayer

/**
 * 짤(이미지) 오버레이. 정적 이미지(PNG/JPG/WebP) 를 시간 범위에 띄움.
 * - [imageUri]: 파일/컨텐츠 URI. 라이브러리 에셋이면 local file://, 1회용이면 content://.
 * - [centerX/Y]: 프레임 기준 NDC 중심 좌표 (-1..1, 0=정중앙)
 * - [scale]: 프레임 짧은 변 대비 이미지 크기 비율 (0.3 = 프레임의 30%)
 * - [rotationDeg]: 회전 각도
 * - [alpha]: 0..1 투명도
 */
data class ImageLayer(
    override val id: String = UUID.randomUUID().toString(),
    override val sourceStartMs: Long,
    override val sourceEndMs: Long,
    val imageUri: Uri,
    val centerX: Float = 0f,
    val centerY: Float = 0f,
    val scale: Float = 0.3f,
    val rotationDeg: Float = 0f,
    val alpha: Float = 1f,
) : TimedLayer

/**
 * 효과음(SFX) 클립. 영상 특정 지점에 짧은 오디오를 덧입힘(원본/BGM 과 믹싱).
 * - sourceStart 시점이 재생 트리거. durationMs 는 재생 시간(0 이면 파일 전체 길이).
 * - 캡션과 동일하게 source-time 앵커 — 세그먼트 cut/이동 따라감.
 */
data class SfxClip(
    override val id: String = UUID.randomUUID().toString(),
    override val sourceStartMs: Long,
    override val sourceEndMs: Long,
    val audioUri: Uri,
    val label: String = "",
    val volume: Float = 1.0f,
) : TimedLayer {
    val durationMs: Long get() = (sourceEndMs - sourceStartMs).coerceAtLeast(0L)
}

/** 모자이크 수동/자동 모드. */
enum class MosaicMode { MANUAL, AUTO_FACE }

/**
 * 프레임 기준 NDC rect 스냅샷 + 시각. 키프레임 사이는 선형 보간으로 위치·크기가 매끄럽게 이동.
 * rect 좌표는 프레임 중심 (0,0) 기준 NDC (-1..1). width/height 도 동일 스케일.
 */
data class MosaicKeyframe(
    val sourceTimeMs: Long,
    val cx: Float,
    val cy: Float,
    val w: Float,
    val h: Float,
)

/**
 * 모자이크 영역. 한 개 이상의 [MosaicKeyframe] 을 시간순으로 보간해서 rect 를 움직임.
 * - MANUAL: 사용자가 수동으로 드래그한 단일 rect (keyframe 1개, 고정)
 * - AUTO_FACE: [FaceDetector] 가 샘플링한 다수 키프레임
 */
data class MosaicRegion(
    override val id: String = UUID.randomUUID().toString(),
    override val sourceStartMs: Long,
    override val sourceEndMs: Long,
    val mode: MosaicMode,
    val keyframes: List<MosaicKeyframe>,
    val blockSizePx: Int = 24,
) : TimedLayer

/**
 * 고정 위치 앵커. 텍스트 템플릿(로고, 채널핸들, 워터마크 등) 위치 결정.
 */
enum class TemplateAnchor(val ndcX: Float, val ndcY: Float) {
    TOP_LEFT(-0.9f, 0.9f),
    TOP_CENTER(0f, 0.9f),
    TOP_RIGHT(0.9f, 0.9f),
    BOTTOM_LEFT(-0.9f, -0.9f),
    BOTTOM_CENTER(0f, -0.9f),
    BOTTOM_RIGHT(0.9f, -0.9f),
}

/**
 * 영상 전체에 지속 표시되는 고정 텍스트 템플릿.
 * - 위치·스타일은 고정 (로고/워터마크처럼)
 * - 기본 텍스트는 [defaultText]
 * - 세그먼트마다 다른 값이 필요하면 [perSegmentText] 에 segmentId → 텍스트 맵 저장
 *   (override 가 없는 세그먼트 구간에는 defaultText 가 쓰임, 빈 문자열이면 해당 구간 숨김)
 */
data class FixedTextTemplate(
    val id: String = UUID.randomUUID().toString(),
    val label: String,
    val anchor: TemplateAnchor,
    val defaultText: String,
    val style: CaptionStyle,
    val perSegmentText: Map<String, String> = emptyMap(),
    val enabled: Boolean = true,
) {
    /** 템플릿 위치·스타일을 기존 CaptionStyle anchorX/Y 에 반영한 사본. */
    fun resolvedStyle(): CaptionStyle =
        style.copy(anchorX = anchor.ndcX, anchorY = anchor.ndcY)
}

/**
 * 원본 영상에서 **잘라낼(삭제할)** 구간.
 * segments 에 저장된 원본 범위를 그대로 유지한 채 export/preview 시점에만 차감 적용.
 * 덕분에 사용자가 구간을 자유롭게 조정·제거 가능 (undo 대신 CutRange 자체를 지우면 복원).
 */
data class CutRange(
    val id: String = UUID.randomUUID().toString(),
    val sourceUri: android.net.Uri,
    val sourceStartMs: Long,
    val sourceEndMs: Long,
)

/**
 * 불변 타임라인. 모든 조작은 새 인스턴스를 반환.
 */
data class Timeline(
    val segments: List<TimelineSegment>,
    val captions: List<TimelineCaption> = emptyList(),
    val textLayers: List<TextLayer> = emptyList(),
    val imageLayers: List<ImageLayer> = emptyList(),
    val sfxClips: List<SfxClip> = emptyList(),
    val mosaicRegions: List<MosaicRegion> = emptyList(),
    val fixedTemplates: List<FixedTextTemplate> = emptyList(),
    val cutRanges: List<CutRange> = emptyList(),
    val transitions: TransitionSettings = TransitionSettings(),
    val audioTrack: AudioTrack? = null,
) {

    /**
     * cutRanges 를 segments 에 차감한 export/render 용 세그먼트. 시각적 타임라인은
     * raw 기준으로 그대로 그리고(= cut 막대를 보여주기 위해) export 만 이걸 사용.
     * 프리뷰 player 도 raw segments 재생 — cut 은 시각 힌트만 (export 전까지 제거 안 됨).
     */
    fun effectiveSegments(): List<TimelineSegment> {
        if (cutRanges.isEmpty()) return segments
        var result = segments
        for (cut in cutRanges) {
            result = result.flatMap { seg -> seg.minus(cut) }
        }
        return result
    }

    /** 타임라인 UI 너비·플레이헤드 좌표는 raw 기준. cut 은 visual overlay 로만 표현. */
    val outputDurationMs: Long get() = segments.sumOf { it.durationMs }

    /**
     * effectiveSegments() 와 **동일 순서·동일 길이** 로, 각 effective 세그먼트의 raw output
     * 시작 ms 를 반환. raw 세그먼트를 순서대로 돌며 관련 cut 만 적용해 조각을 만들므로,
     * 같은 sourceUri 를 가진 raw 세그먼트가 여러 개여도 **인덱스 기반** 으로 안전하게 매핑됨.
     *
     * ExoPlayer(effective 재생) 의 `currentMediaItemIndex + currentPosition` 을 raw output ms
     * 로 역매핑하는 데 사용.
     */
    fun effectiveRawOutputStarts(): List<Long> {
        val result = mutableListOf<Long>()
        var rawAcc = 0L
        for (raw in segments) {
            val applicable = cutRanges.filter { it.sourceUri == raw.sourceUri }
            var pieces = listOf(raw)
            for (cut in applicable) {
                pieces = pieces.flatMap { it.minus(cut) }
            }
            for (piece in pieces) {
                result += rawAcc + (piece.sourceStartMs - raw.sourceStartMs)
            }
            rawAcc += raw.durationMs
        }
        return result
    }

    /**
     * effectiveSegments 사이 경계들을 **raw output ms** 로 반환.
     * 프리뷰 전환 오버레이가 플레이헤드(raw 좌표) 기준으로 페이드 거리 계산에 사용.
     */
    fun transitionBoundariesRawOutputMs(): List<Long> {
        val effective = effectiveSegments()
        if (effective.size <= 1) return emptyList()
        val starts = effectiveRawOutputStarts()
        return (0 until effective.size - 1).map { i -> starts[i] + effective[i].durationMs }
    }

    /** 출력 시각 → (세그먼트, 해당 세그먼트 내부 source 시각) 매핑 — raw 기준. */
    fun mapOutputToSource(outputMs: Long): Pair<TimelineSegment, Long>? {
        var accumulated = 0L
        for (seg in segments) {
            val d = seg.durationMs
            if (outputMs in accumulated..(accumulated + d)) {
                val within = outputMs - accumulated
                return seg to (seg.sourceStartMs + within)
            }
            accumulated += d
        }
        return null
    }

    /** source 시각 → raw 출력 시각. */
    fun mapSourceToOutput(sourceMs: Long): Long? {
        var accumulated = 0L
        for (seg in segments) {
            if (sourceMs in seg.sourceStartMs..seg.sourceEndMs) {
                return accumulated + (sourceMs - seg.sourceStartMs)
            }
            accumulated += seg.durationMs
        }
        return null
    }

    /**
     * 주어진 출력 시각의 세그먼트를 두 조각으로 자름. 경계(처음/끝) 이면 no-op.
     */
    fun splitAtOutputMs(outputMs: Long): Timeline {
        val (seg, sourceT) = mapOutputToSource(outputMs) ?: return this
        if (sourceT <= seg.sourceStartMs || sourceT >= seg.sourceEndMs) return this

        val newSegs = segments.flatMap { s ->
            if (s.id == seg.id) listOf(
                s.copy(sourceEndMs = sourceT),
                TimelineSegment(
                    sourceUri = s.sourceUri,
                    sourceStartMs = sourceT,
                    sourceEndMs = s.sourceEndMs,
                ),
            ) else listOf(s)
        }
        return copy(segments = newSegs)
    }

    /**
     * 타임라인 끝에 새 영상을 통째로 추가. 서로 다른 sourceUri 여도 OK.
     */
    fun appendVideo(uri: Uri, durationMs: Long): Timeline =
        copy(
            segments = segments + TimelineSegment(
                sourceUri = uri,
                sourceStartMs = 0L,
                sourceEndMs = durationMs,
            )
        )

    fun deleteSegment(id: String): Timeline {
        if (segments.size <= 1) return this // 마지막 1개는 남김
        return copy(segments = segments.filter { it.id != id })
    }

    /**
     * 두 인접 세그먼트 사이의 경계를 [sourceDeltaMs] 만큼 이동.
     * - source-adjacent (prev.end == next.start) 이면 둘을 같이 이동해 분할점을 옮기는 느낌
     * - gap 이 있으면(사이에 삭제된 영역) gap 폭을 유지한 채 둘 다 평행 이동
     * - 각 세그먼트 최소 길이 [MIN_DURATION_MS] 보장
     */
    fun moveBoundary(prevSegId: String, nextSegId: String, sourceDeltaMs: Long): Timeline {
        if (sourceDeltaMs == 0L) return this
        val prev = segments.firstOrNull { it.id == prevSegId } ?: return this
        val next = segments.firstOrNull { it.id == nextSegId } ?: return this
        // 서로 다른 영상 사이의 경계는 공유 source 시간축이 없어 의미가 없음 (no-op).
        // 과거 이 경로로 newNextStart 가 음수가 되어 MediaItem.ClippingConfiguration 이 crash.
        if (prev.sourceUri != next.sourceUri) return this

        val gap = next.sourceStartMs - prev.sourceEndMs // 0 if source-adjacent

        // 경계 이동 범위: prev 를 최소 MIN 만큼 남기고, next 도 최소 MIN 남기게
        val minNewPrevEnd = prev.sourceStartMs + MIN_DURATION_MS
        val maxNewPrevEnd = next.sourceEndMs - gap - MIN_DURATION_MS
        val newPrevEnd = (prev.sourceEndMs + sourceDeltaMs)
            .coerceIn(minNewPrevEnd, maxNewPrevEnd)
        val actualDelta = newPrevEnd - prev.sourceEndMs
        if (actualDelta == 0L) return this

        val newNextStart = next.sourceStartMs + actualDelta

        val updated = segments.map { s ->
            when (s.id) {
                prevSegId -> s.copy(sourceEndMs = newPrevEnd)
                nextSegId -> s.copy(sourceStartMs = newNextStart)
                else -> s
            }
        }
        return copy(segments = updated)
    }

    fun addCaption(caption: TimelineCaption): Timeline =
        copy(captions = captions + caption)

    fun updateCaption(caption: TimelineCaption): Timeline =
        copy(captions = captions.map { if (it.id == caption.id) caption else it })

    fun deleteCaption(id: String): Timeline =
        copy(captions = captions.filter { it.id != id })

    fun addTextLayer(layer: TextLayer): Timeline =
        copy(textLayers = textLayers + layer)

    fun updateTextLayer(layer: TextLayer): Timeline =
        copy(textLayers = textLayers.map { if (it.id == layer.id) layer else it })

    fun deleteTextLayer(id: String): Timeline =
        copy(textLayers = textLayers.filter { it.id != id })

    fun addCutRange(range: CutRange): Timeline = copy(cutRanges = cutRanges + range)
    fun updateCutRange(range: CutRange): Timeline =
        copy(cutRanges = cutRanges.map { if (it.id == range.id) range else it })
    fun deleteCutRange(id: String): Timeline =
        copy(cutRanges = cutRanges.filter { it.id != id })

    fun addImageLayer(layer: ImageLayer): Timeline =
        copy(imageLayers = imageLayers + layer)
    fun updateImageLayer(layer: ImageLayer): Timeline =
        copy(imageLayers = imageLayers.map { if (it.id == layer.id) layer else it })
    fun deleteImageLayer(id: String): Timeline =
        copy(imageLayers = imageLayers.filter { it.id != id })

    fun addSfxClip(clip: SfxClip): Timeline = copy(sfxClips = sfxClips + clip)
    fun updateSfxClip(clip: SfxClip): Timeline =
        copy(sfxClips = sfxClips.map { if (it.id == clip.id) clip else it })
    fun deleteSfxClip(id: String): Timeline =
        copy(sfxClips = sfxClips.filter { it.id != id })

    fun addMosaicRegion(region: MosaicRegion): Timeline =
        copy(mosaicRegions = mosaicRegions + region)
    fun updateMosaicRegion(region: MosaicRegion): Timeline =
        copy(mosaicRegions = mosaicRegions.map { if (it.id == region.id) region else it })
    fun deleteMosaicRegion(id: String): Timeline =
        copy(mosaicRegions = mosaicRegions.filter { it.id != id })

    fun addFixedTemplate(template: FixedTextTemplate): Timeline =
        copy(fixedTemplates = fixedTemplates + template)
    fun updateFixedTemplate(template: FixedTextTemplate): Timeline =
        copy(fixedTemplates = fixedTemplates.map { if (it.id == template.id) template else it })
    fun deleteFixedTemplate(id: String): Timeline =
        copy(fixedTemplates = fixedTemplates.filter { it.id != id })

    fun withTransitions(t: TransitionSettings): Timeline = copy(transitions = t)
    fun withAudioTrack(a: AudioTrack?): Timeline = copy(audioTrack = a)

    /**
     * 원본 영상 전체를 한 세그먼트로 담은 초기 타임라인.
     */
    companion object {
        /** moveBoundary 시 각 세그먼트 보장할 최소 길이 */
        const val MIN_DURATION_MS = 100L

        fun single(
            sourceUri: Uri,
            sourceDurationMs: Long,
            captions: List<TimelineCaption> = emptyList(),
        ): Timeline = Timeline(
            segments = listOf(
                TimelineSegment(
                    sourceUri = sourceUri,
                    sourceStartMs = 0L,
                    sourceEndMs = sourceDurationMs,
                )
            ),
            captions = captions,
        )
    }
}

/**
 * 전환 효과 종류.
 * - [FADE_TO_BLACK]: 경계에서 완전히 검정으로 빠졌다 돌아옴 — 명시적 "전환" 느낌.
 * - [DISSOLVE]: 짧고 얕게 어두워지는 정도 — 이어지는 한 영상처럼 자연스럽게.
 */
enum class TransitionKind(
    val halfDurationMs: Long,
    val peakAlpha: Float,
) {
    FADE_TO_BLACK(halfDurationMs = 400L, peakAlpha = 1.0f),
    DISSOLVE(halfDurationMs = 180L, peakAlpha = 0.4f),
}

/**
 * 세그먼트 사이 전환 설정. kind 별로 duration/alpha 가 결정됨.
 */
data class TransitionSettings(
    val enabled: Boolean = false,
    val kind: TransitionKind = TransitionKind.FADE_TO_BLACK,
) {
    val halfDurationMs: Long get() = kind.halfDurationMs
    val peakAlpha: Float get() = kind.peakAlpha
}

/**
 * BGM(또는 다른 오디오) 트랙.
 * replaceOriginal = true 이면 영상의 원본 오디오는 제거하고 BGM 만 사용 (Phase 7 MVP).
 * 볼륨 믹싱은 Phase 8 에서.
 */
data class AudioTrack(
    val uri: android.net.Uri,
    val replaceOriginal: Boolean = true,
)

/**
 * 이 세그먼트에서 [cut] 구간을 차감한 결과.
 * - sourceUri 다르면 영향 없음 (원래 그대로 반환)
 * - cut 이 segment 를 감싸면 빈 리스트
 * - 중간을 관통하면 앞/뒤 두 개로 분리
 * - 한쪽 경계만 겹치면 해당 부분만 잘림
 */
internal fun TimelineSegment.minus(cut: CutRange): List<TimelineSegment> {
    if (sourceUri != cut.sourceUri) return listOf(this)
    val cutStart = cut.sourceStartMs
    val cutEnd = cut.sourceEndMs
    if (cutEnd <= sourceStartMs || cutStart >= sourceEndMs) return listOf(this)
    if (cutStart <= sourceStartMs && cutEnd >= sourceEndMs) return emptyList()
    if (cutStart > sourceStartMs && cutEnd < sourceEndMs) return listOf(
        copy(sourceEndMs = cutStart),
        copy(id = java.util.UUID.randomUUID().toString(), sourceStartMs = cutEnd),
    )
    if (cutStart <= sourceStartMs) return listOf(copy(sourceStartMs = cutEnd))
    return listOf(copy(sourceEndMs = cutStart))
}
