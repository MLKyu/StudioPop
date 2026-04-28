package com.mingeek.studiopop.data.render

/**
 * RenderPlan 을 받아 한 출력을 만들어내는 렌더러의 공통 시그니처.
 *
 * 4 출력의 출력 타입(O) 은 다음과 같이 다르므로 [Renderer] 는 제네릭하다:
 * - 미리보기: 부수효과 (ExoPlayer 갱신) → O = Unit
 * - 내보내기: 결과 파일 경로 → O = File
 * - 썸네일: Bitmap
 * - 숏츠: 결과 파일 경로 → O = File
 *
 * 실제 4 구현은 R2~R3 에서 점진적으로 채움 — R1 단계에선 인터페이스만 자리 잡고, 기존
 * VideoEditor.export / ThumbnailComposer.compose 호출 경로는 영향받지 않는다.
 */
interface Renderer<O> {
    suspend fun render(plan: RenderPlan): Result<O>
}
