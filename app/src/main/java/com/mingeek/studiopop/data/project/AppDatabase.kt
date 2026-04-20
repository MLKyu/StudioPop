package com.mingeek.studiopop.data.project

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.TypeConverter
import androidx.room.TypeConverters

class AssetTypeConverters {
    @TypeConverter fun fromType(t: AssetType): String = t.name
    @TypeConverter fun toType(v: String): AssetType = AssetType.valueOf(v)
}

@Database(
    entities = [ProjectEntity::class, AssetEntity::class],
    version = 1,
    exportSchema = false,
)
@TypeConverters(AssetTypeConverters::class)
abstract class AppDatabase : RoomDatabase() {
    abstract fun projectDao(): ProjectDao
    abstract fun assetDao(): AssetDao

    companion object {
        fun build(context: Context): AppDatabase =
            Room.databaseBuilder(
                context.applicationContext,
                AppDatabase::class.java,
                "superapp.db",
            ).build()
    }
}
