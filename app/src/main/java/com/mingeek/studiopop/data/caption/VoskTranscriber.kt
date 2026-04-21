package com.mingeek.studiopop.data.caption

import android.net.Uri
import com.squareup.moshi.JsonClass
import com.squareup.moshi.Moshi
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.vosk.Model
import org.vosk.Recognizer

/**
 * Vosk 온디바이스 STT.
 *
 * 흐름:
 *  1. Korean 모델(50MB) 설치 확인 → 없으면 [VoskModelManager.ensureInstalled]
 *  2. PCM 16-bit mono @ 16kHz 로 디코드 ([PcmDecoder])
 *  3. Vosk Recognizer 에 청크 단위로 주입 → JSON 결과 누적
 *  4. word-level 결과를 Cue 리스트로 그룹핑
 */
class VoskTranscriber(
    private val pcmDecoder: PcmDecoder,
    private val modelManager: VoskModelManager,
    private val moshi: Moshi,
) : SpeechToText {

    override val id: SttEngine = SttEngine.VOSK_LOCAL

    override suspend fun isAvailable(): SpeechToText.Availability =
        if (modelManager.isReady()) SpeechToText.Availability.Ready
        else SpeechToText.Availability.NeedsSetup(
            reason = "Korean 모델(약 50MB) 다운로드 필요. 첫 실행 시 자동 진행.",
        )

    override suspend fun transcribe(
        videoUri: Uri,
        language: String?,
        onProgress: (SpeechToText.Progress) -> Unit,
    ): Result<List<Cue>> = withContext(Dispatchers.Default) {
        runCatching {
            // 1) 모델 보장
            onProgress(SpeechToText.Progress(0, 3, "모델 준비"))
            modelManager.ensureInstalled().getOrThrow()

            // 2) PCM 디코드
            onProgress(SpeechToText.Progress(1, 3, "오디오 디코드"))
            val pcm = pcmDecoder.decode(videoUri, SAMPLE_RATE)
            if (pcm.isEmpty()) error("디코드된 오디오가 비어있습니다")

            // 3) 인식
            onProgress(SpeechToText.Progress(2, 3, "Vosk 인식 중"))
            val model = Model(modelManager.modelPath())
            val recognizer = Recognizer(model, SAMPLE_RATE.toFloat())
            recognizer.setWords(true)

            val resultAdapter = moshi.adapter(VoskResult::class.java)
            val allWords = mutableListOf<VoskWord>()
            try {
                val chunkSize = 4_000 // 약 0.25초 단위
                var i = 0
                while (i < pcm.size) {
                    val len = minOf(chunkSize, pcm.size - i)
                    val chunk = pcm.copyOfRange(i, i + len)
                    val finalSeg = recognizer.acceptWaveForm(chunk, len)
                    if (finalSeg) {
                        resultAdapter.fromJson(recognizer.result)?.result?.let { allWords += it }
                    }
                    i += len
                    if (i % (chunkSize * 16) == 0) {
                        onProgress(
                            SpeechToText.Progress(
                                currentStep = 2,
                                totalSteps = 3,
                                phaseLabel = "Vosk 인식 ${(i.toFloat() * 100 / pcm.size).toInt()}%",
                            )
                        )
                    }
                }
                resultAdapter.fromJson(recognizer.finalResult)?.result?.let { allWords += it }
            } finally {
                runCatching { recognizer.close() }
                runCatching { model.close() }
            }

            groupIntoCues(allWords)
        }
    }

    /**
     * 단어 리스트를 자막 큐로 그룹핑.
     * - 기본은 7단어씩 묶음
     * - 단어 사이 간격이 [SILENCE_BREAK_S] 초 이상이면 자르고 새 큐 시작
     */
    private fun groupIntoCues(words: List<VoskWord>): List<Cue> {
        if (words.isEmpty()) return emptyList()
        val cues = mutableListOf<Cue>()
        var bucket = mutableListOf<VoskWord>()

        fun flush() {
            if (bucket.isEmpty()) return
            val first = bucket.first()
            val last = bucket.last()
            cues += Cue(
                index = cues.size + 1,
                startMs = (first.start * 1000).toLong(),
                endMs = (last.end * 1000).toLong(),
                text = bucket.joinToString(" ") { it.word },
            )
            bucket = mutableListOf()
        }

        for ((idx, w) in words.withIndex()) {
            if (bucket.isNotEmpty()) {
                val prev = bucket.last()
                if (w.start - prev.end > SILENCE_BREAK_S) {
                    flush()
                }
            }
            bucket += w
            if (bucket.size >= MAX_WORDS_PER_CUE) flush()
            // 마지막 단어면 강제 flush
            if (idx == words.lastIndex) flush()
        }
        return cues
    }

    @JsonClass(generateAdapter = true)
    internal data class VoskResult(
        val result: List<VoskWord>? = null,
        val text: String? = null,
    )

    @JsonClass(generateAdapter = true)
    internal data class VoskWord(
        val word: String,
        val start: Double,
        val end: Double,
        val conf: Double = 1.0,
    )

    companion object {
        private const val SAMPLE_RATE = 16_000
        private const val MAX_WORDS_PER_CUE = 7
        private const val SILENCE_BREAK_S = 0.8
    }
}
