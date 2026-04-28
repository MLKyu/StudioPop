package com.mingeek.studiopop.ui.text

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.tween
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.BiasAlignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.scale
import com.mingeek.studiopop.data.audio.BeatBus
import com.mingeek.studiopop.data.text.RichTextElement
import com.mingeek.studiopop.data.text.TextPosition
import com.mingeek.studiopop.data.text.TextTiming

/**
 * Preview 위에 [RichTextElement] 리스트를 시간 매칭해 렌더하는 오버레이.
 *
 * R4 확장: [beatBus] 를 받으면 [RichTextElement.animation.beatSync] = true 인 자막에 한해
 * 비트 시점에 scale punch 가 자동 적용된다. beatBus = null 이면 정적 렌더 (R3 동작 그대로).
 */
@Composable
fun RichTextOverlay(
    elements: List<RichTextElement>,
    currentSourceMs: Long,
    modifier: Modifier = Modifier,
    baseFontSizeSp: Float = 28f,
    beatBus: BeatBus? = null,
    typefaceProvider: ((fontPackId: String, weight: Int) -> android.graphics.Typeface)? = null,
) {
    val active = remember(elements, currentSourceMs) {
        elements.filter {
            it.enabled && currentSourceMs in it.sourceStartMs..it.sourceEndMs
        }
    }
    if (active.isEmpty()) return

    // 비트 펄스 — 단일 Animatable 가 모든 beatSync 요소를 동시에 펄스. 각 자막마다 개별 펄스가
    // 필요해지면 elementId → Animatable 맵으로 확장.
    val pulse = remember { Animatable(1f) }
    if (beatBus != null) {
        LaunchedEffect(beatBus) {
            beatBus.events.collect { event ->
                val intensityScale = 1f + 0.18f * event.intensity.coerceIn(0f, 1f)
                pulse.snapTo(intensityScale)
                pulse.animateTo(1f, animationSpec = tween(durationMillis = 220))
            }
        }
    }

    BoxWithConstraints(modifier = modifier.fillMaxSize()) {
        active.forEach { element ->
            val anchor = anchorFromPosition(element.position, currentSourceMs)
            val applyBeat = beatBus != null && element.animation.beatSync
            val scale = if (applyBeat) pulse.value else 1f
            // R5c3a: 카라오케 — element.timing 이 FromWordTimestamps 면 단어 데이터를 렌더러로 전달.
            val timing = element.timing
            val (words, currentMs) = if (timing is TextTiming.FromWordTimestamps) {
                timing.words to currentSourceMs
            } else {
                emptyList<com.mingeek.studiopop.data.text.WordTiming>() to null
            }
            RichTextRenderer(
                text = element.content,
                style = element.style,
                baseFontSizeSp = baseFontSizeSp,
                modifier = Modifier
                    .align(anchor)
                    .scale(scale),
                words = words,
                currentTimeMs = currentMs,
                typefaceProvider = typefaceProvider,
            )
        }
    }
}

/**
 * NDC 좌표를 Compose [BiasAlignment] 로 변환. NDC 의 y 는 위쪽이 +1 이지만 Compose 는
 * 위쪽이 -1 이라 부호 반전.
 *
 * Tracked 는 fallback 좌표를 그대로 사용 — frame 차원이 있는 호출 컨텍스트에서
 * [com.mingeek.studiopop.data.text.FaceAvoidanceResolver] 로 사전 해석한 결과를
 * [TextPosition.Static] 으로 넘기는 게 권장 패턴.
 */
private fun anchorFromPosition(pos: TextPosition, timeMs: Long): Alignment {
    val ndc = when (pos) {
        is TextPosition.Static -> pos.anchorX to pos.anchorY
        is TextPosition.Tracked -> pos.fallbackAnchorX to pos.fallbackAnchorY
        is TextPosition.Animated -> {
            val v = pos.track.sampleAt(timeMs) ?: pos.fallback
            v.x to v.y
        }
    }
    val ax = ndc.first.coerceIn(-1f, 1f)
    val ay = ndc.second.coerceIn(-1f, 1f)
    return BiasAlignment(horizontalBias = ax, verticalBias = -ay)
}
