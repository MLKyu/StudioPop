package com.mingeek.studiopop.data.audio

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.asSharedFlow

/**
 * 비트 이벤트 publish/subscribe 채널. 재생 중 발생하는 비트 시점을
 * 구독자(자막 펄스, 줌 펀치, SFX 트리거, 자동 더킹)에게 동시에 알린다.
 *
 * 재생 엔진(ExoPlayer 등) 이 [emit] 호출 — currentPosition 이 다음 onset 을 지날 때마다.
 * 한 번 만들면 R5 의 4영역 모두 같은 흐름을 공유한다.
 *
 * R1 단계에선 emit 호출이 없는 상태로도 멤버를 구독 가능하게 두어 downstream 코드가
 * 안전하게 컴파일된다 — 비트 이벤트가 없을 뿐 동작은 한다.
 */
class BeatBus {

    private val flow = MutableSharedFlow<BeatEvent>(
        replay = 0,
        extraBufferCapacity = 16,
    )

    val events: Flow<BeatEvent> = flow.asSharedFlow()

    suspend fun emit(event: BeatEvent) {
        flow.emit(event)
    }

    /** 재생 시각 + 비트 인덱스 + 강도. 강도는 onset 의 신뢰도/스파이크 크기. */
    data class BeatEvent(
        val sourceTimeMs: Long,
        val beatIndex: Int,
        val intensity: Float = 1f,
    )
}
