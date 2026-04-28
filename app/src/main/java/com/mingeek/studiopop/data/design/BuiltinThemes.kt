package com.mingeek.studiopop.data.design

import com.mingeek.studiopop.data.effects.builtins.CaptionStylePresets
import com.mingeek.studiopop.data.effects.builtins.TransitionPresets

/**
 * 5종 채널 톤 테마 팩. 각 테마는 폰트/팔레트/LUT/자막 효과/전환 효과 조합으로 채널의
 * 분위기를 한 번에 전환한다. 사용자는 메뉴에서 한 번 선택 = 자막·썸네일·숏츠 보더 모두
 * 일관된 톤으로 정렬.
 *
 * 자산이 비어 있는 항목(폰트 ttf, LUT .cube)이 있어도 fallback 으로 동작 —
 * - 폰트 없음 → SystemDefaultFontPack
 * - LUT 없음 → 원본 영상 (NOOP)
 * - SFX/BGM 없음 → 자막 전환 효과음 미발생
 */
object BuiltinThemes {

    val VLOG = ThemePack(
        id = "theme.vlog",
        displayName = "브이로그",
        description = "부드러운 톤·미세한 줌·감성 자막",
        palette = ColorPalette(
            id = "palette.vlog",
            displayName = "브이로그",
            primary = 0xFF2D2A28.toInt(),
            accent = 0xFFE8C7A5.toInt(),
            neon = 0xFFFFE3C4.toInt(),
            textOnPrimary = 0xFFFFF8F0.toInt(),
            textOnAccent = 0xFF2D2A28.toInt(),
            gradientStart = 0xFFE8C7A5.toInt(),
            gradientEnd = 0xFF2D2A28.toInt(),
        ),
        displayFontPackId = BuiltinFontPacks.DISPLAY_FAT_ROUND.id,
        subtitleFontPackId = BuiltinFontPacks.SUBTITLE_PRETENDARD.id,
        lutId = BuiltinLuts.VINTAGE.id,
        captionEffectIds = listOf(
            CaptionStylePresets.HIGHLIGHT_PEN,
            CaptionStylePresets.CARD_DROP,
        ),
        transitionEffectId = TransitionPresets.DISSOLVE,
        thumbnailDecoEffectId = null,
    )

    val MUKBANG = ThemePack(
        id = "theme.mukbang",
        displayName = "먹방",
        description = "선명한 색·강한 자막·임팩트 효과음",
        palette = ColorPalette(
            id = "palette.mukbang",
            displayName = "먹방",
            primary = 0xFFB22222.toInt(),
            accent = 0xFFFFD400.toInt(),
            neon = 0xFFFFD400.toInt(),
            textOnPrimary = 0xFFFFFFFF.toInt(),
            textOnAccent = 0xFF000000.toInt(),
            gradientStart = 0xFFB22222.toInt(),
            gradientEnd = 0xFFFFD400.toInt(),
        ),
        displayFontPackId = BuiltinFontPacks.DISPLAY_BLACK_SANS.id,
        subtitleFontPackId = BuiltinFontPacks.SUBTITLE_NOTO_BOLD.id,
        lutId = BuiltinLuts.VIVID.id,
        captionEffectIds = listOf(
            CaptionStylePresets.TRIPLE_STROKE,
            CaptionStylePresets.SHADOW_3D,
            CaptionStylePresets.RIBBON,
        ),
        transitionEffectId = TransitionPresets.ZOOM_PUNCH,
    )

    val REVIEW = ThemePack(
        id = "theme.review",
        displayName = "리뷰",
        description = "깔끔한 화이트·뉴스풍 자막·느린 디졸브",
        palette = ColorPalette(
            id = "palette.review",
            displayName = "리뷰",
            primary = 0xFFFFFFFF.toInt(),
            accent = 0xFF1976D2.toInt(),
            neon = 0xFF1976D2.toInt(),
            textOnPrimary = 0xFF101820.toInt(),
            textOnAccent = 0xFFFFFFFF.toInt(),
            gradientStart = 0xFFE3F2FD.toInt(),
            gradientEnd = 0xFF1976D2.toInt(),
        ),
        displayFontPackId = BuiltinFontPacks.SUBTITLE_PRETENDARD.id,
        subtitleFontPackId = BuiltinFontPacks.SUBTITLE_PRETENDARD.id,
        lutId = BuiltinLuts.COOL.id,
        captionEffectIds = listOf(
            CaptionStylePresets.CARD_DROP,
            CaptionStylePresets.GRADIENT_POP,
        ),
        transitionEffectId = TransitionPresets.DISSOLVE,
    )

    val GAME = ThemePack(
        id = "theme.game",
        displayName = "게임",
        description = "네온·글리치·빠른 컷",
        palette = ColorPalette(
            id = "palette.game",
            displayName = "게임",
            primary = 0xFF0F0F1A.toInt(),
            accent = 0xFF00E5FF.toInt(),
            neon = 0xFFFF00E5.toInt(),
            textOnPrimary = 0xFFFFFFFF.toInt(),
            textOnAccent = 0xFF0F0F1A.toInt(),
            gradientStart = 0xFF00E5FF.toInt(),
            gradientEnd = 0xFFFF00E5.toInt(),
        ),
        displayFontPackId = BuiltinFontPacks.CHARACTER_RETRO_ARCADE.id,
        subtitleFontPackId = BuiltinFontPacks.SUBTITLE_NOTO_BOLD.id,
        lutId = BuiltinLuts.CINEMATIC.id,
        captionEffectIds = listOf(
            CaptionStylePresets.GLOW_NEON,
            CaptionStylePresets.GRADIENT_POP,
        ),
        transitionEffectId = TransitionPresets.GLITCH,
    )

    val GACHA = ThemePack(
        id = "theme.gacha",
        displayName = "뽑기",
        description = "노랑·검정 강대비·임팩트 자막·줌 펀치",
        palette = ColorPalette(
            id = "palette.gacha",
            displayName = "뽑기",
            primary = 0xFF000000.toInt(),
            accent = 0xFFFFEB3B.toInt(),
            neon = 0xFFFFEB3B.toInt(),
            textOnPrimary = 0xFFFFEB3B.toInt(),
            textOnAccent = 0xFF000000.toInt(),
            gradientStart = 0xFFFFEB3B.toInt(),
            gradientEnd = 0xFFFF6F00.toInt(),
        ),
        displayFontPackId = BuiltinFontPacks.DISPLAY_BLACK_SANS.id,
        subtitleFontPackId = BuiltinFontPacks.DISPLAY_BLACK_SANS.id,
        lutId = BuiltinLuts.VIVID.id,
        captionEffectIds = listOf(
            CaptionStylePresets.TRIPLE_STROKE,
            CaptionStylePresets.GLOW_NEON,
            CaptionStylePresets.SHADOW_3D,
        ),
        transitionEffectId = TransitionPresets.ZOOM_PUNCH,
    )

    val ALL: List<ThemePack> = listOf(VLOG, MUKBANG, REVIEW, GAME, GACHA)
}

/** 일괄 등록 진입점. 팔레트와 테마를 함께 등록 — 팔레트는 테마와 강결합. */
fun DesignTokens.registerBuiltinThemes() {
    BuiltinThemes.ALL.forEach {
        registerPalette(it.palette)
        registerTheme(it)
    }
}
