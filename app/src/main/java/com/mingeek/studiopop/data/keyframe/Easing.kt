package com.mingeek.studiopop.data.keyframe

import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.exp
import kotlin.math.pow
import kotlin.math.sin

/**
 * 0..1 입력을 0..1 출력으로 매핑하는 시간 보간 함수.
 * - LINEAR: 일정 속도
 * - EASE_IN_OUT: 부드러운 가속/감속 (가장 흔함)
 * - SPRING: 가벼운 오버슛 후 안정 — Pop 자막 등 진입 애니
 * - BEAT_BOUNCE: 비트 동기 펄스용. 1에서 출발해 한 번 튕긴 뒤 1로 복귀.
 *   이 함수는 `progress` 가 한 비트 사이클(0..1) 일 때 scale multiplier 처럼 사용 — 즉
 *   진입 애니에선 어색하므로 BeatBus 와 결합한 펄스 트랙에서만 의미가 있음.
 */
enum class Easing {
    LINEAR,
    EASE_IN,
    EASE_OUT,
    EASE_IN_OUT,
    SPRING,
    BEAT_BOUNCE;

    fun apply(t: Float): Float {
        val x = t.coerceIn(0f, 1f)
        return when (this) {
            LINEAR -> x
            EASE_IN -> x * x
            EASE_OUT -> 1f - (1f - x).pow(2)
            EASE_IN_OUT -> if (x < 0.5f) 2f * x * x else 1f - (-2f * x + 2f).pow(2) / 2f
            SPRING -> {
                // 감쇠 진동: 1.0 근처에서 빠르게 안정. 진입 애니에 자주 쓰는 모양.
                val damping = 6f
                val frequency = 8f
                1f - exp(-damping * x.toDouble()).toFloat() *
                    cos((frequency * x * PI).toFloat())
            }
            BEAT_BOUNCE -> {
                // 0.5 부근에서 피크 후 원위치. scale 곱셈 용도라 base=1, peak=1+amp.
                val amp = 0.25f
                1f + amp * sin((x * PI).toFloat())
            }
        }
    }
}
