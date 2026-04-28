package com.mingeek.studiopop.data.ai

import android.net.Uri
import com.mingeek.studiopop.data.design.LutAsset
import com.mingeek.studiopop.data.design.ThemePack

/**
 * 영상 분석 + 편집 추천 + 업로드 패키지 생성을 하나로 묶은 AI 어시스트의 단일 진입점.
 *
 * 기존 단일 목적 컴포넌트들([com.mingeek.studiopop.data.thumbnail.GeminiThumbnailAdvisor],
 * [com.mingeek.studiopop.data.thumbnail.GeminiCopywriter],
 * [com.mingeek.studiopop.data.shorts.GeminiHighlightPicker]) 은 그대로 두고, 이 인터페이스
 * 뒤에서 조합돼 호출된다 (어댑터 = [DefaultAiAssist]).
 *
 * R1 단계에선 [DefaultAiAssist] 가 모든 메서드에 NotImplementedError 를 반환하는 stub —
 * 인터페이스 자체가 callsite 자리를 잡는 것이 목적. R5 부터 실제 구현이 들어옴.
 */
interface AiAssist {

    /** 영상 한 편에 대한 통합 분석 결과. 부분 실패는 빈 필드로 표현. */
    suspend fun analyzeVideo(uri: Uri): Result<VideoAnalysis>

    /** 분석 결과를 바탕으로 한 편집 제안 묶음. */
    suspend fun suggestEdits(analysis: VideoAnalysis): Result<List<EditSuggestion>>

    /** 분석 + 추가 메타(채널 톤, 사용자 입력 주제) 로 업로드 패키지를 생성. */
    suspend fun generatePackage(
        analysis: VideoAnalysis,
        topic: String? = null,
    ): Result<YoutubePackage>

    /**
     * 채널 과거 영상의 색·폰트·톤을 학습해 추천 테마 반환. R6 (헤비 항목) — R5 까지는 항상
     * StudioPop 기본 테마 반환.
     */
    suspend fun suggestThemeFromHistory(channelId: String): Result<ThemePack>

    /**
     * 영상의 톤 분석 결과로 어울리는 LUT 추천. R5 에서 실제 Gemini Vision 호출로 채워짐 —
     * 그 전까지는 [VideoAnalysis.tone] 의 휴리스틱(없으면 null) 으로 fallback.
     *
     * @return 추천 LUT 1개. 영상이 이미 잘 나와 LUT 가 불필요하면 null.
     */
    suspend fun suggestLut(analysis: VideoAnalysis): Result<LutAsset?>
}
