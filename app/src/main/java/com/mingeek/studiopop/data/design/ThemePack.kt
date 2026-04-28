package com.mingeek.studiopop.data.design

/**
 * 채널 톤을 결정하는 한 묶음. ThemePack 한 개 적용 = 자막·썸네일·숏츠 보더·SFX 전반이
 * 일관된 분위기로 정렬된다.
 *
 * 자산은 모두 id 참조 — 실제 자산 인스턴스는 DesignTokens 에서 lookup. 빠진 자산이 있으면
 * 시스템 fallback 으로 부드럽게 대체.
 *
 * @property captionEffectIds 이 테마에 어울리는 자막 스타일 효과 id 목록. 사용자가
 *                            자막을 추가할 때 자동 추천 1순위.
 * @property transitionEffectId 이 테마의 기본 전환 효과 id.
 * @property thumbnailDecoEffectId 썸네일 메인 텍스트 데코 기본값 id.
 */
data class ThemePack(
    val id: String,
    val displayName: String,
    val description: String,
    val palette: ColorPalette,
    val displayFontPackId: String,
    val subtitleFontPackId: String,
    val lutId: String? = null,
    val sfxIds: List<String> = emptyList(),
    val bgmIds: List<String> = emptyList(),
    val stickerPackIds: List<String> = emptyList(),
    val captionEffectIds: List<String> = emptyList(),
    val transitionEffectId: String? = null,
    val thumbnailDecoEffectId: String? = null,
)

/** 설정 미적용 상태에서 안전한 기본 테마. */
val StudioPopDefaultTheme = ThemePack(
    id = "studiopop.default",
    displayName = "StudioPop 기본",
    description = "네온 그린 + 다크 — 채널 기본 톤",
    palette = StudioPopDefaultPalette,
    displayFontPackId = SystemDefaultFontPack.id,
    subtitleFontPackId = SystemDefaultFontPack.id,
)
