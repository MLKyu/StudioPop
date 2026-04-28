package com.mingeek.studiopop.data.design

/**
 * 폰트 패밀리 한 묶음. 한글 디스플레이 폰트(블랙한스산스, 양진체 등)를 도입할 때
 * 굵기 변형(Regular/Bold/Black)을 함께 들고 다닌다. R1 단계에선 기본 시스템 폰트만
 * 등록되며, 실제 폰트 파일 추가는 사용자 라이센스 확정 후 R2 에서.
 *
 * @property weights 굵기별 소스. 키는 100..900 범위의 표준 weight (예: 400=Regular, 700=Bold).
 *                   비어 있으면 시스템 fallback (Typeface.DEFAULT) 사용.
 */
data class FontPack(
    val id: String,
    val displayName: String,
    val weights: Map<Int, AssetSource> = emptyMap(),
    val supportsKorean: Boolean = true,
    val recommendedFor: List<UseCase> = emptyList(),
) {
    enum class UseCase {
        DISPLAY_TITLE,    // 굵고 큰 타이틀 (썸네일 메인 등)
        SUBTITLE,         // 자막
        BODY,             // 본문/설명
        CHARACTER,        // 캐릭터·뽑기 톤 (양진체 같은)
    }
}

/**
 * 시스템 기본 폰트 — 자산이 추가되기 전 안전한 fallback.
 */
val SystemDefaultFontPack = FontPack(
    id = "system.default",
    displayName = "시스템 기본",
    weights = emptyMap(),
    recommendedFor = listOf(FontPack.UseCase.BODY),
)
