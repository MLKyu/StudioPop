package com.mingeek.studiopop.data.caption

/**
 * 하나의 자막 큐.
 * @param startMs 시작 시각 (밀리초)
 * @param endMs 종료 시각 (밀리초)
 * @param words 단어 단위 시간 정보. STT 엔진(Vosk, whisper.cpp) 이 word-level 출력을 지원하면
 *              채워지고, 그렇지 않으면 null. 카라오케 자막·강조어 폭탄자막에서 활용한다.
 *              SRT 직렬화에는 포함되지 않으며 (W3C SRT 표준에 word timing 없음), in-memory
 *              에서만 살아남는다.
 * @param speakerLabel 화자 라벨 ("A", "B", ...). Vosk SpeakerModel + [SpeakerClusterer] 가 채움.
 *                     null 이면 화자 분리 미실행/미지원. SRT write 는 "[A] 텍스트" prefix 로 보존.
 */
data class Cue(
    val index: Int,
    val startMs: Long,
    val endMs: Long,
    val text: String,
    val words: List<CueWord>? = null,
    val speakerLabel: String? = null,
)

/**
 * 한 단어의 시간 범위. ms 기준 (Cue 와 동일 단위) — 엔진별 raw 단위(Vosk: seconds Double,
 * whisper.cpp: ms Long) 는 변환 후 저장된다.
 *
 * @param confidence 0..1 사이 신뢰도. 엔진이 제공하지 않으면 1.0.
 */
data class CueWord(
    val word: String,
    val startMs: Long,
    val endMs: Long,
    val confidence: Float = 1f,
)

object Srt {

    /**
     * SRT 문자열을 Cue 리스트로 파싱.
     * 형식:
     * ```
     * 1
     * 00:00:01,000 --> 00:00:04,200
     * 첫 번째 자막
     *
     * 2
     * 00:00:05,000 --> 00:00:07,500
     * 두 번째 자막
     * ```
     */
    fun parse(srt: String): List<Cue> {
        val normalized = srt.replace("\r\n", "\n").replace("\r", "\n").trim()
        if (normalized.isEmpty()) return emptyList()

        return normalized
            .split(Regex("\n{2,}"))
            .mapNotNull { parseBlock(it) }
    }

    private fun parseBlock(block: String): Cue? {
        val lines = block.lines().filter { it.isNotBlank() }
        if (lines.size < 2) return null

        val (indexLine, timingLine, textLines) = when {
            TIMING_REGEX.containsMatchIn(lines[0]) -> Triple(null, lines[0], lines.drop(1))
            lines.size >= 2 && TIMING_REGEX.containsMatchIn(lines[1]) ->
                Triple(lines[0], lines[1], lines.drop(2))
            else -> return null
        }

        val match = TIMING_REGEX.find(timingLine) ?: return null
        val startMs = parseTimestamp(match.groupValues[1]) ?: return null
        val endMs = parseTimestamp(match.groupValues[2]) ?: return null
        val index = indexLine?.trim()?.toIntOrNull() ?: 0
        val rawText = textLines.joinToString("\n")
        // 화자 prefix "[A] 텍스트" 를 분리해 speakerLabel 로 보존, 텍스트는 깨끗하게.
        val (speaker, text) = SPEAKER_PREFIX_REGEX.find(rawText)?.let {
            it.groupValues[1] to rawText.substring(it.range.last + 1).trimStart()
        } ?: (null to rawText)

        return Cue(
            index = index,
            startMs = startMs,
            endMs = endMs,
            text = text,
            speakerLabel = speaker,
        )
    }

    private fun parseTimestamp(ts: String): Long? {
        val m = TIMESTAMP_REGEX.matchEntire(ts.trim()) ?: return null
        val h = m.groupValues[1].toLong()
        val min = m.groupValues[2].toLong()
        val sec = m.groupValues[3].toLong()
        val ms = m.groupValues[4].toLong()
        return ((h * 3600 + min * 60 + sec) * 1000) + ms
    }

    fun write(cues: List<Cue>): String = buildString {
        cues.forEachIndexed { idx, cue ->
            if (idx > 0) append("\n\n")
            append(idx + 1).append('\n')
            append(formatTimestamp(cue.startMs))
                .append(" --> ")
                .append(formatTimestamp(cue.endMs))
                .append('\n')
            // 화자 라벨이 있으면 "[A] " prefix — 비표준이지만 export/import round-trip 보존되고
            // 표준 SRT 플레이어에선 그냥 텍스트의 일부로 보임 (기능 깨짐 없음).
            val spk = cue.speakerLabel?.takeIf { it.isNotBlank() }
            if (spk != null) append('[').append(spk).append(']').append(' ')
            append(cue.text)
        }
        append('\n')
    }

    private fun formatTimestamp(ms: Long): String {
        val totalSec = ms / 1000
        val h = totalSec / 3600
        val m = (totalSec % 3600) / 60
        val s = totalSec % 60
        val millis = ms % 1000
        return "%02d:%02d:%02d,%03d".format(h, m, s, millis)
    }

    /** "[A] 텍스트" prefix 매칭 — 단일 영문자(A-Z), 대괄호 + 한 칸 공백. */
    private val SPEAKER_PREFIX_REGEX = Regex("""^\[([A-Z])\]\s?""")

    private val TIMESTAMP_REGEX = Regex("""(\d{1,2}):(\d{2}):(\d{2})[,.](\d{3})""")
    private val TIMING_REGEX =
        Regex("""(\d{1,2}:\d{2}:\d{2}[,.]\d{3})\s*-->\s*(\d{1,2}:\d{2}:\d{2}[,.]\d{3})""")
}
