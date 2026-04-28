package com.mingeek.studiopop.data.text

/**
 * 텍스트 한 묶음의 시각 스타일. 단순 텍스트(`text + color + outline`) 를 넘어 글로우·다중
 * 외곽선·그라디언트·배경 박스·카라오케 하이라이트까지 표현 가능.
 *
 * 모든 색은 ARGB Int. 크기는 frame 기준 정규화된 값(`sizeScale` × baseline 폰트 크기)으로
 * 표현해 해상도 변화에 유연하게 대응한다. 실제 baseline 폰트 크기는 렌더러가 결정.
 *
 * R1 단계에선 데이터 모델만 — 실제 페인팅은 R2 에서 ThumbnailComposer 의 GLOW 로직을
 * 포팅하면서 확장.
 */
data class RichTextStyle(
    val fontPackId: String,
    val fontWeight: Int = 700,
    val fillBrush: TextBrush = TextBrush.Solid(0xFFFFFFFF.toInt()),
    val strokes: List<TextStroke> = emptyList(),
    val shadows: List<TextShadow> = emptyList(),
    val glow: TextGlow? = null,
    val background: TextBackground = TextBackground.None,
    val transform: TextTransform = TextTransform.NONE,
    val sizeScale: Float = 1f,
    val letterSpacingEm: Float = 0f,
    val lineHeightMultiplier: Float = 1.1f,
    val align: TextAlign = TextAlign.CENTER,
)

/** 텍스트 채움. */
sealed interface TextBrush {
    /** ARGB Int 단색. */
    data class Solid(val color: Int) : TextBrush

    /** 위→아래 또는 좌→우 방향의 두 색 그라디언트. direction 0..1 = 시작 위치. */
    data class LinearGradient(
        val startColor: Int,
        val endColor: Int,
        val angleDegrees: Float = 90f,
    ) : TextBrush

    /** 무지개/네온 그라디언트 — 다색 stop 리스트. */
    data class MultiStopGradient(
        val stops: List<Stop>,
        val angleDegrees: Float = 90f,
    ) : TextBrush {
        data class Stop(val position: Float, val color: Int)
    }
}

/** 텍스트 외곽선 — 여러 개 쌓아 이중 외곽선·후광 효과를 만들 수 있다. */
data class TextStroke(
    val color: Int,
    val widthPx: Float,
    val blurPx: Float = 0f,
)

/** 드롭 섀도. 여러 개 가능 — 3D 텍스트는 같은 색 다른 offset 으로 여러 장 쌓아 표현. */
data class TextShadow(
    val color: Int,
    val offsetXPx: Float,
    val offsetYPx: Float,
    val blurPx: Float,
)

/** 글로우 (외곽 발광). 썸네일 GLOW 로직을 일반화. */
data class TextGlow(
    val color: Int,
    val radiusPx: Float,
    val intensity: Float = 1f,
)

/** 텍스트 뒤 배경. */
sealed interface TextBackground {
    data object None : TextBackground

    data class Box(
        val color: Int,
        val cornerRadiusPx: Float = 12f,
        val paddingHorizontalPx: Float = 24f,
        val paddingVerticalPx: Float = 12f,
    ) : TextBackground

    data class Bubble(
        val color: Int,
        val pointerSide: PointerSide = PointerSide.BOTTOM_LEFT,
        val cornerRadiusPx: Float = 24f,
    ) : TextBackground {
        enum class PointerSide { TOP_LEFT, TOP_RIGHT, BOTTOM_LEFT, BOTTOM_RIGHT }
    }

    /** 형광펜 스타일 — 글자 베이스라인 살짝 위에서 띠 형태로. */
    data class Highlight(
        val color: Int,
        val heightRatio: Float = 0.6f,
        val offsetYRatio: Float = 0.1f,
    ) : TextBackground

    /** 리본 — 텍스트 양 끝에 삼각/사각 꼬리. */
    data class Ribbon(val color: Int, val tailLength: Float = 24f) : TextBackground

    /** 카드 — Box 와 비슷하지만 아래 그림자가 있는 부유감. */
    data class Card(
        val color: Int,
        val cornerRadiusPx: Float = 16f,
        val shadow: TextShadow,
    ) : TextBackground
}

enum class TextTransform { NONE, UPPERCASE, LOWERCASE, KOREAN_BOLD_FORM }

enum class TextAlign { LEFT, CENTER, RIGHT }
