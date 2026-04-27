package com.mingeek.studiopop.data.library

import androidx.room.Entity
import androidx.room.PrimaryKey

/**
 * 프로젝트 편집 상태(타임라인) 스냅샷.
 * 한 프로젝트당 최신 하나만 유지 — projectId 가 PK. 편집기에서 변경될 때마다 디바운스로 덮어씀.
 * 앱 재시작·프로세스 종료 후에도 편집 중이던 상태를 복구하기 위함.
 *
 * [timelineJson] 은 Timeline 전체를 직렬화한 JSON. Uri 는 String 으로 변환돼 저장됨.
 */
@Entity(tableName = "timeline_snapshots")
data class TimelineSnapshotEntity(
    @PrimaryKey val projectId: Long,
    val timelineJson: String,
    val updatedAt: Long = System.currentTimeMillis(),
)
