package com.mingeek.studiopop.data.library

import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

/**
 * 사용자가 설정 화면에서 등록해두는 재사용 가능한 에셋.
 * - [LibraryAssetKind.STICKER]: 편집기 "짤" 으로 사용할 이미지 (PNG/JPG/WebP)
 * - [LibraryAssetKind.SFX]: 편집기 "효과음" 으로 사용할 짧은 오디오 (mp3/wav/m4a 등)
 *
 * 추후 FONT, PRESET_CAPTION 등 새 타입을 [LibraryAssetKind] 에 추가하면 그대로 확장됨.
 */
enum class LibraryAssetKind { STICKER, SFX }

@Entity(
    tableName = "library_assets",
    indices = [Index("kind"), Index("createdAt")],
)
data class LibraryAssetEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val kind: LibraryAssetKind,
    /** 사용자 지정 이름 (예: "띠용", "두둥", "반응 놀람") */
    val label: String,
    /**
     * 앱 내부로 복사된 파일의 절대 경로. 원본 URI 권한이 시간에 따라 만료되더라도
     * 안정적으로 참조 가능하도록 등록 시점에 files/library/ 하위에 복사됨.
     */
    val filePath: String,
    /**
     * SFX 전용 메타 (ms). 편집기에서 기본 지속 시간을 계산할 때 사용. 0 이면 미상.
     */
    val durationMs: Long = 0L,
    val createdAt: Long = System.currentTimeMillis(),
)
