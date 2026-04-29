package com.mingeek.studiopop.data.thumbnail

import android.graphics.Color
import android.graphics.Rect
import java.util.UUID

/** 텍스트 배치 위치(상하 + 좌중우). 1280×720 기준 9개 anchor 중 5개. */
enum class TextAnchor { TOP_LEFT, TOP_RIGHT, BOTTOM_LEFT, BOTTOM_RIGHT, CENTER }

/** 메인 텍스트 주변 시각 강조 스타일. */
enum class DecorationStyle {
    NONE,
    /** 텍스트 뒤 단색 박스 (accentColor) */
    BOX,
    /** 굵은 외곽선 (accentColor) */
    OUTLINE,
    /** 텍스트 좌/우에 화살표 마커 */
    ARROW,
    /** 텍스트 주변 글로우 (accentColor 광원) */
    GLOW,
}

/** 감지된 얼굴/주체 영역에 대한 시각 강조. */
enum class SubjectEmphasis {
    NONE,
    /** 빨간 사각형 테두리 */
    BORDER,
    /** 주체 외곽 글로우 */
    GLOW,
    /** "강조용" 줌 펀치 (subjectCrop 자체로 효과 ↑) */
    ZOOM_PUNCH,
}

/**
 * 한 썸네일 변형의 모든 시각 사양.
 * Composer 가 이걸 받아 1280×720 Bitmap 으로 그림.
 *
 * ### TODO — A/B 썸네일 자동 테스트 (외부 인증 + 비공식 API 필요)
 * 현재는 [com.mingeek.studiopop.data.ai.AiAssist] 가 여러 [ThumbnailVariant] 를 생성해 사용자가
 * 수동으로 1개 선택. YouTube 의 자동 A/B 테스트 (Test & Compare) 는 YouTube Studio 의 비공개
 * Experiments API 만 지원 — 공식 Data API 에 미노출.
 *  - 옵션 A: Studio 비공식 API reverse-engineer (TOS 위험, 권장 X)
 *  - 옵션 B: 공식 `videos.update` 로 일정 시간마다 thumbnail 교체 + Analytics API 의 CTR 비교
 *    스케줄러 구현 — WorkManager + ApiKeyStore 의 google.oauth.token 필요
 *  - 옵션 C: 외부 서비스(TubeBuddy/VidIQ) 위임 — 사용자가 별도 가입
 * 무료 라운드 스코프 밖. 일단 다중 변형 생성 + UX 의 manual pick 으로 유사 효과.
 */
data class ThumbnailVariant(
    val id: String = UUID.randomUUID().toString(),
    val mainText: String,
    val subText: String = "",
    /** 메인 텍스트 색 (#AARRGGBB). */
    val mainColor: Int = Color.WHITE,
    /** 데코·서브텍스트 강조용 보조 색 (#AARRGGBB). */
    val accentColor: Int = Color.parseColor("#FFEB00"),
    val anchor: TextAnchor = TextAnchor.BOTTOM_LEFT,
    /** 1.0 = 기본. 0.7 ~ 1.4 권장. */
    val sizeScale: Float = 1.0f,
    val decoration: DecorationStyle = DecorationStyle.NONE,
    val subjectEmphasis: SubjectEmphasis = SubjectEmphasis.NONE,
    /** 원본 source bitmap 좌표계의 줌 영역. null 이면 전체 사용. */
    val subjectCrop: Rect? = null,
    /** Gemini 가 왜 이 안을 추천했는지 한줄 설명 (선택). */
    val reasoning: String? = null,
)
