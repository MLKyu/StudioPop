package com.mingeek.studiopop.data.library

import com.mingeek.studiopop.data.editor.CaptionStyle
import com.mingeek.studiopop.data.editor.FixedTextTemplate
import com.mingeek.studiopop.data.editor.TemplateAnchor
import com.mingeek.studiopop.data.editor.TimelineSerializer
import kotlinx.coroutines.flow.Flow

/**
 * 고정 텍스트 템플릿 프리셋(라이브러리) Repository.
 * 한 영상에서 저장한 프리셋을 다른 영상에서 그대로 불러 쓸 수 있게 하기 위함.
 *
 * 저장 단위: 앵커 + 스타일 + 기본 문구 + 라벨. 세그먼트별 override(perSegmentText) 는 저장 안 함
 * (영상마다 세그먼트 구성이 달라 이식 불가).
 */
class FixedTemplatePresetRepository(
    private val dao: FixedTemplatePresetDao,
) {

    fun observe(): Flow<List<FixedTemplatePresetEntity>> = dao.observeAll()

    suspend fun list(): List<FixedTemplatePresetEntity> = dao.listAll()

    /**
     * 프리셋 저장. 동일한 (label, anchor, defaultText, styleJson) 조합이 이미 있으면
     * 새 row 를 만들지 않고 기존 id 를 반환해 "저장 버튼을 여러 번 눌러 라이브러리가 중복으로
     * 쌓이는" UX 문제 방지.
     */
    suspend fun save(
        label: String,
        anchor: TemplateAnchor,
        defaultText: String,
        style: CaptionStyle,
    ): Long {
        val finalLabel = label.ifBlank { "템플릿" }
        val styleJson = TimelineSerializer.styleToJson(style)
        val existing = dao.listAll().firstOrNull {
            it.label == finalLabel &&
                it.anchorName == anchor.name &&
                it.defaultText == defaultText &&
                it.styleJson == styleJson
        }
        if (existing != null) return existing.id
        return dao.insert(
            FixedTemplatePresetEntity(
                label = finalLabel,
                anchorName = anchor.name,
                defaultText = defaultText,
                styleJson = styleJson,
            )
        )
    }

    /** 프리셋 → 새 [FixedTextTemplate] 인스턴스(id 는 새로 발급). perSegmentText 는 빈 맵. */
    fun instantiate(preset: FixedTemplatePresetEntity): FixedTextTemplate {
        val style = TimelineSerializer.styleFromJson(preset.styleJson)
            ?: CaptionStyle.DEFAULT
        val anchor = runCatching { TemplateAnchor.valueOf(preset.anchorName) }
            .getOrDefault(TemplateAnchor.TOP_LEFT)
        return FixedTextTemplate(
            label = preset.label,
            anchor = anchor,
            defaultText = preset.defaultText,
            style = style,
        )
    }

    suspend fun delete(preset: FixedTemplatePresetEntity) = dao.delete(preset)
}
