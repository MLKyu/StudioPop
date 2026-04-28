package com.mingeek.studiopop.data.text

import com.mingeek.studiopop.data.keyframe.KeyframeTrack
import com.mingeek.studiopop.data.keyframe.Vec2

/**
 * 텍스트의 화면 위치. 단순 anchor 부터 인물 트래킹·키프레임 이동까지 단일 모델로 표현.
 *
 * - Static: 한 번 정해진 NDC 좌표. 기존 CaptionStyle.anchorX/Y 와 동등.
 * - Tracked: ML Kit 얼굴 감지 결과를 따라 이동(또는 회피)하는 실시간 추적.
 * - Animated: 키프레임 트랙 — 켄번즈 텍스트, 슬라이드 자막에 사용.
 */
sealed interface TextPosition {

    data class Static(val anchorX: Float, val anchorY: Float) : TextPosition

    data class Tracked(
        val mode: TrackMode,
        val fallbackAnchorX: Float = 0f,
        val fallbackAnchorY: Float = -0.8f,
        val offsetX: Float = 0f,
        val offsetY: Float = 0f,
    ) : TextPosition {
        enum class TrackMode {
            FOLLOW_FACE,
            AVOID_FACE,
            FOLLOW_SUBJECT,
        }
    }

    data class Animated(
        val track: KeyframeTrack<Vec2>,
        val fallback: Vec2 = Vec2(0f, -0.8f),
    ) : TextPosition

    companion object {
        val DEFAULT_BOTTOM = Static(anchorX = 0f, anchorY = -0.8f)
        val DEFAULT_TOP = Static(anchorX = 0f, anchorY = 0.8f)
        val DEFAULT_CENTER = Static(anchorX = 0f, anchorY = 0f)
    }
}
