package com.mingeek.studiopop.data.project

import androidx.room.Dao
import androidx.room.Delete
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import kotlinx.coroutines.flow.Flow

@Dao
interface ProjectDao {

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertProject(project: ProjectEntity): Long

    @Query("SELECT * FROM projects ORDER BY createdAt DESC")
    fun observeAll(): Flow<List<ProjectEntity>>

    @Query("SELECT * FROM projects WHERE id = :id LIMIT 1")
    suspend fun getById(id: Long): ProjectEntity?

    @Delete
    suspend fun delete(project: ProjectEntity)
}

@Dao
interface AssetDao {

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertAsset(asset: AssetEntity): Long

    @Query("SELECT * FROM assets WHERE projectId = :projectId ORDER BY createdAt DESC")
    fun observeForProject(projectId: Long): Flow<List<AssetEntity>>

    @Query("SELECT * FROM assets WHERE projectId = :projectId ORDER BY createdAt DESC")
    suspend fun listForProject(projectId: Long): List<AssetEntity>

    @Query("""
        SELECT * FROM assets
        WHERE projectId = :projectId AND type = :type
        ORDER BY createdAt DESC LIMIT 1
    """)
    suspend fun latestOfType(projectId: Long, type: AssetType): AssetEntity?

    /**
     * 특정 타입의 에셋을 **모든 프로젝트에 걸쳐** 최신순으로 조회.
     * "내 편집본(EXPORT_VIDEO) 라이브러리" 같은 cross-project picker 에서 사용.
     */
    @Query("SELECT * FROM assets WHERE type = :type ORDER BY createdAt DESC")
    fun observeAllOfType(type: AssetType): kotlinx.coroutines.flow.Flow<List<AssetEntity>>

    @Query("SELECT * FROM assets WHERE type = :type ORDER BY createdAt DESC")
    suspend fun listAllOfType(type: AssetType): List<AssetEntity>

    @Delete
    suspend fun delete(asset: AssetEntity)
}
