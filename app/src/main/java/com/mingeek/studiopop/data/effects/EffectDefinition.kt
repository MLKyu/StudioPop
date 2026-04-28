package com.mingeek.studiopop.data.effects

/**
 * 한 효과의 메타데이터. id 는 전 시스템 고유 — 카테고리 prefix 권장 (예: "transition.zoom_punch").
 * 효과의 실제 렌더링 로직은 [EffectRenderer] 가 담당하며, 카테고리에 맞는 렌더러가 4 출력
 * 파이프라인(Preview/Export/Thumbnail/Shorts) 에서 호출한다.
 *
 * @property defaultParams 새로 추가될 때 채워질 기본값.
 * @property previewHint UI 가 카드 형태로 보여줄 때 도움이 되는 짧은 설명/이모지/색.
 */
data class EffectDefinition(
    val id: String,
    val displayName: String,
    val category: EffectCategory,
    val parameters: List<EffectParameter> = emptyList(),
    val previewHint: PreviewHint = PreviewHint(),
    val defaultParams: EffectParamValues = EffectParamValues(),
) {
    init {
        require(id.isNotBlank()) { "EffectDefinition id must not be blank" }
        val keys = parameters.map { it.key }
        require(keys.size == keys.toSet().size) {
            "EffectDefinition '$id' has duplicate parameter keys"
        }
    }

    data class PreviewHint(
        val emoji: String = "",
        val tagLine: String = "",
        val accentColor: Int = 0,
    )
}
