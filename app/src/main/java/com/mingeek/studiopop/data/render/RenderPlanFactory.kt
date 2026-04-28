package com.mingeek.studiopop.data.render

import com.mingeek.studiopop.data.design.DesignTokens
import com.mingeek.studiopop.data.design.ThemePack
import com.mingeek.studiopop.data.editor.Timeline
import com.mingeek.studiopop.data.text.RichTextAdapters
import com.mingeek.studiopop.data.text.RichTextElement

/**
 * 기존 [Timeline] 으로부터 새 시스템 입력 [RenderPlan] 을 만들어주는 빌더.
 *
 * 골격 단계의 효용: 기존 ViewModel/UseCase 가 Timeline 만 들고 있어도, 한 줄 호출로 새
 * 시스템 형식(RenderPlan) 을 얻을 수 있다 → R2 부터의 신규 효과/렌더러가 자연스럽게
 * 진입 가능. 어댑터를 통해 [Timeline.captions] / [textLayers] / [fixedTemplates] 가
 * [richTexts] 로 자동 통합된다.
 */
object RenderPlanFactory {

    fun fromLegacyTimeline(
        timeline: Timeline,
        designTokens: DesignTokens,
        themeId: String? = null,
    ): RenderPlan {
        val theme = themeId?.let { designTokens.theme(it) }
            ?: designTokens.theme("studiopop.default")
        return RenderPlan(
            legacyTimeline = timeline,
            theme = theme,
            richTexts = collectRichTexts(timeline, theme),
        )
    }

    private fun collectRichTexts(timeline: Timeline, theme: ThemePack): List<RichTextElement> {
        val list = mutableListOf<RichTextElement>()
        timeline.captions.forEach { list += RichTextAdapters.fromCaption(it) }
        timeline.textLayers.forEach { list += RichTextAdapters.fromTextLayer(it) }
        // FixedTextTemplate 은 영상 전체에 표시 → 출력 전체 길이로 변환
        val fullEnd = timeline.outputDurationMs
        timeline.fixedTemplates.forEach { tmpl ->
            list += RichTextAdapters.fromFixedTemplate(
                tmpl,
                sourceStartMs = 0L,
                sourceEndMs = fullEnd,
            )
        }
        return list
    }
}
