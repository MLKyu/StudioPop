package com.mingeek.studiopop.data.text

import com.mingeek.studiopop.data.design.DesignTokens
import com.mingeek.studiopop.data.editor.TimelineCaption
import com.mingeek.studiopop.data.effects.EffectParamValues
import com.mingeek.studiopop.data.effects.EffectRegistry
import com.mingeek.studiopop.data.effects.builtins.CaptionStylePresets
import com.mingeek.studiopop.data.keyframe.Easing

/**
 * 자막 + 효과 매핑 + 디자인 토큰을 받아 화면에 그릴 [RichTextElement] 리스트로 변환.
 *
 * R3.5 단계의 핵심 헬퍼: ViewModel 의 timeline + captionEffectIds 를 EditorScreen 의
 * RichTextOverlay 가 받는 형태로 변환한다. 이 변환을 데이터 레이어에 둬서 ViewModel·UI 가
 * 같은 로직을 공유 (테스트도 가능).
 *
 * 분기:
 *  - effectIds 에 매핑이 있는 자막만 결과에 포함 — 효과 미적용 자막은 기존
 *    PreviewCaptionOverlay 가 그대로 렌더하므로 여기서 빼야 중복 표시 방지.
 *  - 매핑된 effectId 가 알 수 없는 효과면 무시 (defensive).
 */
object CaptionEffectResolver {

    fun resolveEffectiveCaptions(
        captions: List<TimelineCaption>,
        captionEffectIds: Map<String, String>,
        effectRegistry: EffectRegistry,
        designTokens: DesignTokens,
        themeId: String,
        captionBeatSyncIds: Set<String> = emptySet(),
        captionKaraokeIds: Set<String> = emptySet(),
    ): List<RichTextElement> {
        if (captions.isEmpty() || captionEffectIds.isEmpty()) return emptyList()
        val theme = designTokens.theme(themeId)
        val palette = theme.palette
        val subtitleFontPackId = theme.subtitleFontPackId

        return captions.mapNotNull { c ->
            val effectId = captionEffectIds[c.id] ?: return@mapNotNull null
            val def = effectRegistry.get(effectId) ?: return@mapNotNull null
            val style = CaptionStylePresets.buildStyle(
                id = def.id,
                params = def.defaultParams.takeIf { it.values.isNotEmpty() }
                    ?: EffectParamValues(),
                palette = palette,
                subtitleFontPackId = subtitleFontPackId,
            )
            val beatSync = c.id in captionBeatSyncIds
            val karaokeOn = c.id in captionKaraokeIds
            val timing: TextTiming = if (karaokeOn) {
                TextTiming.FromWordTimestamps(
                    sourceStartMs = c.sourceStartMs,
                    sourceEndMs = c.sourceEndMs,
                    words = fakeWordTimings(c.text, c.sourceStartMs, c.sourceEndMs),
                )
            } else {
                TextTiming.Manual(c.sourceStartMs, c.sourceEndMs)
            }
            RichTextElement(
                id = c.id,
                content = c.text,
                style = style,
                animation = if (beatSync) TextAnimation(
                    enter = AnimationKind.NONE,
                    exit = AnimationKind.NONE,
                    easing = Easing.BEAT_BOUNCE,
                    beatSync = true,
                ) else TextAnimation.NONE,
                timing = timing,
                position = TextPosition.Static(c.style.anchorX, c.style.anchorY),
            )
        }
    }

    /**
     * 자막 텍스트를 공백으로 분할해 시간을 균등 분배. STT word 정보가 보존되지 않은 자막에서도
     * 카라오케 동작을 즉시 미리볼 수 있게 하는 R5c3a 임시 구현. 정확도는 발화 속도에 따라 어긋날
     * 수 있고, 실 word timestamp(STT 결과 직결) 가 들어올 때 자동으로 교체된다.
     */
    fun fakeWordTimings(text: String, startMs: Long, endMs: Long): List<WordTiming> {
        val words = text.split(Regex("\\s+")).filter { it.isNotBlank() }
        if (words.isEmpty()) return emptyList()
        val durMs = (endMs - startMs).coerceAtLeast(1L)
        val perWordMs = durMs / words.size
        var t = startMs
        return words.mapIndexed { i, w ->
            val wStart = t
            val wEnd = if (i == words.lastIndex) endMs else t + perWordMs
            t = wEnd
            WordTiming(word = w, startMs = wStart, endMs = wEnd)
        }
    }
}
