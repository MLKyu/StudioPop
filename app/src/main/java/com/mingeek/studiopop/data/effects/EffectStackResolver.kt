package com.mingeek.studiopop.data.effects

/**
 * R6: 출력 렌더 직전 [EffectStack] 에서 export 파이프라인이 즉시 소비할 수 있는 형태의 정보를
 * 뽑아내는 헬퍼.
 *
 * 두 진입점:
 *  - [resolveActiveLutId] — 단일 LUT (composition-level 적용용)
 *  - [resolvePerSegmentLuts] — effective 세그먼트별 LUT (시간별 다른 LUT 라우팅용)
 *
 * VIDEO_FX (Ken Burns / Zoom Punch) 는 별도 [com.mingeek.studiopop.data.editor.EffectStackVideoEffects],
 * SHORTS_PIECE 는 [com.mingeek.studiopop.data.editor.EffectStackShortsOverlays] 가 처리.
 */
object EffectStackResolver {

    /**
     * effectStack 에 들어 있는 LUT 효과 인스턴스 중 "지금 영상에 적용할" id 를 결정.
     *
     * 매칭 규칙:
     *  - `params.lutId` 가 있으면 그 값 (사용자/AI 가 명시한 정확한 id)
     *  - 없으면 `definitionId` 가 "lut." 접두사면 그것을 lut id 로 간주
     *
     * 여러 LUT 이 동시에 있으면 시간 범위가 가장 넓은 것 우선 — 보통 영상 전체에 걸친 LUT 이
     * 작은 부분 강조 LUT 보다 의도가 강한 base 톤.
     *
     * Media3 [androidx.media3.effect.SingleColorLut] 은 단일 적용이라 중복 LUT 은 표현 불가 —
     * 시간별 다른 LUT 은 후속 라운드의 EditedMediaItem 분할 라우팅 필요.
     */
    fun resolveActiveLutId(stack: EffectStack): String? {
        if (stack.instances.isEmpty()) return null
        val candidates = lutCandidates(stack)
        if (candidates.isEmpty()) return null
        val (_, lutId) = candidates.maxByOrNull { (inst, _) ->
            inst.sourceEndMs - inst.sourceStartMs
        } ?: return null
        return lutId
    }

    /**
     * effective 세그먼트별 LUT id 결정 — 한 세그먼트의 source 범위와 가장 많이 겹치는 LUT 인스턴스
     * 의 id 를 부여. 매칭되는 인스턴스가 없으면 [fallbackLutId] (보통 selectedThemeId 의 theme.lutId).
     *
     * 반환 리스트의 길이 == timeline.effectiveSegments().size, 순서도 동일. VideoEditor 가 인덱스로
     * 매핑해 EditedMediaItem 단위로 SingleColorLut Effect 를 부여한다.
     *
     * 예: 0~5s 는 lut.cinematic, 5~10s 는 lut.vivid 인 effectStack + 두 effective 세그먼트(0~5, 5~10)
     * → 결과 ["lut.cinematic", "lut.vivid"]. 시간별 다른 톤이 export 영상에 그대로 박힘.
     */
    fun resolvePerSegmentLuts(
        stack: EffectStack,
        timeline: com.mingeek.studiopop.data.editor.Timeline,
        fallbackLutId: String? = null,
    ): List<String?> {
        val ranges = timeline.effectiveSegments().map { it.sourceStartMs to it.sourceEndMs }
        return resolvePerSegmentLutsForRanges(stack, ranges, fallbackLutId)
    }

    /**
     * pure-data 변종 — Timeline 의존 없이 source-time 범위 리스트만으로 LUT 라우팅. 단위 테스트
     * (Android stub Uri 안 거치고 검증) 와, 미래에 Timeline 외 출처(예: 프리뷰 가상 segments)에서
     * 같은 라우팅 로직을 재사용할 때 진입점.
     */
    internal fun resolvePerSegmentLutsForRanges(
        stack: EffectStack,
        segmentRanges: List<Pair<Long, Long>>,
        fallbackLutId: String? = null,
    ): List<String?> {
        if (segmentRanges.isEmpty()) return emptyList()
        val candidates = lutCandidates(stack)
        if (candidates.isEmpty()) return List(segmentRanges.size) { fallbackLutId }
        return segmentRanges.map { (segStart, segEnd) ->
            val best = candidates.maxByOrNull { (inst, _) ->
                val ovStart = maxOf(inst.sourceStartMs, segStart)
                val ovEnd = minOf(inst.sourceEndMs, segEnd)
                (ovEnd - ovStart).coerceAtLeast(0L)
            }
            if (best != null) {
                val (inst, id) = best
                val overlap = minOf(inst.sourceEndMs, segEnd) -
                    maxOf(inst.sourceStartMs, segStart)
                if (overlap > 0L) id else fallbackLutId
            } else fallbackLutId
        }
    }

    private fun lutCandidates(stack: EffectStack): List<Pair<EffectInstance, String>> =
        stack.instances
            .filter { it.enabled }
            .mapNotNull { inst ->
                val explicit = inst.params.assetId("lutId")
                val implicit = inst.definitionId.takeIf { it.startsWith("lut.") }
                val id = explicit ?: implicit ?: return@mapNotNull null
                inst to id
            }
}
