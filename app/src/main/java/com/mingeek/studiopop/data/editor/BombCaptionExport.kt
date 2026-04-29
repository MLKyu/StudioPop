package com.mingeek.studiopop.data.editor

import androidx.media3.common.util.UnstableApi
import androidx.media3.effect.TextureOverlay
import com.mingeek.studiopop.data.caption.CueWord

/**
 * R6: 강조어 폭탄자막의 export 측 변환. 미리보기 [com.mingeek.studiopop.ui.text.BombCaptionOverlay]
 * 와 같은 단어 선정 규칙([resolveBombInfo]) 을 공유하며, source-time bomb 시간창을 출력-time 으로
 * 매핑한 뒤 [AnimatedTextOverlay] (펄스 + jitter + 페이드 곡선) 로 만든다.
 *
 * 단어 선정:
 *  - STT word 가 있으면 가장 긴 단어(≥2자) → 그 단어의 [CueWord.startMs..endMs] 시간창
 *  - 없으면 큐 텍스트의 가장 긴 토큰 → 큐 시작 + [BOMB_FALLBACK_OFFSET_MS] 부터
 *    [BOMB_FALLBACK_DURATION_MS] 동안
 *
 * cut 분할 대응: 한 폭탄 시간창이 여러 effective 세그먼트에 걸치면 각 윈도우마다 동일 텍스트 +
 * 동일 animator 의 [AnimatedSegment] 로 펼침. 폭탄은 짧아서 보통 한 윈도우 안.
 */
@UnstableApi
object BombCaptionExport {

    private const val BOMB_FALLBACK_OFFSET_MS = 80L
    private const val BOMB_FALLBACK_DURATION_MS = 600L

    /** 한 번 분석으로 선정된 폭탄 — source 시각 기준. 같은 데이터를 미리보기/export 가 공유. */
    data class BombInfo(
        val word: String,
        val startMs: Long,
        val endMs: Long,
    )

    /**
     * 한 [TimelineCaption] + 선택적 word timing → 폭탄 정보. 미리보기와 동일 규칙.
     */
    fun resolveBombInfo(caption: TimelineCaption, words: List<CueWord>?): BombInfo? {
        val tokens = caption.text.split(Regex("\\s+")).filter { it.isNotBlank() }
        if (tokens.isEmpty()) return null
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
        val longest = tokens.maxByOrNull { it.length } ?: return null
        val start = caption.sourceStartMs + BOMB_FALLBACK_OFFSET_MS
        val end = (start + BOMB_FALLBACK_DURATION_MS).coerceAtMost(caption.sourceEndMs)
        if (end <= start) return null
        return BombInfo(word = longest, startMs = start, endMs = end)
    }

    /**
     * timeline 의 [bombCaptionIds] 자막들 → export 용 [TextureOverlay] 리스트.
     * 텍스트별 한 [AnimatedTextOverlay] 단위 (한 화면에 동시 폭탄은 거의 없어 단순 전략).
     */
    fun build(
        timeline: Timeline,
        bombCaptionIds: Set<String>,
        captionWords: Map<String, List<CueWord>>,
    ): List<TextureOverlay> {
        if (bombCaptionIds.isEmpty()) return emptyList()
        val out = mutableListOf<TextureOverlay>()
        for (cap in timeline.captions) {
            if (cap.id !in bombCaptionIds) continue
            val bomb = resolveBombInfo(cap, captionWords[cap.id]) ?: continue
            val windows = timeline.rangeToOutputWindows(bomb.startMs, bomb.endMs)
            if (windows.isEmpty()) continue
            val segments = windows.map { w ->
                AnimatedSegment(
                    startMs = w.first,
                    endMs = w.last,
                    text = bomb.word,
                    animator = Animators::bombCaption,
                )
            }
            out += AnimatedTextOverlay(segments, STYLE_BOMB)
        }
        return out
    }

    /**
     * 폭탄자막 스타일 — 미리보기와 시각적으로 통일. GACHA 노란 굵은 + 화면 중앙 위쪽
     * (anchorY=0.4, BiasAlignment(verticalBias=-0.2) 와 부호 반대 — Media3 NDC 는 위로 가면 +Y).
     * sizeScale 2.4 — preview 56sp 와 비슷한 화면 점유 비율.
     */
    private val STYLE_BOMB = CaptionStyle(
        preset = CaptionPreset.GACHA,
        anchorX = 0f,
        anchorY = 0.4f,
        sizeScale = 2.4f,
    )
}
