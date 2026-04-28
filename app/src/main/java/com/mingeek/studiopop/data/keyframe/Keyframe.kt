package com.mingeek.studiopop.data.keyframe

/**
 * 시간(ms) 위에 놓인 임의 타입 T 의 스냅샷 + 이전 키프레임에서 이쪽으로 들어올 때의 이징.
 * 첫 키프레임의 easing 은 무시(시작점이라 들어오는 보간이 없음).
 *
 * R1 골격 단계에선 기존 [com.mingeek.studiopop.data.editor.MosaicKeyframe] 같은
 * 도메인 키프레임을 이쪽으로 마이그레이트하지 않는다 — 기존 구조는 그대로 두고,
 * 새 효과(자막 위치, 켄번즈, 줌 펀치, 스티커 트래킹)는 처음부터 이 일반화 모델 위에서 작성.
 */
data class Keyframe<T>(
    val timeMs: Long,
    val value: T,
    val easing: Easing = Easing.EASE_IN_OUT,
)

/**
 * 시간순으로 정렬된 키프레임 트랙. [interpolator] 가 두 값 사이를 보간.
 * 트랙은 비어있을 수 있고(= 모든 sample 호출이 null), 단일 키프레임이면 항상 그 값.
 */
class KeyframeTrack<T>(
    keyframes: List<Keyframe<T>>,
    private val interpolator: (a: T, b: T, t: Float) -> T,
) {
    private val sorted: List<Keyframe<T>> = keyframes.sortedBy { it.timeMs }

    val isEmpty: Boolean get() = sorted.isEmpty()

    val firstTimeMs: Long? get() = sorted.firstOrNull()?.timeMs
    val lastTimeMs: Long? get() = sorted.lastOrNull()?.timeMs

    /**
     * 주어진 시각에서의 보간된 값. 트랙이 비면 null. 트랙 범위 밖이면 양 끝점 값으로 클램프.
     */
    fun sampleAt(timeMs: Long): T? {
        if (sorted.isEmpty()) return null
        if (timeMs <= sorted.first().timeMs) return sorted.first().value
        if (timeMs >= sorted.last().timeMs) return sorted.last().value

        // 이진 탐색으로 [a, b] 구간 찾기
        var lo = 0
        var hi = sorted.size - 1
        while (lo + 1 < hi) {
            val mid = (lo + hi) / 2
            if (sorted[mid].timeMs <= timeMs) lo = mid else hi = mid
        }
        val a = sorted[lo]
        val b = sorted[hi]
        val span = (b.timeMs - a.timeMs).coerceAtLeast(1L)
        val raw = ((timeMs - a.timeMs).toFloat() / span)
        val eased = b.easing.apply(raw)
        return interpolator(a.value, b.value, eased)
    }

    fun keyframes(): List<Keyframe<T>> = sorted
}

/** Float 보간 — Position·Scale·Rotation·Opacity·Color alpha 등에 공통. */
fun lerpFloat(a: Float, b: Float, t: Float): Float = a + (b - a) * t

/** 2D Float 쌍 보간 — Position(NDC), Anchor 등. */
data class Vec2(val x: Float, val y: Float)

fun lerpVec2(a: Vec2, b: Vec2, t: Float): Vec2 =
    Vec2(lerpFloat(a.x, b.x, t), lerpFloat(a.y, b.y, t))

/** ARGB 색 보간 (premultiplied 가 아닌 단순 채널별 lerp — 간단/저비용). */
fun lerpArgb(a: Int, b: Int, t: Float): Int {
    val ax = (a ushr 24) and 0xFF
    val ar = (a ushr 16) and 0xFF
    val ag = (a ushr 8) and 0xFF
    val ab = a and 0xFF
    val bx = (b ushr 24) and 0xFF
    val br = (b ushr 16) and 0xFF
    val bg = (b ushr 8) and 0xFF
    val bb = b and 0xFF
    val ix = (ax + (bx - ax) * t).toInt().coerceIn(0, 255)
    val ir = (ar + (br - ar) * t).toInt().coerceIn(0, 255)
    val ig = (ag + (bg - ag) * t).toInt().coerceIn(0, 255)
    val ib = (ab + (bb - ab) * t).toInt().coerceIn(0, 255)
    return (ix shl 24) or (ir shl 16) or (ig shl 8) or ib
}
