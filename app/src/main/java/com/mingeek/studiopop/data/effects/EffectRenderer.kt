package com.mingeek.studiopop.data.effects

/**
 * 효과를 출력 파이프라인에 적용하는 추상화. 4가지 렌더 컨텍스트가 동일 등록을 공유하지만,
 * 각자 자신이 처리할 수 있는 효과만 [supports] = true 로 응답한다 (예: Transition 은 정지 썸네일에선 무의미).
 *
 * 실제 작업은 [apply] — 컨텍스트 타입에 의존적인 객체(Canvas, Media3 Effect 빌더, Compose Modifier 등)를
 * [RenderContext] 안에 넣어 전달한다. R1 단계에선 인터페이스만 깔아두고, R2+ 에서 실제 렌더러들이 등록.
 */
interface EffectRenderer<C : EffectRenderer.RenderContext> {

    /** 이 렌더러가 어떤 정의 id 들을 처리할 수 있는지. */
    fun supports(definition: EffectDefinition): Boolean

    /** 컨텍스트에 효과를 적용. 파라미터는 정의의 [EffectDefinition.parameters] 와 일치한다고 가정. */
    suspend fun apply(
        context: C,
        definition: EffectDefinition,
        params: EffectParamValues,
    )

    /** 렌더 출력별 컨텍스트. 구현체는 자신의 출력에 필요한 객체를 들고 있는 컨텍스트 타입을 정의. */
    interface RenderContext {
        /** 현재 처리 중인 시각(원본 영상 ms). 정지 이미지면 단일 시점. */
        val currentTimeMs: Long
    }
}
