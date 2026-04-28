package com.mingeek.studiopop.data.design

/**
 * 채널/테마 톤을 결정하는 색 팔레트. 자막·썸네일·숏츠 보더가 같은 팔레트를 공유하면
 * 채널 일관성이 자연스럽게 만들어진다.
 *
 * 모든 색은 ARGB Int. accent 는 강조 한 줄/박스/외곽선용, neon 은 GLOW·그라디언트의
 * 하이라이트 톤. textOnAccent 는 accent 위에 겹친 텍스트 가독성용.
 */
data class ColorPalette(
    val id: String,
    val displayName: String,
    val primary: Int,
    val accent: Int,
    val neon: Int,
    val textOnPrimary: Int,
    val textOnAccent: Int,
    val gradientStart: Int = primary,
    val gradientEnd: Int = accent,
)

/** 현재 colors.xml 의 ShuPick Crane 톤 (네온 그린 + 다크) — 기본 팔레트. */
val StudioPopDefaultPalette = ColorPalette(
    id = "studiopop.default",
    displayName = "StudioPop 기본",
    primary = 0xFF0D1117.toInt(),
    accent = 0xFFA6FF00.toInt(),
    neon = 0xFFA6FF00.toInt(),
    textOnPrimary = 0xFFFFFFFF.toInt(),
    textOnAccent = 0xFF0D1117.toInt(),
    gradientStart = 0xFF0D1117.toInt(),
    gradientEnd = 0xFFA6FF00.toInt(),
)
