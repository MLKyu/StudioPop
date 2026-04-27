package com.mingeek.studiopop.data.library

import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

/**
 * 고정 텍스트 템플릿 프리셋(라이브러리 재사용용).
 * 하나의 영상에 국한되지 않고 여러 영상에서 불러 쓸 수 있도록 앵커·스타일·기본 문구만 저장.
 *
 * 스타일은 CaptionStyle 을 JSON 으로 직렬화해 저장. 필드가 늘어나도 호환 유지.
 */
@Entity(
    tableName = "fixed_template_presets",
    indices = [Index("createdAt")],
)
data class FixedTemplatePresetEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    /** 사용자 표시명 (예: "상단 채널 핸들", "우하단 워터마크") */
    val label: String,
    /** [com.mingeek.studiopop.data.editor.TemplateAnchor] name. 예: "TOP_LEFT" */
    val anchorName: String,
    val defaultText: String,
    val styleJson: String,
    val createdAt: Long = System.currentTimeMillis(),
)
