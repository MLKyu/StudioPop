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
    /**
     * R6: 의미적 톤 분석. Gemini 멀티모달이 키프레임 몇 장을 보고 무드 라벨 + 추천 LUT/테마를
     * 채워 줌. [tone] (수치 휴리스틱) 과 보완 관계 — 둘 중 하나가 null 이어도 안전히 동작.
     */
    val aiTone: AiToneAnalysis? = null,
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

/**
 * R6: Gemini 가 키프레임을 본 의미적 톤/무드. [ToneEstimate] 가 측정한 수치를 보완하는
 * 사람이 읽기 좋은 라벨 + LUT/테마 추천. 무료 tier 부담을 줄이려 키프레임 3장 이내만 전송.
 *
 * @param mood "energetic" / "calm" / "dramatic" / "playful" / "tense" / "warm" / "cool" /
 *             "mysterious" 등. 한 단어. 모르면 빈 문자열.
 * @param descriptors 한국어 형용사 리스트 (예: ["밝은", "역동적인", "따뜻한"]). 0~5개.
 * @param recommendedLutId BuiltinLuts 의 id (예: "lut.cinematic"). 매칭 LUT 이 없으면 null.
 * @param recommendedThemeId BuiltinThemes 의 id (예: "theme.vlog"). null 가능.
 * @param reasoning 한 줄(<=80자) 한국어 설명.
 */
data class AiToneAnalysis(
    val mood: String = "",
    val descriptors: List<String> = emptyList(),
    val recommendedLutId: String? = null,
    val recommendedThemeId: String? = null,
    val reasoning: String = "",
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
