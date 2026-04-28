package com.mingeek.studiopop.data.ai

import com.mingeek.studiopop.data.thumbnail.ThumbnailVariant

/**
 * 한 영상에 대한 업로드 패키지. AI 스토리보드(원클릭 패키지) 기능의 산출물 — 사용자는
 * 받은 패키지를 그대로 업로드하거나 부분 편집할 수 있다.
 *
 * R1 단계에선 데이터 모델만 — 실제 채움은 [AiAssist.generatePackage] 의 R5 구현에서.
 */
data class YoutubePackage(
    val titles: List<String>,
    val description: String,
    val tags: List<String>,
    val chapters: List<Chapter>,
    val hashtags: List<String>,
    val thumbnailVariants: List<ThumbnailVariant>,
    val shortsHighlights: List<HighlightSpan>,
    /**
     * R6: Gemini 톤 분석 결과 (있으면). UI 가 별도 카드로 노출 + 추천 LUT 한 탭 적용에 사용.
     * null 이면 분석 미실행/실패 (description 의 "분위기" 라인도 비어 있음).
     */
    val aiTone: AiToneAnalysis? = null,
)

data class Chapter(
    val startMs: Long,
    val title: String,
)
