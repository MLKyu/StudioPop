package com.mingeek.studiopop.data.effects

import java.util.UUID

/**
 * 타임라인에 실제로 배치된 효과 한 개. 정의(id) + 파라미터 값 + 적용 시간 범위를 가진다.
 * 같은 정의를 여러 번 배치할 수 있고(예: 두 컷 사이에 둘 다 Zoom Punch), 인스턴스의
 * id 로 Timeline 안에서 식별된다.
 *
 * 시각은 다른 TimedLayer 들과 같은 규칙: **원본 영상(source) 기준** ms.
 * 인스턴스가 점(point) 효과(예: 전환)면 start/end 가 같을 수 있고, 이 경우 적용 폭은
 * 효과 정의 자체의 파라미터(예: TransitionKind.halfDurationMs) 가 결정.
 */
data class EffectInstance(
    val id: String = UUID.randomUUID().toString(),
    val definitionId: String,
    val params: EffectParamValues = EffectParamValues(),
    val sourceStartMs: Long,
    val sourceEndMs: Long = sourceStartMs,
    val enabled: Boolean = true,
) {
    val isPoint: Boolean get() = sourceEndMs == sourceStartMs
}

/**
 * 한 RenderPlan 이 들고 다니는 효과 모음. 카테고리별로 나뉘어 있어 4 렌더러가
 * 자기에게 의미 있는 카테고리만 빠르게 골라 적용할 수 있다.
 *
 * R1 골격 단계에선 빈 컬렉션이 기본 — Timeline 의 기존 transitions/captions 등은
 * 그대로 두고, 새 효과들이 R2 부터 여기로 모인다.
 */
data class EffectStack(
    val instances: List<EffectInstance> = emptyList(),
) {
    fun byCategory(registry: EffectRegistry, category: EffectCategory): List<EffectInstance> =
        instances.filter { inst ->
            val def = registry.get(inst.definitionId) ?: return@filter false
            def.category == category && inst.enabled
        }

    fun activeAt(
        registry: EffectRegistry,
        timeMs: Long,
        category: EffectCategory,
    ): List<EffectInstance> = byCategory(registry, category).filter { inst ->
        if (inst.isPoint) inst.sourceStartMs == timeMs
        else timeMs in inst.sourceStartMs..inst.sourceEndMs
    }

    fun add(instance: EffectInstance): EffectStack = copy(instances = instances + instance)

    fun remove(id: String): EffectStack = copy(instances = instances.filter { it.id != id })

    fun update(instance: EffectInstance): EffectStack =
        copy(instances = instances.map { if (it.id == instance.id) instance else it })

    companion object {
        val EMPTY = EffectStack()
    }
}
