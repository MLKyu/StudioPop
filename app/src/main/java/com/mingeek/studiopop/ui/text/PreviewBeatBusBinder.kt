package com.mingeek.studiopop.ui.text

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import com.mingeek.studiopop.data.audio.BeatBus
import com.mingeek.studiopop.data.audio.BeatData

/**
 * 재생 중 [currentSourceMs] 변화를 감지해 [BeatData.onsets] 의 다음 비트를 지나는 시점에
 * [BeatBus] 로 emit. UI 가 단일 진입점만 호출하면 자막 펄스 / 줌 펀치 / SFX 트리거 등 모든
 * 비트 동기 효과가 자동으로 활성화된다.
 *
 * 흐름:
 *  1. beats == null 이면 NOOP — 분석 미수행 또는 비트 없음.
 *  2. 매 컴포지션마다 currentSourceMs 변화 추적. 마지막으로 emit 한 onset index 보다 큰 인덱스
 *     중 currentSourceMs <= onset 이 처음 나오는 인덱스 직전까지 emit.
 *  3. 사용자가 빨리감기로 점프하면 한 번에 여러 비트가 지나 보이지만, UI 입장에선 짧은 시간에
 *     여러 emit 발생 — RichTextOverlay 의 펄스 애니메이션이 합쳐져 어색하지 않다.
 *
 * 비트 시점에서 약간 미리 (lookahead) emit 하면 자막 펄스가 비트와 정확히 맞물려 더 자연스러움.
 * 기본 30ms lookahead — 사용자 지각 한계보다 작아 어색함 없음.
 */
@Composable
fun PreviewBeatBusBinder(
    beats: BeatData?,
    currentSourceMs: Long,
    beatBus: BeatBus,
    lookaheadMs: Long = 30L,
) {
    if (beats == null || beats.onsets.isEmpty()) return

    // 마지막으로 emit 한 onset index 추적 — 같은 비트가 여러 번 발사되지 않게.
    val state = remember(beats) { LastEmittedHolder(-1) }

    LaunchedEffect(beats, currentSourceMs) {
        val effective = currentSourceMs + lookaheadMs
        val onsets = beats.onsets
        // 시간 점프(seek) 시 lastIndex 가 너무 멀면 리셋
        if (state.lastIndex >= 0) {
            val lastTime = onsets[state.lastIndex]
            if (effective < lastTime - 200L) state.lastIndex = -1
        }
        var i = state.lastIndex + 1
        while (i < onsets.size && onsets[i] <= effective) {
            beatBus.emit(
                BeatBus.BeatEvent(
                    sourceTimeMs = onsets[i],
                    beatIndex = i,
                    intensity = beats.confidence.coerceIn(0f, 1f),
                )
            )
            state.lastIndex = i
            i++
        }
    }
}

/** remember 안의 mutable 컨테이너. data class 였으면 매 update 가 재구성을 트리거. */
private class LastEmittedHolder(var lastIndex: Int)
