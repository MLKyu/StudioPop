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
