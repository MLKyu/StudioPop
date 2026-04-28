package com.mingeek.studiopop.data.effects

/**
 * R6: 출력 렌더 직전 [EffectStack] 에서 export 파이프라인이 즉시 소비할 수 있는 형태의 정보를
 * 뽑아내는 헬퍼.
 *
 * 현재 1차 통합 범위는 LUT — VIDEO_FX(Ken Burns / Zoom Punch / Speed Ramp) 는 시간 가변
 * GlEffect/GlShaderProgram 이 필요해 별도 라운드. SHORTS_PIECE 도 동일 (정형 텍스트 자산
 * 모듈이 미정).
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
        val candidates = stack.instances
            .filter { it.enabled }
            .mapNotNull { inst ->
                val explicit = inst.params.assetId("lutId")
                val implicit = inst.definitionId.takeIf { it.startsWith("lut.") }
                val id = explicit ?: implicit ?: return@mapNotNull null
                inst to id
            }
        if (candidates.isEmpty()) return null
        // 가장 긴 시간 범위 우선 — point 효과는 0 길이라 자연히 후순위.
        val (_, lutId) = candidates.maxByOrNull { (inst, _) ->
            inst.sourceEndMs - inst.sourceStartMs
        } ?: return null
        return lutId
    }
}
