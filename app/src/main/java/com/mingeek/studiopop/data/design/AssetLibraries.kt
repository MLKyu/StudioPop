package com.mingeek.studiopop.data.design

/**
 * 디자인 자산들의 가벼운 라이브러리 항목. 각 자산은 [id] 로 효과 시스템과 연결되며,
 * 실제 바이너리는 [source] 가 가리키는 위치에서 로드된다. R1 골격 단계에선 빈
 * 라이브러리들이 기본 — 사용자 라이센스 결정 후 자산을 채워 넣는다.
 */

/**
 * 컬러 LUT 자산. .cube 형식 권장 (Media3 ColorLut + Cube 파서 호환).
 * intensityRange 는 UI 슬라이더 기본값 — 0=원본, 1=완전 적용. 일부 LUT 은 강하게
 * 적용 시 원본 디테일이 사라지므로 추천값을 함께 노출한다.
 */
data class LutAsset(
    val id: String,
    val displayName: String,
    val source: AssetSource,
    val previewSwatch: Int = 0,
    val recommendedIntensity: Float = 1f,
    val tags: List<String> = emptyList(),
)

/**
 * 효과음(SFX) 자산. 짧은 (0.1~3s) 오디오. 카테고리별로 분류해 자막 진입·전환·강조에
 * 자동 매칭되도록 한다.
 */
data class SfxAsset(
    val id: String,
    val displayName: String,
    val source: AssetSource,
    val durationMs: Long,
    val category: Category,
    val tags: List<String> = emptyList(),
) {
    enum class Category {
        WHOOSH,       // 휘리릭, 슬라이드/줌 펀치 동반
        IMPACT,       // 팡, 강조 자막 동반
        TICK,         // 띠링/포인트, 카운트다운·강조어
        UI_FEEDBACK,  // 클릭/뽑기 결과 등 인터랙션
        AMBIENCE,     // 짧은 분위기
    }
}

/**
 * 배경 음악(BGM) 자산. 무드·BPM 정보가 있어 비트 분석 결과와 매칭하거나 무드 필터에 활용.
 */
data class BgmAsset(
    val id: String,
    val displayName: String,
    val source: AssetSource,
    val durationMs: Long,
    val mood: Mood,
    val bpm: Int? = null,
    val tags: List<String> = emptyList(),
    val licenseNote: String = "",
) {
    enum class Mood {
        UPBEAT, CHILL, EPIC, FUNNY, EMOTIONAL, TENSE, NEUTRAL,
    }
}

/**
 * 스티커 한 묶음. 동일 컨셉의 PNG/SVG/Lottie 여러 개를 묶어 카테고리화.
 * R1 단계에선 빈 묶음 — 자산 큐레이션 후 채움.
 */
data class StickerPack(
    val id: String,
    val displayName: String,
    val items: List<StickerItem>,
    val previewItemId: String? = null,
)

data class StickerItem(
    val id: String,
    val source: AssetSource,
    val isAnimated: Boolean = false,
    val tags: List<String> = emptyList(),
)
