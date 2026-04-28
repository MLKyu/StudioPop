package com.mingeek.studiopop.data.caption

import android.net.Uri
import com.squareup.moshi.JsonClass
import com.squareup.moshi.Moshi
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.vosk.Model
import org.vosk.Recognizer
import org.vosk.SpeakerModel

/**
 * Vosk 온디바이스 STT.
 *
 * 흐름:
 *  1. Korean 모델(50MB) 설치 확인 → 없으면 [VoskModelManager.ensureInstalled]
 *  2. (선택) Speaker 모델(13MB) 설치 시도 — 실패는 흡수 (fail-open, 화자 라벨링만 비활성)
 *  3. PCM 16-bit mono @ 16kHz 로 디코드 ([PcmDecoder])
 *  4. Vosk Recognizer 에 청크 단위로 주입 → JSON 결과 누적, Speaker 모델 attach 시 spk 벡터 동봉
 *  5. word-level 결과를 Cue 리스트로 그룹핑
 *  6. (Speaker 모델 가능 시) [SpeakerClusterer] 로 라벨 부여
 */
class VoskTranscriber(
    private val pcmDecoder: PcmDecoder,
    private val modelManager: VoskModelManager,
    private val moshi: Moshi,
    /**
     * R6: 선택적 화자 분리. null 이거나 다운로드 실패면 화자 라벨 미부여 — STT 자체는 동작.
     */
    private val speakerModelManager: VoskSpeakerModelManager? = null,
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
            // 1) 모델 보장 — 다운로드/압축해제 진행률 200ms 폴링
            onProgress(SpeechToText.Progress(0, 100, "모델 준비"))
            coroutineScope {
                val poller = launch {
                    while (isActive) {
                        val pct = (modelManager.progress.value * 100).toInt().coerceIn(0, 100)
                        val phase = when (modelManager.state.value) {
                            VoskModelManager.State.DOWNLOADING -> "Vosk 모델 다운로드"
                            VoskModelManager.State.UNZIPPING   -> "모델 압축 해제"
                            else                               -> "모델 준비"
                        }
                        onProgress(SpeechToText.Progress(pct, 100, phase))
                        delay(200L)
                    }
                }
                try {
                    modelManager.ensureInstalled().getOrThrow()
                } finally {
                    poller.cancel()
                }
            }

            // 2) (선택) 화자 모델 보장 — fail-open. 실패해도 본 STT 는 그대로 진행.
            val spkReady = speakerModelManager?.let { mgr ->
                runCatching { mgr.ensureInstalled().getOrThrow() }.isSuccess
            } ?: false

            // 3) PCM 디코드
            onProgress(SpeechToText.Progress(1, 3, "오디오 디코드"))
            val pcm = pcmDecoder.decode(videoUri, SAMPLE_RATE)
            if (pcm.isEmpty()) error("디코드된 오디오가 비어있습니다")

            // 4) 인식 (+ spk 벡터)
            onProgress(SpeechToText.Progress(2, 3, "Vosk 인식 중"))
            val model = Model(modelManager.modelPath())
            val spkModel = if (spkReady) {
                runCatching { SpeakerModel(speakerModelManager!!.modelPath()) }.getOrNull()
            } else null
            val recognizer = if (spkModel != null) {
                Recognizer(model, SAMPLE_RATE.toFloat(), spkModel)
            } else {
                Recognizer(model, SAMPLE_RATE.toFloat())
            }
            recognizer.setWords(true)

            val resultAdapter = moshi.adapter(VoskResult::class.java)
            // 결과 단위(utterance)로 수집 — 한 번의 result 가 여러 단어 + 단일 spk 벡터.
            val results = mutableListOf<VoskUtterance>()
            try {
                val chunkSize = 4_000 // 약 0.25초 단위
                var i = 0
                while (i < pcm.size) {
                    val len = minOf(chunkSize, pcm.size - i)
                    val chunk = pcm.copyOfRange(i, i + len)
                    val finalSeg = recognizer.acceptWaveForm(chunk, len)
                    if (finalSeg) {
                        resultAdapter.fromJson(recognizer.result)?.let { r ->
                            r.result?.let { ws ->
                                results += VoskUtterance(words = ws, spk = r.spk?.toFloatArray())
                            }
                        }
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
                resultAdapter.fromJson(recognizer.finalResult)?.let { r ->
                    r.result?.let { ws ->
                        results += VoskUtterance(words = ws, spk = r.spk?.toFloatArray())
                    }
                }
            } finally {
                runCatching { recognizer.close() }
                runCatching { spkModel?.close() }
                runCatching { model.close() }
            }

            // 5) 화자 클러스터링 — utterance 단위로 라벨 붙여서 cue 분할 후에도 라벨 유지.
            //    spkModel 이 없거나 일부 utterance 가 spk 부재면 해당 cue 의 라벨은 null.
            val labels = if (spkModel != null) {
                SpeakerClusterer.cluster(results.map { it.spk })
            } else List(results.size) { null }

            groupIntoCues(results, labels)
        }
    }

    /**
     * Utterance(=하나의 final result)를 묶음 단위로 자막 큐로 그룹핑. 각 utterance 의 화자 라벨이
     * 그 안 단어로부터 만들어진 모든 큐에 전파된다 — utterance 경계를 넘는 큐는 만들지 않음
     * (한 utterance 안에서만 단어 7개씩 또는 침묵으로 분할).
     */
    private fun groupIntoCues(
        utterances: List<VoskUtterance>,
        speakerLabels: List<String?>,
    ): List<Cue> {
        if (utterances.isEmpty()) return emptyList()
        val cues = mutableListOf<Cue>()
        for ((uIdx, utt) in utterances.withIndex()) {
            val label = speakerLabels.getOrNull(uIdx)
            cues += groupWordsIntoCues(utt.words, startIndex = cues.size + 1, speakerLabel = label)
        }
        return cues
    }

    private fun groupWordsIntoCues(
        words: List<VoskWord>,
        startIndex: Int,
        speakerLabel: String?,
    ): List<Cue> {
        if (words.isEmpty()) return emptyList()
        val out = mutableListOf<Cue>()
        var bucket = mutableListOf<VoskWord>()

        fun flush() {
            if (bucket.isEmpty()) return
            val first = bucket.first()
            val last = bucket.last()
            out += Cue(
                index = startIndex + out.size,
                startMs = (first.start * 1000).toLong(),
                endMs = (last.end * 1000).toLong(),
                text = bucket.joinToString(" ") { it.word },
                words = bucket.map { w ->
                    CueWord(
                        word = w.word,
                        startMs = (w.start * 1000).toLong(),
                        endMs = (w.end * 1000).toLong(),
                        confidence = w.conf.toFloat().coerceIn(0f, 1f),
                    )
                },
                speakerLabel = speakerLabel,
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
            if (idx == words.lastIndex) flush()
        }
        return out
    }

    /** 한 utterance 단위 — Vosk final result 한 번에서 나온 단어들 + 화자 임베딩(있으면). */
    private data class VoskUtterance(
        val words: List<VoskWord>,
        val spk: FloatArray?,
    )

    @JsonClass(generateAdapter = true)
    internal data class VoskResult(
        val result: List<VoskWord>? = null,
        val text: String? = null,
        /** 화자 모델 활성화 시 ~128차원 x-vector. */
        val spk: List<Float>? = null,
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
