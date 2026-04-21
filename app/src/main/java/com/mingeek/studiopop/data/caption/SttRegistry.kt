package com.mingeek.studiopop.data.caption

/**
 * 사용 가능한 STT 엔진 레지스트리. UI 가 이걸로 라디오 옵션을 그림.
 */
class SttRegistry(
    private val engines: Map<SttEngine, SpeechToText>,
) {
    fun get(id: SttEngine): SpeechToText =
        engines[id] ?: error("등록되지 않은 엔진: $id")

    fun all(): List<SpeechToText> = SttEngine.entries.mapNotNull { engines[it] }
}
