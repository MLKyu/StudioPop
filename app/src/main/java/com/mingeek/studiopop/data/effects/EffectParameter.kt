package com.mingeek.studiopop.data.effects

/**
 * 효과가 받는 파라미터의 명세. 효과 자체는 UI 코드에 의존하지 않고, 이 명세만 노출하면
 * 공통 효과 패널이 적절한 컨트롤(슬라이더·색 선택기·스위치·이미지 슬롯)을 자동 렌더링할 수 있다.
 *
 * R1 단계에선 명세만 — 실제 UI 바인딩은 R2+ 에서.
 */
sealed interface EffectParameter {
    val key: String
    val label: String

    data class FloatRange(
        override val key: String,
        override val label: String,
        val min: Float,
        val max: Float,
        val default: Float,
        val step: Float = (max - min) / 100f,
    ) : EffectParameter

    data class IntRange(
        override val key: String,
        override val label: String,
        val min: Int,
        val max: Int,
        val default: Int,
    ) : EffectParameter

    data class Toggle(
        override val key: String,
        override val label: String,
        val default: Boolean,
    ) : EffectParameter

    /** ARGB Int 색. */
    data class Color(
        override val key: String,
        override val label: String,
        val default: Int,
    ) : EffectParameter

    /** 단일 선택 enum. options 는 (id, displayLabel) 쌍. */
    data class Choice(
        override val key: String,
        override val label: String,
        val options: List<Option>,
        val defaultId: String,
    ) : EffectParameter {
        data class Option(val id: String, val label: String)
    }

    /** 외부 자산(폰트·LUT·SFX·BGM·스티커) 슬롯. assetType 은 DesignTokens 의 카테고리 키. */
    data class AssetSlot(
        override val key: String,
        override val label: String,
        val assetType: String,
        val defaultAssetId: String? = null,
    ) : EffectParameter
}

/** 효과 인스턴스가 사용자로부터 받은 파라미터 값. key → value(any of: Float/Int/Boolean/String). */
data class EffectParamValues(
    val values: Map<String, Any> = emptyMap(),
) {
    fun float(key: String, default: Float = 0f): Float =
        (values[key] as? Number)?.toFloat() ?: default

    fun int(key: String, default: Int = 0): Int =
        (values[key] as? Number)?.toInt() ?: default

    fun bool(key: String, default: Boolean = false): Boolean =
        (values[key] as? Boolean) ?: default

    fun color(key: String, default: Int = 0): Int =
        (values[key] as? Number)?.toInt() ?: default

    fun choice(key: String, default: String): String =
        (values[key] as? String) ?: default

    fun assetId(key: String): String? = values[key] as? String
}
