package com.mingeek.studiopop.data.library

import androidx.room.Dao
import androidx.room.Delete
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import kotlinx.coroutines.flow.Flow

@Dao
interface LibraryAssetDao {

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(asset: LibraryAssetEntity): Long

    @Query("SELECT * FROM library_assets WHERE kind = :kind ORDER BY createdAt DESC")
    fun observeByKind(kind: LibraryAssetKind): Flow<List<LibraryAssetEntity>>

    @Query("SELECT * FROM library_assets WHERE kind = :kind ORDER BY createdAt DESC")
    suspend fun listByKind(kind: LibraryAssetKind): List<LibraryAssetEntity>

    @Query("SELECT * FROM library_assets WHERE id = :id LIMIT 1")
    suspend fun getById(id: Long): LibraryAssetEntity?

    @Delete
    suspend fun delete(asset: LibraryAssetEntity)
}
