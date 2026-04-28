package com.mingeek.studiopop.data.ai

/**
 * AI 가 영상 분석 후 제안하는 편집 행위. 사용자는 한 번에 받아들이거나 개별 선택 가능.
 *
 * 모든 제안은 "이미 EffectRegistry 에 등록된 효과 id" 또는 "새 텍스트 요소"를 가리키며,
 * 적용은 단순한 add/insert 작업으로 끝나도록 설계된다 — AI 가 직접 렌더링에 관여하지 않는다.
 */
sealed interface EditSuggestion {
    val rationale: String

    /** 자막 추가 — TimingSpec 은 전 영상 word-level 자막부터 단일 강조 한 줄까지 표현. */
    data class AddCaption(
        val sourceStartMs: Long,
        val sourceEndMs: Long,
        val text: String,
        val emphasisCharRanges: List<Pair<Int, Int>> = emptyList(),
        override val rationale: String,
    ) : EditSuggestion

    /** 효과 등록 instance 추가 — 줌 펀치·전환·LUT 등. */
    data class AddEffect(
        val effectDefinitionId: String,
        val sourceStartMs: Long,
        val sourceEndMs: Long,
        val params: Map<String, Any> = emptyMap(),
        override val rationale: String,
    ) : EditSuggestion

    /** 컷 추가 — 무음 구간/필러 단어 정리. */
    data class AddCut(
        val sourceStartMs: Long,
        val sourceEndMs: Long,
        override val rationale: String,
    ) : EditSuggestion

    /** SFX 추가 — DesignTokens.SfxAsset id 참조. */
    data class AddSfx(
        val sfxAssetId: String,
        val sourceStartMs: Long,
        override val rationale: String,
    ) : EditSuggestion

    /** 테마 적용 — ThemePack id 참조. */
    data class ApplyTheme(
        val themeId: String,
        override val rationale: String,
    ) : EditSuggestion
}
