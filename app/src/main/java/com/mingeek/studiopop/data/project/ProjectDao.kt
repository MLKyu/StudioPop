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

    @Delete
    suspend fun delete(asset: AssetEntity)
}
