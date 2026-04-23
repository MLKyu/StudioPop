package com.mingeek.studiopop.data.project

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.TypeConverter
import androidx.room.TypeConverters
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase
import com.mingeek.studiopop.data.library.LibraryAssetDao
import com.mingeek.studiopop.data.library.LibraryAssetEntity
import com.mingeek.studiopop.data.library.LibraryAssetKind

class AssetTypeConverters {
    @TypeConverter fun fromType(t: AssetType): String = t.name
    @TypeConverter fun toType(v: String): AssetType = AssetType.valueOf(v)

    @TypeConverter fun fromLibraryKind(k: LibraryAssetKind): String = k.name
    @TypeConverter fun toLibraryKind(v: String): LibraryAssetKind = LibraryAssetKind.valueOf(v)
}

@Database(
    entities = [ProjectEntity::class, AssetEntity::class, LibraryAssetEntity::class],
    version = 2,
    exportSchema = false,
)
@TypeConverters(AssetTypeConverters::class)
abstract class AppDatabase : RoomDatabase() {
    abstract fun projectDao(): ProjectDao
    abstract fun assetDao(): AssetDao
    abstract fun libraryAssetDao(): LibraryAssetDao

    companion object {
        /** v1 → v2: library_assets 테이블 추가 (짤/효과음 사용자 라이브러리). */
        private val MIGRATION_1_2 = object : Migration(1, 2) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL(
                    """
                    CREATE TABLE IF NOT EXISTS library_assets (
                        id INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL,
                        kind TEXT NOT NULL,
                        label TEXT NOT NULL,
                        filePath TEXT NOT NULL,
                        durationMs INTEGER NOT NULL DEFAULT 0,
                        createdAt INTEGER NOT NULL
                    )
                    """.trimIndent()
                )
                db.execSQL("CREATE INDEX IF NOT EXISTS index_library_assets_kind ON library_assets(kind)")
                db.execSQL("CREATE INDEX IF NOT EXISTS index_library_assets_createdAt ON library_assets(createdAt)")
            }
        }

        fun build(context: Context): AppDatabase =
            Room.databaseBuilder(
                context.applicationContext,
                AppDatabase::class.java,
                "superapp.db",
            )
                .addMigrations(MIGRATION_1_2)
                .build()
    }
}
