package com.mingeek.studiopop.data.render

import com.mingeek.studiopop.data.audio.AudioAnalysis
import com.mingeek.studiopop.data.design.ThemePack
import com.mingeek.studiopop.data.editor.Timeline
import com.mingeek.studiopop.data.effects.EffectStack
import com.mingeek.studiopop.data.text.RichTextElement

/**
 * 4 렌더러(미리보기·내보내기·썸네일·숏츠) 가 공통으로 받는 단일 입력. 한 RenderPlan 으로
 * 4가지 출력이 결정되므로 새 효과를 추가하면 4곳에 일관되게 반영된다.
 *
 * R1 골격 단계의 핵심 가치: 기존 [Timeline] 을 그대로 들고, 추가 정보(EffectStack, theme,
 * audioAnalysis, richTexts) 만 옆에 붙여 새 시스템 입력을 형성한다. 기존 렌더 경로
 * (VideoEditor.export, ThumbnailComposer.compose 등) 는 [legacyTimeline] 만 사용하면
 * 그대로 동작하고, 새 효과를 받는 호출자만 [effects] / [theme] / [richTexts] 를 읽는다.
 *
 * @property legacyTimeline 기존 Timeline (segments/captions/textLayers/imageLayers/...)
 * @property effects 새 EffectStack — R2 부터 채워짐
 * @property theme 적용된 ThemePack
 * @property audioAnalysis 영상의 비트/라우드니스/파형 분석. null 이면 분석 미수행.
 * @property richTexts 새 RichTextElement 리스트 — 어댑터로 변환된 기존 자막 + R2 부터의 신규.
 */
data class RenderPlan(
    val legacyTimeline: Timeline,
    val effects: EffectStack = EffectStack.EMPTY,
    val theme: ThemePack,
    val audioAnalysis: AudioAnalysis? = null,
    val richTexts: List<RichTextElement> = emptyList(),
)
