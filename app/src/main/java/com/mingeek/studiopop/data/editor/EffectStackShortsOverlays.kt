package com.mingeek.studiopop.data.editor

import androidx.media3.common.util.UnstableApi
import androidx.media3.effect.TextureOverlay
import com.mingeek.studiopop.data.caption.Cue
import com.mingeek.studiopop.data.effects.EffectInstance
import com.mingeek.studiopop.data.effects.EffectStack
import com.mingeek.studiopop.data.effects.builtins.IntroOutroPresets

/**
 * R6: SHORTS_PIECE intro/outro EffectInstance → Media3 [TextureOverlay] 변환.
 *
 * EffectDefinition 자체엔 텍스트 콘텐츠가 없어 — 이 헬퍼가 default 한국어 카피를 부여한다.
 * 사용자가 EffectInstance.params 의 "text" 키로 커스텀 텍스트를 명시하면 그게 우선.
 *
 * 지원 효과:
 *  - HOOK_TITLE: 인트로 후킹 한 줄 — 화면 상단, 큰 노란 굵은 글씨
 *  - SUBSCRIBE_PROMPT: 아웃트로 구독 유도 — 화면 중앙, 큰 흰 굵은 글씨
 *  - SIMPLE_TITLE: 인트로 디졸브 타이틀 — 화면 가운데
 *  - NEXT_EPISODE_CARD: 아웃트로 다음 영상 카드 — 화면 하단
 *
 * 미지원: COUNTDOWN_3 (애니메이션 카운트다운 — 별도 BitmapOverlay 작업 필요)
 *
 * cut 분할 대응: 한 EffectInstance 의 source 범위가 여러 effective 세그먼트에 걸치면 윈도우당
 * 하나씩 Cue 가 만들어져 동일 [CaptionOverlay] 안에 묶여 들어감.
 */
@UnstableApi
object EffectStackShortsOverlays {

    private const val PARAM_TEXT = "text"

    fun build(stack: EffectStack, timeline: Timeline): List<TextureOverlay> {
        if (stack.instances.isEmpty()) return emptyList()
        val out = mutableListOf<TextureOverlay>()
        for (inst in stack.instances) {
            if (!inst.enabled) continue
            val text = inst.params.values[PARAM_TEXT] as? String
            val (resolvedText, style) = when (inst.definitionId) {
                IntroOutroPresets.HOOK_TITLE -> (text ?: DEFAULT_HOOK_TITLE) to STYLE_HOOK
                IntroOutroPresets.SUBSCRIBE_PROMPT -> (text ?: DEFAULT_SUBSCRIBE) to STYLE_SUBSCRIBE
                IntroOutroPresets.SIMPLE_TITLE -> (text ?: DEFAULT_SIMPLE_TITLE) to STYLE_SIMPLE
                IntroOutroPresets.NEXT_EPISODE_CARD -> (text ?: DEFAULT_NEXT_EPISODE) to STYLE_NEXT
                // COUNTDOWN_3 / 기타: 미지원
                else -> continue
            }
            val cues = inst.toCuesInOutputTime(timeline, resolvedText)
            if (cues.isNotEmpty()) {
                out += CaptionOverlay(cues, style)
            }
        }
        return out
    }

    private fun EffectInstance.toCuesInOutputTime(
        timeline: Timeline,
        text: String,
    ): List<Cue> {
        val windows = timeline.rangeToOutputWindows(sourceStartMs, sourceEndMs)
        return windows.mapIndexed { idx, w ->
            Cue(
                index = idx + 1,
                startMs = w.first,
                endMs = w.last,
                text = text,
            )
        }
    }

    // 화자 분리 SRT 와의 충돌 방지: 모두 단순 한국어 한 줄.
    private const val DEFAULT_HOOK_TITLE = "잠깐, 끝까지 봐요!"
    private const val DEFAULT_SUBSCRIBE = "구독 부탁해요 🔔"
    private const val DEFAULT_SIMPLE_TITLE = "▶ 시작"
    private const val DEFAULT_NEXT_EPISODE = "➡ 다음 영상 보기"

    // CaptionStyle 기반 — preset = SHORTS (큰 굵은 글씨 + 강한 외곽선) 채택. anchor/scale 만 차별.
    private val STYLE_HOOK = CaptionStyle(
        preset = CaptionPreset.GACHA,        // 노란 굵은 글씨 — 강한 후킹
        anchorY = 0.55f,                      // 상단 가까이
        anchorX = 0f,
        sizeScale = 1.5f,
    )
    private val STYLE_SUBSCRIBE = CaptionStyle(
        preset = CaptionPreset.SHORTS,        // 큰 흰 굵은 글씨
        anchorY = 0f,                         // 정중앙
        anchorX = 0f,
        sizeScale = 1.6f,
    )
    private val STYLE_SIMPLE = CaptionStyle(
        preset = CaptionPreset.MINIMAL,
        anchorY = 0f,
        anchorX = 0f,
        sizeScale = 1.4f,
    )
    private val STYLE_NEXT = CaptionStyle(
        preset = CaptionPreset.VLOG,
        anchorY = -0.6f,
        anchorX = 0f,
        sizeScale = 1.2f,
    )
}
