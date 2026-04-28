package com.mingeek.studiopop.data.text

import android.graphics.RectF
import com.mingeek.studiopop.data.ai.FaceTrack
import com.mingeek.studiopop.data.keyframe.Vec2

/**
 * [TextPosition] 을 실제 화면 NDC 좌표로 해석하는 단일 진입점.
 *
 * Static 은 그대로, Animated 는 KeyframeTrack 샘플, Tracked 는 [FaceTrack] 들을 시간순 보간해
 * 얼굴 위치를 따라가거나 피한다. 렌더 단계(R3+)는 이 함수만 호출하면 됨.
 *
 * 좌표계: NDC (-1..1). 화면 너비/높이가 다르더라도 같은 NDC 가 같은 의미를 갖도록 한다.
 *
 * AVOID_FACE 정책:
 *  - 얼굴이 화면 상반(>0) 에 있으면 자막은 하단 (-0.8) 으로
 *  - 얼굴이 화면 하반(<=0) 에 있으면 자막은 상단 ( 0.8) 으로
 *  - 얼굴이 여러 개면 가장 큰 박스 기준
 *  - 얼굴 트랙이 비면 fallback 사용
 */
object FaceAvoidanceResolver {

    /**
     * @param frameWidth/frameHeight FaceTrack.keyframes 안의 sourceRect 가 사용한 좌표계의
     *                                프레임 크기 (보통 영상 원본 픽셀 폭/높이).
     */
    fun resolve(
        position: TextPosition,
        timeMs: Long,
        faces: List<FaceTrack>,
        frameWidth: Int,
        frameHeight: Int,
    ): Vec2 = when (position) {
        is TextPosition.Static -> Vec2(position.anchorX, position.anchorY)

        is TextPosition.Animated -> {
            position.track.sampleAt(timeMs) ?: position.fallback
        }

        is TextPosition.Tracked -> {
            val rect = sampleLargestFace(faces, timeMs)
            if (rect == null) {
                Vec2(position.fallbackAnchorX, position.fallbackAnchorY)
            } else {
                val ndc = rectCenterToNdc(rect, frameWidth, frameHeight)
                when (position.mode) {
                    TextPosition.Tracked.TrackMode.FOLLOW_FACE,
                    TextPosition.Tracked.TrackMode.FOLLOW_SUBJECT -> Vec2(
                        x = (ndc.x + position.offsetX).coerceIn(-1f, 1f),
                        y = (ndc.y + position.offsetY).coerceIn(-1f, 1f),
                    )
                    TextPosition.Tracked.TrackMode.AVOID_FACE -> {
                        // 얼굴이 위쪽(NDC y > 0) 이면 자막 아래로, 아래쪽이면 위로
                        val anchorY = if (ndc.y > 0f) -0.8f else 0.8f
                        Vec2(
                            x = (position.fallbackAnchorX + position.offsetX).coerceIn(-1f, 1f),
                            y = (anchorY + position.offsetY).coerceIn(-1f, 1f),
                        )
                    }
                }
            }
        }
    }

    /**
     * 주어진 시각에서 가장 큰 얼굴 박스를 보간해 반환. 트랙이 비면 null.
     * 키프레임이 한 개면 그 값, 두 개 이상이면 인접 두 키프레임을 선형 보간.
     */
    private fun sampleLargestFace(faces: List<FaceTrack>, timeMs: Long): RectF? {
        if (faces.isEmpty()) return null
        val candidates = faces.mapNotNull { sampleTrack(it, timeMs) }
        if (candidates.isEmpty()) return null
        return candidates.maxByOrNull { it.width() * it.height() }
    }

    private fun sampleTrack(track: FaceTrack, timeMs: Long): RectF? {
        val keys = track.keyframes
        if (keys.isEmpty()) return null
        if (timeMs <= keys.first().timeMs) return keys.first().sourceRect.toRectF()
        if (timeMs >= keys.last().timeMs) return keys.last().sourceRect.toRectF()
        var lo = 0
        var hi = keys.size - 1
        while (lo + 1 < hi) {
            val mid = (lo + hi) / 2
            if (keys[mid].timeMs <= timeMs) lo = mid else hi = mid
        }
        val a = keys[lo]
        val b = keys[hi]
        val span = (b.timeMs - a.timeMs).coerceAtLeast(1L)
        val t = (timeMs - a.timeMs).toFloat() / span
        val ar = a.sourceRect
        val br = b.sourceRect
        return RectF(
            ar.left + (br.left - ar.left) * t,
            ar.top + (br.top - ar.top) * t,
            ar.right + (br.right - ar.right) * t,
            ar.bottom + (br.bottom - ar.bottom) * t,
        )
    }

    private fun rectCenterToNdc(rect: RectF, frameWidth: Int, frameHeight: Int): Vec2 {
        if (frameWidth <= 0 || frameHeight <= 0) return Vec2(0f, 0f)
        val cx = (rect.left + rect.right) / 2f
        val cy = (rect.top + rect.bottom) / 2f
        // NDC: x: 좌(-1)→우(+1), y: 하(-1)→상(+1)
        val ndcX = (cx / frameWidth) * 2f - 1f
        val ndcY = -((cy / frameHeight) * 2f - 1f)
        return Vec2(ndcX.coerceIn(-1f, 1f), ndcY.coerceIn(-1f, 1f))
    }

    private fun android.graphics.Rect.toRectF(): RectF =
        RectF(left.toFloat(), top.toFloat(), right.toFloat(), bottom.toFloat())
}
