package com.mingeek.studiopop.data.library

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query

@Dao
interface TimelineSnapshotDao {

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(snapshot: TimelineSnapshotEntity)

    @Query("SELECT * FROM timeline_snapshots WHERE projectId = :projectId LIMIT 1")
    suspend fun getByProject(projectId: Long): TimelineSnapshotEntity?

    @Query("DELETE FROM timeline_snapshots WHERE projectId = :projectId")
    suspend fun deleteByProject(projectId: Long)
}
