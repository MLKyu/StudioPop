package com.mingeek.studiopop.data.caption

/**
 * 하나의 자막 큐.
 * @param startMs 시작 시각 (밀리초)
 * @param endMs 종료 시각 (밀리초)
 * @param words 단어 단위 시간 정보. STT 엔진(Vosk, whisper.cpp) 이 word-level 출력을 지원하면
 *              채워지고, 그렇지 않으면 null. 카라오케 자막·강조어 폭탄자막에서 활용한다.
 *              SRT 직렬화에는 포함되지 않으며 (W3C SRT 표준에 word timing 없음), in-memory
 *              에서만 살아남는다.
 */
data class Cue(
    val index: Int,
    val startMs: Long,
    val endMs: Long,
    val text: String,
    val words: List<CueWord>? = null,
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
        val text = textLines.joinToString("\n")

        return Cue(index = index, startMs = startMs, endMs = endMs, text = text)
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

    private val TIMESTAMP_REGEX = Regex("""(\d{1,2}):(\d{2}):(\d{2})[,.](\d{3})""")
    private val TIMING_REGEX =
        Regex("""(\d{1,2}:\d{2}:\d{2}[,.]\d{3})\s*-->\s*(\d{1,2}:\d{2}:\d{2}[,.]\d{3})""")
}
