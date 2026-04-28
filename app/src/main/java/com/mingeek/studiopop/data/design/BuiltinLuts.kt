package com.mingeek.studiopop.data.design

/**
 * 시스템에 미리 등록되는 LUT placeholder 5종. 실제 .cube 파일은 R3 단계에선 미배치 —
 * 사용자가 라이센스 결정 후 [AssetSource.Bundled] 또는 [AssetSource.UserImported] 로
 * 바이너리를 채워 넣으면 [CubeLutLoader] 로 로드된다.
 *
 * 추천 카테고리:
 *  - Cinematic: 어둡고 따뜻한 톤. 영화 같은 분위기.
 *  - Vivid: 채도·콘트라스트 강조. 짧은 영상·뽑기·리액션.
 *  - Mono: 흑백.
 *  - Vintage: 빛바랜 색감 + 약간의 그레인.
 *  - Cool: 푸르스름한 톤. 여름·새벽.
 *
 * placeholder 가 가리키는 자산이 없는 LUT 효과를 호출하면 렌더러는 NOOP — 원본 영상이
 * 그대로 출력된다. 사용자에겐 LUT 카드는 보이지만 적용 효과는 없는 상태.
 */
object BuiltinLuts {

    val CINEMATIC = LutAsset(
        id = "lut.cinematic",
        displayName = "시네마틱",
        source = AssetSource.Bundled("luts/cinematic.cube"),
        previewSwatch = 0xFF6E5847.toInt(),
        recommendedIntensity = 0.85f,
        tags = listOf("dark", "warm", "moody"),
    )

    val VIVID = LutAsset(
        id = "lut.vivid",
        displayName = "비비드",
        source = AssetSource.Bundled("luts/vivid.cube"),
        previewSwatch = 0xFFFF6B35.toInt(),
        recommendedIntensity = 0.9f,
        tags = listOf("saturated", "punchy", "shorts"),
    )

    val MONO = LutAsset(
        id = "lut.mono",
        displayName = "모노",
        source = AssetSource.Bundled("luts/mono.cube"),
        previewSwatch = 0xFF888888.toInt(),
        recommendedIntensity = 1.0f,
        tags = listOf("black-white"),
    )

    val VINTAGE = LutAsset(
        id = "lut.vintage",
        displayName = "빈티지",
        source = AssetSource.Bundled("luts/vintage.cube"),
        previewSwatch = 0xFFC8A977.toInt(),
        recommendedIntensity = 0.75f,
        tags = listOf("retro", "faded"),
    )

    val COOL = LutAsset(
        id = "lut.cool",
        displayName = "쿨톤",
        source = AssetSource.Bundled("luts/cool.cube"),
        previewSwatch = 0xFF5C8FB8.toInt(),
        recommendedIntensity = 0.85f,
        tags = listOf("cool", "blue", "dawn"),
    )

    val ALL: List<LutAsset> = listOf(CINEMATIC, VIVID, MONO, VINTAGE, COOL)
}

/** 일괄 등록 진입점. */
fun DesignTokens.registerBuiltinLuts() {
    BuiltinLuts.ALL.forEach { registerLut(it) }
}
