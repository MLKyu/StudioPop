package com.mingeek.studiopop.data.effects.builtins

import com.mingeek.studiopop.data.effects.EffectCategory
import com.mingeek.studiopop.data.effects.EffectDefinition
import com.mingeek.studiopop.data.effects.EffectParameter
import com.mingeek.studiopop.data.effects.EffectRegistry

/**
 * 숏츠/풀영상 첫 1~3초(인트로) 와 마지막 2~5초(아웃트로) 에 들어가는 정형 템플릿 효과 5종.
 *
 * 각 항목은 카테고리만 SHORTS_PIECE 로 같고, 시간 범위 권장값은 [EffectParameter.IntRange] 의
 * default 가 안내한다. 실제 렌더는 R5c 에서 RichTextElement + ImageLayer 합성으로 구현.
 *
 * 새 인트로/아웃트로 추가 = 정의 한 개 + (선택) 별도 builder helper. 효과 시스템 확장의 모범 사례:
 *  - 텍스트 한 줄 + 배경 박스: BUILT-IN HOOK_TITLE / SUBSCRIBE_PROMPT
 *  - 풀스크린 카운트다운: COUNTDOWN_3
 *  - 다음 영상 카드 + 채널 핸들: NEXT_EPISODE_CARD
 *  - 단순 디졸브-인 인트로: SIMPLE_TITLE
 */
object IntroOutroPresets {

    const val HOOK_TITLE = "shorts.intro.hook_title"
    const val SIMPLE_TITLE = "shorts.intro.simple_title"
    const val COUNTDOWN_3 = "shorts.intro.countdown_3"
    const val SUBSCRIBE_PROMPT = "shorts.outro.subscribe_prompt"
    const val NEXT_EPISODE_CARD = "shorts.outro.next_episode"

    private val durationParam = EffectParameter.IntRange(
        key = "durationMs", label = "지속 시간(ms)",
        min = 500, max = 6000, default = 2000,
    )

    private val titleTextParam = EffectParameter.Choice(
        key = "titleSize", label = "제목 크기",
        options = listOf(
            EffectParameter.Choice.Option("S", "작게"),
            EffectParameter.Choice.Option("M", "중간"),
            EffectParameter.Choice.Option("L", "크게"),
        ),
        defaultId = "L",
    )

    private val accentColorParam = EffectParameter.Color(
        key = "accentColor", label = "강조 색",
        default = 0xFFA6FF00.toInt(),
    )

    val DEFINITIONS: List<EffectDefinition> = listOf(
        EffectDefinition(
            id = HOOK_TITLE, displayName = "후킹 타이틀 (인트로)",
            category = EffectCategory.SHORTS_PIECE,
            parameters = listOf(durationParam, titleTextParam, accentColorParam),
            previewHint = EffectDefinition.PreviewHint(
                emoji = "🎬", tagLine = "첫 1초 강력한 한 줄로 시청자 멈추기",
                accentColor = 0xFFA6FF00.toInt(),
            ),
        ),
        EffectDefinition(
            id = SIMPLE_TITLE, displayName = "심플 타이틀 (인트로)",
            category = EffectCategory.SHORTS_PIECE,
            parameters = listOf(durationParam, titleTextParam),
            previewHint = EffectDefinition.PreviewHint(
                emoji = "📝", tagLine = "디졸브-인 타이틀 — 깔끔한 시작",
            ),
        ),
        EffectDefinition(
            id = COUNTDOWN_3, displayName = "3·2·1 카운트다운 (인트로)",
            category = EffectCategory.SHORTS_PIECE,
            parameters = listOf(accentColorParam),
            previewHint = EffectDefinition.PreviewHint(
                emoji = "⏱️", tagLine = "긴장감 폭발 — 뽑기·리액션 시작용",
            ),
        ),
        EffectDefinition(
            id = SUBSCRIBE_PROMPT, displayName = "구독 유도 (아웃트로)",
            category = EffectCategory.SHORTS_PIECE,
            parameters = listOf(durationParam, accentColorParam),
            previewHint = EffectDefinition.PreviewHint(
                emoji = "🔔", tagLine = "마지막 3~5초에 구독·알림 강조",
            ),
        ),
        EffectDefinition(
            id = NEXT_EPISODE_CARD, displayName = "다음 영상 카드 (아웃트로)",
            category = EffectCategory.SHORTS_PIECE,
            parameters = listOf(durationParam),
            previewHint = EffectDefinition.PreviewHint(
                emoji = "➡️", tagLine = "끝에 다음 영상 미리보기 카드",
            ),
        ),
    )
}

/** 일괄 등록 진입점. */
fun EffectRegistry.registerIntroOutroPresets() {
    registerAll(IntroOutroPresets.DEFINITIONS)
}
