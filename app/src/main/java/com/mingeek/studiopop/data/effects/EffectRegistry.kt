package com.mingeek.studiopop.data.effects

import java.util.concurrent.ConcurrentHashMap

/**
 * 모든 효과 정의의 단일 등록소. 카테고리별·id별 조회를 제공하며,
 * 동일 id 재등록은 런타임 오류(개발자 실수 방지). 4 출력 파이프라인이 이 레지스트리를
 * 공유하므로, 새 효과 추가는 [register] 한 번이면 끝.
 *
 * Thread-safe: 스레드 간 공유되는 단일 인스턴스를 가정.
 *
 * R1 단계에선 비어있다 — R2+ 에서 builtin 효과들이 [registerBuiltins] 호출로 일괄 등록된다.
 */
class EffectRegistry {

    private val byId = ConcurrentHashMap<String, EffectDefinition>()

    fun register(definition: EffectDefinition) {
        val previous = byId.putIfAbsent(definition.id, definition)
        require(previous == null) {
            "Effect id '${definition.id}' is already registered (duplicate detected)"
        }
    }

    fun registerAll(definitions: Iterable<EffectDefinition>) {
        for (d in definitions) register(d)
    }

    fun unregister(id: String) {
        byId.remove(id)
    }

    fun get(id: String): EffectDefinition? = byId[id]

    fun requireById(id: String): EffectDefinition =
        byId[id] ?: error("Unknown effect id '$id'")

    fun all(): List<EffectDefinition> = byId.values.sortedBy { it.id }

    fun byCategory(category: EffectCategory): List<EffectDefinition> =
        byId.values.filter { it.category == category }.sortedBy { it.id }

    fun isEmpty(): Boolean = byId.isEmpty()

    fun size(): Int = byId.size
}

/**
 * builtin 효과들의 일괄 등록 진입점. R2 부터 효과 정의 리스트들을 추가.
 * 호출 측은 보통 앱 시작 시점 (AppContainer) 에서 한 번 호출.
 */
fun EffectRegistry.registerBuiltins() {
    // R1: 등록할 builtin 없음. 골격만 깔린 상태.
    // R2 자막 스타일 팩, 전환 8종 등이 여기로 들어옴.
}
