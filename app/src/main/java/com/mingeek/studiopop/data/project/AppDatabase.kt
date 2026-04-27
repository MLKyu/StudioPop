package com.mingeek.studiopop.data.project

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.TypeConverter
import androidx.room.TypeConverters
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase
import com.mingeek.studiopop.data.library.FixedTemplatePresetDao
import com.mingeek.studiopop.data.library.FixedTemplatePresetEntity
import com.mingeek.studiopop.data.library.LibraryAssetDao
import com.mingeek.studiopop.data.library.LibraryAssetEntity
import com.mingeek.studiopop.data.library.LibraryAssetKind
import com.mingeek.studiopop.data.library.TimelineSnapshotDao
import com.mingeek.studiopop.data.library.TimelineSnapshotEntity

class AssetTypeConverters {
    @TypeConverter fun fromType(t: AssetType): String = t.name
    @TypeConverter fun toType(v: String): AssetType = AssetType.valueOf(v)

    @TypeConverter fun fromLibraryKind(k: LibraryAssetKind): String = k.name
    @TypeConverter fun toLibraryKind(v: String): LibraryAssetKind = LibraryAssetKind.valueOf(v)
}

@Database(
    entities = [
        ProjectEntity::class,
        AssetEntity::class,
        LibraryAssetEntity::class,
        TimelineSnapshotEntity::class,
        FixedTemplatePresetEntity::class,
    ],
    version = 3,
    exportSchema = false,
)
@TypeConverters(AssetTypeConverters::class)
abstract class AppDatabase : RoomDatabase() {
    abstract fun projectDao(): ProjectDao
    abstract fun assetDao(): AssetDao
    abstract fun libraryAssetDao(): LibraryAssetDao
    abstract fun timelineSnapshotDao(): TimelineSnapshotDao
    abstract fun fixedTemplatePresetDao(): FixedTemplatePresetDao

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

        /**
         * v2 → v3:
         *  - timeline_snapshots: 프로젝트별 편집 중 타임라인 JSON 스냅샷 (앱 재시작 복구용)
         *  - fixed_template_presets: 고정 텍스트 템플릿 프리셋(라이브러리) — 영상 간 재사용
         */
        private val MIGRATION_2_3 = object : Migration(2, 3) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL(
                    """
                    CREATE TABLE IF NOT EXISTS timeline_snapshots (
                        projectId INTEGER PRIMARY KEY NOT NULL,
                        timelineJson TEXT NOT NULL,
                        updatedAt INTEGER NOT NULL
                    )
                    """.trimIndent()
                )
                db.execSQL(
                    """
                    CREATE TABLE IF NOT EXISTS fixed_template_presets (
                        id INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL,
                        label TEXT NOT NULL,
                        anchorName TEXT NOT NULL,
                        defaultText TEXT NOT NULL,
                        styleJson TEXT NOT NULL,
                        createdAt INTEGER NOT NULL
                    )
                    """.trimIndent()
                )
                db.execSQL(
                    "CREATE INDEX IF NOT EXISTS index_fixed_template_presets_createdAt " +
                        "ON fixed_template_presets(createdAt)"
                )
            }
        }

        fun build(context: Context): AppDatabase =
            Room.databaseBuilder(
                context.applicationContext,
                AppDatabase::class.java,
                "superapp.db",
            )
                .addMigrations(MIGRATION_1_2, MIGRATION_2_3)
                .build()
    }
}
