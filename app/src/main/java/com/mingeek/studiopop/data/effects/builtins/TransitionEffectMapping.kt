package com.mingeek.studiopop.data.effects.builtins

import com.mingeek.studiopop.data.editor.TransitionKind

/**
 * 기존 [TransitionKind] enum (Timeline.transitions 가 의존) 과 새 EffectRegistry 의 정의 id
 * 를 잇는 양방향 매핑. 기존 데이터 모델은 그대로 두고, 효과 시스템 쪽 호출에서 변환만.
 *
 * R3 렌더러는 EffectInstance 가 들어오면 이 매핑을 거쳐 기존 FadeOverlay 또는 Media3 effect
 * 빌더를 부른다 (Fade/Dissolve), 또는 신규 8종은 새 렌더 경로로.
 */
object TransitionEffectMapping {

    fun toEffectId(kind: TransitionKind): String = when (kind) {
        TransitionKind.FADE_TO_BLACK -> TransitionPresets.FADE_TO_BLACK
        TransitionKind.DISSOLVE -> TransitionPresets.DISSOLVE
    }

    /**
     * 효과 id 를 받아 기존 enum 으로 매핑. 신규 8종은 enum 으로 표현 불가능 → null.
     * 호출 측은 null 이면 새 렌더 경로(R3) 로 분기.
     */
    fun toLegacyKind(effectId: String): TransitionKind? = when (effectId) {
        TransitionPresets.FADE_TO_BLACK -> TransitionKind.FADE_TO_BLACK
        TransitionPresets.DISSOLVE -> TransitionKind.DISSOLVE
        else -> null
    }
}
