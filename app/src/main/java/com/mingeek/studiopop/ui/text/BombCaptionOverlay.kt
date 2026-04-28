package com.mingeek.studiopop.ui.text

import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.offset
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.BiasAlignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.scale
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.material3.Text
import com.mingeek.studiopop.data.caption.CueWord
import com.mingeek.studiopop.data.editor.Timeline
import com.mingeek.studiopop.data.editor.TimelineCaption
import kotlin.math.PI
import kotlin.math.sin

/**
 * R6: 강조어 폭탄자막 오버레이.
 *
 * 활성 자막(현재 source 시각이 [TimelineCaption.sourceStartMs..sourceEndMs] 안) 중
 * [bombCaptionIds] 에 속한 것에 대해, 큐 안의 "가장 긴 단어" 를 한 번에 큰 글씨로 폭발시킨다.
 *
 * 단어 선정:
 *  - [captionWords] 에 STT word 가 있으면 그중 가장 긴 단어 (≥ 2자, 한 큐 1개) — 그 단어의
 *    [CueWord.startMs..endMs] 시간창에 발사.
 *  - 없으면 큐 텍스트의 가장 긴 토큰을 픽 — 큐 시작 + [BOMB_FALLBACK_OFFSET_MS] 부터
 *    [BOMB_FALLBACK_DURATION_MS] 동안 발사.
 *
 * 시각 효과: bomb 시간창의 정규화 진행도 t (0..1) 에 대해
 *  - scale: 0.6 → 1.2 (entry, t<0.25) → 1.0 (steady, 0.25<=t<0.7) → 1.0 → 0.0 (exit, t>=0.7)
 *  - x jitter: ±[BOMB_JITTER_DP] dp sin 진동 (steady 구간에서만)
 *  - 기본 위치는 화면 정중앙 (강조 정점). 큐 자체 anchor 와 겹치지 않게 의도적으로 위쪽.
 *
 * 한 화면에 동시에 여러 폭탄이 뜰 수 있으므로 큐별 배치 — 단, 같은 큐가 여러 번 폭탄을
 * 만들지는 않음 (큐당 1발).
 */
@Composable
fun BombCaptionOverlay(
    timeline: Timeline,
    currentSourceMs: Long,
    bombCaptionIds: Set<String>,
    captionWords: Map<String, List<CueWord>>,
    modifier: Modifier = Modifier,
) {
    val active = remember(timeline.captions, currentSourceMs, bombCaptionIds, captionWords) {
        timeline.captions
            .filter { it.id in bombCaptionIds }
            .filter { currentSourceMs in it.sourceStartMs..it.sourceEndMs }
            .mapNotNull { cap -> resolveBomb(cap, captionWords[cap.id]) }
            .filter { currentSourceMs in it.startMs..it.endMs }
    }
    if (active.isEmpty()) return

    BoxWithConstraints(modifier = modifier.fillMaxSize()) {
        active.forEach { bomb ->
            val span = (bomb.endMs - bomb.startMs).coerceAtLeast(1L)
            val t = ((currentSourceMs - bomb.startMs).toFloat() / span).coerceIn(0f, 1f)

            val scale = when {
                t < 0.25f -> lerp(0.6f, 1.2f, t / 0.25f)
                t < 0.7f -> 1.0f
                else -> lerp(1.0f, 0f, (t - 0.7f) / 0.3f)
            }
            // sin 한 주기 = jitter 한 번 흔들기. steady 구간(0.25..0.7) 에서만 흔들고 끝.
            val jitterDp = if (t in 0.25f..0.7f) {
                val phase = ((t - 0.25f) / 0.45f) * (2f * PI.toFloat())
                BOMB_JITTER_DP * sin(phase)
            } else 0f

            // 화면 중앙 위쪽(verticalBias = -0.2) — 큐 본체와 겹치지 않게.
            Text(
                text = bomb.word,
                color = Color(0xFFFFEB3B),
                fontSize = BOMB_FONT_SIZE_SP.sp,
                fontWeight = FontWeight.Black,
                textAlign = TextAlign.Center,
                modifier = Modifier
                    .align(BiasAlignment(horizontalBias = 0f, verticalBias = -0.2f))
                    .offset(x = jitterDp.dp)
                    .scale(scale),
            )
        }
    }
}

/** [TimelineCaption] 한 개 + 선택적 word timing 으로 폭탄 발사 정보를 결정. */
internal fun resolveBomb(caption: TimelineCaption, words: List<CueWord>?): BombInfo? {
    val tokens = caption.text.split(Regex("\\s+")).filter { it.isNotBlank() }
    if (tokens.isEmpty()) return null

    // STT word 가 있으면 가장 긴 단어 픽, [startMs..endMs] 그대로 사용.
    if (!words.isNullOrEmpty()) {
        val pick = words
            .filter { it.word.isNotBlank() && it.word.length >= 2 }
            .maxByOrNull { it.word.length }
        if (pick != null) {
            return BombInfo(
                word = pick.word,
                startMs = pick.startMs,
                endMs = pick.endMs.coerceAtLeast(pick.startMs + 1L),
            )
        }
    }

    // fallback: 큐 텍스트의 가장 긴 토큰을 큐 시작 직후에 폭발.
    val longest = tokens.maxByOrNull { it.length } ?: return null
    val start = caption.sourceStartMs + BOMB_FALLBACK_OFFSET_MS
    val end = (start + BOMB_FALLBACK_DURATION_MS).coerceAtMost(caption.sourceEndMs)
    if (end <= start) return null
    return BombInfo(word = longest, startMs = start, endMs = end)
}

internal data class BombInfo(
    val word: String,
    val startMs: Long,
    val endMs: Long,
)

private fun lerp(a: Float, b: Float, t: Float): Float = a + (b - a) * t

private const val BOMB_FONT_SIZE_SP = 56f
private const val BOMB_JITTER_DP = 6f
private const val BOMB_FALLBACK_OFFSET_MS = 80L
private const val BOMB_FALLBACK_DURATION_MS = 600L
