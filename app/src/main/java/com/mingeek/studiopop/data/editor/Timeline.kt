package com.mingeek.studiopop.data.editor

import android.net.Uri
import java.util.UUID

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
    val id: String = UUID.randomUUID().toString(),
    val sourceStartMs: Long,
    val sourceEndMs: Long,
    val text: String,
    val style: CaptionStyle = CaptionStyle.DEFAULT,
)

/**
 * 영상 위에 올라가는 독립 텍스트 레이어.
 * 자막(TimelineCaption) 과 달리 "타이틀/CTA/강조" 등 화면 구성용 오버레이.
 * 시각은 자막과 같은 방식(source-time) 으로 저장.
 */
data class TextLayer(
    val id: String = UUID.randomUUID().toString(),
    val sourceStartMs: Long,
    val sourceEndMs: Long,
    val text: String,
    val style: CaptionStyle = CaptionStyle.DEFAULT.copy(anchorY = 0.8f), // 기본 상단
)

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
 * 세그먼트 사이 전환 설정. 현재는 페이드-투-블랙 하나만 지원.
 */
data class TransitionSettings(
    val enabled: Boolean = false,
    val durationMs: Long = 400L,
)

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
