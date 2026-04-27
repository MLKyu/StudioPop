package com.mingeek.studiopop.data.library

import androidx.room.Dao
import androidx.room.Delete
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import kotlinx.coroutines.flow.Flow

@Dao
interface FixedTemplatePresetDao {

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(preset: FixedTemplatePresetEntity): Long

    @Query("SELECT * FROM fixed_template_presets ORDER BY createdAt DESC")
    fun observeAll(): Flow<List<FixedTemplatePresetEntity>>

    @Query("SELECT * FROM fixed_template_presets ORDER BY createdAt DESC")
    suspend fun listAll(): List<FixedTemplatePresetEntity>

    @Delete
    suspend fun delete(preset: FixedTemplatePresetEntity)
}
