package com.mingeek.studiopop.data.library

import com.mingeek.studiopop.data.editor.Timeline
import com.mingeek.studiopop.data.editor.TimelineSerializer

/**
 * 프로젝트별 편집 중 타임라인 스냅샷을 DB 에 저장·복구.
 * 호출 순서와 무관하게 최신 timeline 만 덮어쓰는 upsert 모델이라 디바운스 저장에 적합.
 */
class TimelineSnapshotRepository(
    private val dao: TimelineSnapshotDao,
) {

    suspend fun save(projectId: Long, timeline: Timeline) {
        val json = TimelineSerializer.toJson(timeline)
        dao.upsert(TimelineSnapshotEntity(projectId = projectId, timelineJson = json))
    }

    suspend fun load(projectId: Long): Timeline? {
        val entity = dao.getByProject(projectId) ?: return null
        return TimelineSerializer.fromJson(entity.timelineJson)
    }

    suspend fun clear(projectId: Long) {
        dao.deleteByProject(projectId)
    }
}
