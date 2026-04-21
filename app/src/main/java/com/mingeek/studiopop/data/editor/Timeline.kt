package com.mingeek.studiopop.data.editor

import java.util.UUID

/**
 * 타임라인의 한 조각. 원본 영상의 [sourceStartMs, sourceEndMs] 구간.
 * 출력 영상은 [TimelineSegment] 들을 순서대로 이어붙인 결과.
 */
data class TimelineSegment(
    val id: String = UUID.randomUUID().toString(),
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
 * 불변 타임라인. 모든 조작은 새 인스턴스를 반환.
 */
data class Timeline(
    val segments: List<TimelineSegment>,
    val captions: List<TimelineCaption> = emptyList(),
    val textLayers: List<TextLayer> = emptyList(),
    val transitions: TransitionSettings = TransitionSettings(),
    val audioTrack: AudioTrack? = null,
) {

    val outputDurationMs: Long
        get() = segments.sumOf { it.durationMs }

    /**
     * 출력 시각 → (세그먼트, 해당 세그먼트 내부 source 시각) 매핑.
     */
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

    /**
     * source 시각이 현재 타임라인에서 보이는지, 보인다면 출력 시각은 몇인지.
     * 같은 source 시각이 여러 세그먼트에 있으면 첫 번째 매칭 반환.
     */
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
                TimelineSegment(sourceStartMs = sourceT, sourceEndMs = s.sourceEndMs),
            ) else listOf(s)
        }
        return copy(segments = newSegs)
    }

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

    fun withTransitions(t: TransitionSettings): Timeline = copy(transitions = t)
    fun withAudioTrack(a: AudioTrack?): Timeline = copy(audioTrack = a)

    /**
     * 원본 영상 전체를 한 세그먼트로 담은 초기 타임라인.
     */
    companion object {
        /** moveBoundary 시 각 세그먼트 보장할 최소 길이 */
        const val MIN_DURATION_MS = 100L

        fun single(sourceDurationMs: Long, captions: List<TimelineCaption> = emptyList()): Timeline =
            Timeline(
                segments = listOf(
                    TimelineSegment(sourceStartMs = 0L, sourceEndMs = sourceDurationMs)
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
