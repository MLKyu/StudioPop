package com.mingeek.studiopop.data.editor

/**
 * 자막(또는 텍스트 오버레이) 렌더링 스타일.
 * 좌표: anchorY = 세로 위치 NDC (-1..1, -1=하단, 1=상단). 기본은 하단 근처(-0.8).
 */
data class CaptionStyle(
    val preset: CaptionPreset,
    val anchorY: Float = -0.8f,
    val sizeScale: Float = 1f,
) {
    val textColor: Int get() = preset.textColor
    val outlineColor: Int get() = preset.outlineColor
    val backgroundAlpha: Int get() = preset.backgroundAlpha
    val bold: Boolean get() = preset.bold
    val outlineWidth: Float get() = preset.outlineWidth

    companion object {
        val DEFAULT = CaptionStyle(preset = CaptionPreset.CLEAN)
    }
}

enum class CaptionPreset(
    val label: String,
    val textColor: Int,
    val outlineColor: Int,
    val backgroundAlpha: Int,
    val bold: Boolean,
    val outlineWidth: Float,
) {
    CLEAN(
        label = "Clean",
        textColor = 0xFFFFFFFF.toInt(),
        outlineColor = 0xFF000000.toInt(),
        backgroundAlpha = 0,
        bold = false,
        outlineWidth = 3f,
    ),
    VLOG(
        label = "Vlog",
        textColor = 0xFFFFFFFF.toInt(),
        outlineColor = 0xFF000000.toInt(),
        backgroundAlpha = 160,
        bold = false,
        outlineWidth = 0f,
    ),
    GACHA(
        label = "뽑기 (굵은 노랑)",
        textColor = 0xFFFFEB3B.toInt(), // yellow
        outlineColor = 0xFF000000.toInt(),
        backgroundAlpha = 0,
        bold = true,
        outlineWidth = 6f,
    ),
    SHORTS(
        label = "Shorts (크게)",
        textColor = 0xFFFFFFFF.toInt(),
        outlineColor = 0xFF000000.toInt(),
        backgroundAlpha = 0,
        bold = true,
        outlineWidth = 5f,
    ),
    MINIMAL(
        label = "Minimal",
        textColor = 0xFFFFFFFF.toInt(),
        outlineColor = 0x00000000,
        backgroundAlpha = 0,
        bold = false,
        outlineWidth = 0f,
    ),
}
