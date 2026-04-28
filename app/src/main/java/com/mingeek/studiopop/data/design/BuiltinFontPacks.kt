package com.mingeek.studiopop.data.design

/**
 * 시스템에 미리 등록되는 폰트 팩들. 실제 .ttf 파일은 R2 단계에선 미배치 — placeholder 로
 * id 와 UseCase 만 잡아두고, 사용자가 라이센스 결정 후 [AssetSource.Bundled] 또는
 * [AssetSource.UserImported] 로 바이너리를 채워 넣으면 된다.
 *
 * 자산이 비어 있는 폰트 팩을 효과가 참조하면 렌더러는 [SystemDefaultFontPack] 으로 자동
 * fallback — 화면에 시스템 폰트로 출력되며 충돌 없음.
 *
 * 추천 후보(라이센스 OFL/SIL — 무료 상업 사용 가능):
 * - DISPLAY_TITLE: Black Han Sans, Sunflower-700, Bagel Fat One
 * - SUBTITLE: Noto Sans KR Bold, Pretendard Bold
 * - CHARACTER: 양진체(YangJin) 또는 Cafe24 시리즈
 * - BODY: Noto Sans KR Regular, Pretendard Regular
 */
object BuiltinFontPacks {

    val DISPLAY_BLACK_SANS = FontPack(
        id = "font.display.black_sans",
        displayName = "Black Sans (디스플레이)",
        recommendedFor = listOf(FontPack.UseCase.DISPLAY_TITLE),
    )

    val DISPLAY_FAT_ROUND = FontPack(
        id = "font.display.fat_round",
        displayName = "Fat Round (둥근 굵은 디스플레이)",
        recommendedFor = listOf(FontPack.UseCase.DISPLAY_TITLE),
    )

    val SUBTITLE_NOTO_BOLD = FontPack(
        id = "font.subtitle.noto_bold",
        displayName = "Noto Sans Bold (자막)",
        recommendedFor = listOf(FontPack.UseCase.SUBTITLE),
    )

    val SUBTITLE_PRETENDARD = FontPack(
        id = "font.subtitle.pretendard",
        displayName = "Pretendard (자막)",
        recommendedFor = listOf(FontPack.UseCase.SUBTITLE),
    )

    val CHARACTER_HANDWRITING = FontPack(
        id = "font.character.handwriting",
        displayName = "손글씨 (캐릭터)",
        recommendedFor = listOf(FontPack.UseCase.CHARACTER),
    )

    val CHARACTER_RETRO_ARCADE = FontPack(
        id = "font.character.retro_arcade",
        displayName = "레트로 아케이드 (캐릭터)",
        recommendedFor = listOf(FontPack.UseCase.CHARACTER),
    )

    val BODY_NOTO_REGULAR = FontPack(
        id = "font.body.noto_regular",
        displayName = "Noto Sans (본문)",
        recommendedFor = listOf(FontPack.UseCase.BODY),
    )

    val BODY_PRETENDARD = FontPack(
        id = "font.body.pretendard_regular",
        displayName = "Pretendard (본문)",
        recommendedFor = listOf(FontPack.UseCase.BODY),
    )

    val ALL: List<FontPack> = listOf(
        DISPLAY_BLACK_SANS,
        DISPLAY_FAT_ROUND,
        SUBTITLE_NOTO_BOLD,
        SUBTITLE_PRETENDARD,
        CHARACTER_HANDWRITING,
        CHARACTER_RETRO_ARCADE,
        BODY_NOTO_REGULAR,
        BODY_PRETENDARD,
    )
}

/** 일괄 등록 진입점. AppContainer 가 designTokens 초기화 직후 호출. */
fun DesignTokens.registerBuiltinFontPacks() {
    BuiltinFontPacks.ALL.forEach { registerFontPack(it) }
}
