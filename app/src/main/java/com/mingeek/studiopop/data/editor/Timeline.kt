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
)

/**
 * 불변 타임라인. 모든 조작은 새 인스턴스를 반환.
 */
data class Timeline(
    val segments: List<TimelineSegment>,
    val captions: List<TimelineCaption> = emptyList(),
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

    fun addCaption(caption: TimelineCaption): Timeline =
        copy(captions = captions + caption)

    fun updateCaption(caption: TimelineCaption): Timeline =
        copy(captions = captions.map { if (it.id == caption.id) caption else it })

    fun deleteCaption(id: String): Timeline =
        copy(captions = captions.filter { it.id != id })

    /**
     * 원본 영상 전체를 한 세그먼트로 담은 초기 타임라인.
     */
    companion object {
        fun single(sourceDurationMs: Long, captions: List<TimelineCaption> = emptyList()): Timeline =
            Timeline(
                segments = listOf(
                    TimelineSegment(sourceStartMs = 0L, sourceEndMs = sourceDurationMs)
                ),
                captions = captions,
            )
    }
}
