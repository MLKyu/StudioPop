package com.mingeek.studiopop.data.ai

import android.graphics.Rect
import android.net.Uri

/**
 * AI 가 영상을 본 결과의 통합 표현. 각 필드는 분석이 안 됐을 때 비어 있을 수 있어
 * 구독자는 부분 정보로도 동작 가능 — Gemini 가 실패해도 ML Kit 얼굴 결과는 유지되는 식.
 */
data class VideoAnalysis(
    val sourceUri: Uri,
    val durationMs: Long,
    val scenes: List<SceneSummary> = emptyList(),
    val highlights: List<HighlightSpan> = emptyList(),
    val keywords: List<String> = emptyList(),
    val tone: ToneEstimate? = null,
    val faces: List<FaceTrack> = emptyList(),
    val srtCues: List<com.mingeek.studiopop.data.caption.Cue> = emptyList(),
)

/** 씬 단위 요약 — Gemini 가 키프레임 묶음을 보고 짧게 설명. */
data class SceneSummary(
    val startMs: Long,
    val endMs: Long,
    val title: String,
    val description: String = "",
    val mood: String = "",
)

/** 하이라이트 시간 범위 + 추천 이유 + 강도 점수(0..1). */
data class HighlightSpan(
    val startMs: Long,
    val endMs: Long,
    val score: Float,
    val reason: String,
)

/** 영상 톤(밝기·채도·콘트라스트) 추정 — 자동 LUT 추천 입력. */
data class ToneEstimate(
    val brightness: Float,    // -1.0 (어두움) ~ 1.0 (밝음)
    val saturation: Float,    // -1.0 (저채도) ~ 1.0 (고채도)
    val contrast: Float,      // -1.0 (낮음) ~ 1.0 (높음)
    val warmth: Float,        // -1.0 (차가움) ~ 1.0 (따뜻함)
)

/** 인물 트랙 — ML Kit Face Detection 결과의 시간순 묶음. AVOID_FACE 자막 위치 결정 등에 사용. */
data class FaceTrack(
    val trackId: Int,
    val keyframes: List<FaceKeyframe>,
)

data class FaceKeyframe(
    val timeMs: Long,
    val sourceRect: Rect,
)
