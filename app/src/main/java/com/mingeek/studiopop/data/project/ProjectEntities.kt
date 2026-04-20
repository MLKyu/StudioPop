package com.mingeek.studiopop.data.project

import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey

enum class AssetType {
    CAPTION_SRT,
    EXPORT_VIDEO,
    THUMBNAIL,
    SHORTS,
    UPLOADED_VIDEO_ID,
}

@Entity(tableName = "projects")
data class ProjectEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val title: String,
    val description: String = "",
    /** ContentResolver 로 읽을 수 있는 영상 URI (String). MediaStore/SAF 모두 지원. */
    val sourceVideoUri: String,
    val createdAt: Long = System.currentTimeMillis(),
)

@Entity(
    tableName = "assets",
    foreignKeys = [
        ForeignKey(
            entity = ProjectEntity::class,
            parentColumns = ["id"],
            childColumns = ["projectId"],
            onDelete = ForeignKey.CASCADE,
        )
    ],
    indices = [Index("projectId"), Index("type")],
)
data class AssetEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val projectId: Long,
    val type: AssetType,
    /**
     * 파일 에셋이면 절대 경로, 메타데이터성(업로드된 videoId 등) 이면 값 자체.
     */
    val value: String,
    val label: String = "",
    val createdAt: Long = System.currentTimeMillis(),
)
