package com.mingeek.studiopop.data.effects.builtins

import com.mingeek.studiopop.data.effects.EffectCategory
import com.mingeek.studiopop.data.effects.EffectDefinition
import com.mingeek.studiopop.data.effects.EffectParameter
import com.mingeek.studiopop.data.effects.EffectRegistry

/**
 * 세그먼트 사이 전환 효과 10종(기존 2 + 신규 8). R2 단계에선 등록만 — 실제 렌더링은 R3 에서
 * 기존 [com.mingeek.studiopop.data.editor.FadeOverlay] 와 Media3 Effect 조합으로 추가.
 *
 * 등록 자체로도 의미가 있는 이유: UI 효과 패널이 즉시 10종을 보여주고 사용자가 선택 가능.
 * 미렌더 효과는 카드에 "곧 출시" 표식을 붙여 사용자에게 미리 노출하는 패턴.
 *
 * 기존 [com.mingeek.studiopop.data.editor.TransitionKind] enum 의 FADE_TO_BLACK / DISSOLVE
 * 는 그대로 두며, 여기 등록되는 정의의 id 는 한 단계 매핑된다 (TransitionEffectMapping).
 */
object TransitionPresets {

    // 기존 호환 (Timeline.transitions 와 매핑되는 두 항목).
    const val FADE_TO_BLACK = "transition.fade_to_black"
    const val DISSOLVE = "transition.dissolve"

    // 신규 8종.
    const val WIPE_HORIZONTAL = "transition.wipe_horizontal"
    const val WIPE_VERTICAL = "transition.wipe_vertical"
    const val ZOOM_PUNCH = "transition.zoom_punch"
    const val GLITCH = "transition.glitch"
    const val WHIP_PAN = "transition.whip_pan"
    const val RGB_SPLIT = "transition.rgb_split"
    const val SLIDE = "transition.slide"
    const val FLASH_WHITE = "transition.flash_white"

    private val durationParam = EffectParameter.IntRange(
        key = "durationMs",
        label = "지속 시간(ms)",
        min = 80,
        max = 1200,
        default = 320,
    )

    private val intensityParam = EffectParameter.FloatRange(
        key = "intensity",
        label = "강도",
        min = 0f,
        max = 1.5f,
        default = 1f,
    )

    val DEFINITIONS: List<EffectDefinition> = listOf(
        EffectDefinition(
            id = FADE_TO_BLACK, displayName = "검정 페이드",
            category = EffectCategory.TRANSITION,
            parameters = listOf(durationParam),
            previewHint = EffectDefinition.PreviewHint(emoji = "⬛", tagLine = "완전히 검정으로 빠짐"),
        ),
        EffectDefinition(
            id = DISSOLVE, displayName = "디졸브",
            category = EffectCategory.TRANSITION,
            parameters = listOf(durationParam),
            previewHint = EffectDefinition.PreviewHint(emoji = "🌫️", tagLine = "얕게 어두워지며 자연스럽게"),
        ),
        EffectDefinition(
            id = WIPE_HORIZONTAL, displayName = "와이프 (가로)",
            category = EffectCategory.TRANSITION,
            parameters = listOf(durationParam, intensityParam),
            previewHint = EffectDefinition.PreviewHint(emoji = "↔️", tagLine = "한쪽 방향으로 쓸어 넘김"),
        ),
        EffectDefinition(
            id = WIPE_VERTICAL, displayName = "와이프 (세로)",
            category = EffectCategory.TRANSITION,
            parameters = listOf(durationParam, intensityParam),
            previewHint = EffectDefinition.PreviewHint(emoji = "↕️", tagLine = "위·아래로 쓸어 넘김"),
        ),
        EffectDefinition(
            id = ZOOM_PUNCH, displayName = "줌 펀치",
            category = EffectCategory.TRANSITION,
            parameters = listOf(durationParam, intensityParam),
            previewHint = EffectDefinition.PreviewHint(emoji = "🥊", tagLine = "줌 인 후 다음 컷으로 확 들어감"),
        ),
        EffectDefinition(
            id = GLITCH, displayName = "글리치",
            category = EffectCategory.TRANSITION,
            parameters = listOf(durationParam, intensityParam),
            previewHint = EffectDefinition.PreviewHint(emoji = "📺", tagLine = "RGB split + jitter"),
        ),
        EffectDefinition(
            id = WHIP_PAN, displayName = "휘프 팬",
            category = EffectCategory.TRANSITION,
            parameters = listOf(durationParam, intensityParam),
            previewHint = EffectDefinition.PreviewHint(emoji = "💨", tagLine = "빠르게 휘둘리며 전환"),
        ),
        EffectDefinition(
            id = RGB_SPLIT, displayName = "RGB 분리",
            category = EffectCategory.TRANSITION,
            parameters = listOf(durationParam, intensityParam),
            previewHint = EffectDefinition.PreviewHint(emoji = "🎨", tagLine = "RGB 채널 분리 후 합쳐짐"),
        ),
        EffectDefinition(
            id = SLIDE, displayName = "슬라이드",
            category = EffectCategory.TRANSITION,
            parameters = listOf(durationParam),
            previewHint = EffectDefinition.PreviewHint(emoji = "➡️", tagLine = "다음 컷이 밀고 들어옴"),
        ),
        EffectDefinition(
            id = FLASH_WHITE, displayName = "화이트 플래시",
            category = EffectCategory.TRANSITION,
            parameters = listOf(durationParam, intensityParam),
            previewHint = EffectDefinition.PreviewHint(emoji = "⚡", tagLine = "흰색 플래시로 임팩트"),
        ),
    )
}

/** 일괄 등록 진입점. */
fun EffectRegistry.registerTransitionPresets() {
    registerAll(TransitionPresets.DEFINITIONS)
}
