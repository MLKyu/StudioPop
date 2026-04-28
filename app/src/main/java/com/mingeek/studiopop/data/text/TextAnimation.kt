package com.mingeek.studiopop.data.text

import com.mingeek.studiopop.data.keyframe.Easing

/**
 * 텍스트 진입/퇴장 애니메이션 사양. 실제 보간은 [Easing] 와 [perChar] 의 조합으로
 * 결정 — 한 텍스트 전체에 한 번 vs. 글자별 캐스케이드.
 *
 * @property kind 어떤 모양으로 들어올/나갈지.
 * @property perChar true 면 글자별로 시간차를 두고 순차 적용 (Word-cascade 자막).
 * @property cascadeStaggerMs perChar 일 때 글자 간 시간차 (ms). 단어 단위 cascade 도 동일.
 * @property beatSync true 면 비트 시점에 추가 펄스(scale punch). BeatBus 가 자동 트리거.
 */
data class TextAnimation(
    val enter: AnimationKind = AnimationKind.NONE,
    val exit: AnimationKind = AnimationKind.NONE,
    val durationMs: Long = 320L,
    val easing: Easing = Easing.SPRING,
    val perChar: Boolean = false,
    val cascadeStaggerMs: Long = 35L,
    val beatSync: Boolean = false,
) {
    companion object {
        val NONE = TextAnimation(enter = AnimationKind.NONE, exit = AnimationKind.NONE)
    }
}

enum class AnimationKind {
    NONE,
    POP,           // 0.6 → 1.05 → 1.0 (Spring)
    BOUNCE,        // 위에서 아래로 튕기듯 진입
    SLIDE_UP,
    SLIDE_DOWN,
    SLIDE_LEFT,
    SLIDE_RIGHT,
    FADE,
    TYPEWRITER,    // 글자 단위 한 문자씩 나타남 (perChar 와 결합 권장)
    WORD_CASCADE,  // 단어 단위 cascade (perChar 무시, 단어 경계 사용)
    ZOOM_IN,
    ZOOM_OUT,
    GLITCH,        // 짧은 RGB split + jitter
}
