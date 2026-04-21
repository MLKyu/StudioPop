package com.mingeek.studiopop.data.caption

import android.net.Uri
import com.squareup.moshi.JsonClass
import com.squareup.moshi.Moshi
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * whisper.cpp 온디바이스 STT.
 *
 *  1. WhisperCppModelManager 로 GGML 모델(base 기본, ~142MB) 자동 다운로드
 *  2. PcmDecoder 로 영상 → 16kHz mono short[] PCM
 *  3. Short→Float 정규화 ([-1.0, 1.0])
 *  4. JNI 호출 → segments JSON
 *  5. Cue 리스트 변환
 *
 * 네이티브 라이브러리: libstudiopop_native.so (CMakeLists.txt + jni_bridge.cpp)
 */
class WhisperCppEngine(
    private val pcmDecoder: PcmDecoder,
    private val modelManager: WhisperCppModelManager,
    private val moshi: Moshi,
) : SpeechToText {

    override val id: SttEngine = SttEngine.WHISPER_CPP

    override suspend fun isAvailable(): SpeechToText.Availability {
        if (!nativeLoaded) {
            return SpeechToText.Availability.Unsupported(
                reason = "네이티브 라이브러리 로드 실패. CPU ABI 확인.",
            )
        }
        return if (modelManager.isReady()) SpeechToText.Availability.Ready
        else SpeechToText.Availability.NeedsSetup(
            reason = "GGML 모델(${modelManager.modelPath().substringAfterLast('/')}, " +
                    "약 142MB) 다운로드 필요. 첫 실행 시 자동 진행.",
        )
    }

    override suspend fun transcribe(
        videoUri: Uri,
        language: String?,
        onProgress: (SpeechToText.Progress) -> Unit,
    ): Result<List<Cue>> = withContext(Dispatchers.Default) {
        runCatching {
            check(nativeLoaded) { "libstudiopop_native.so 로드 실패" }

            onProgress(SpeechToText.Progress(0, 4, "모델 준비"))
            modelManager.ensureInstalled().getOrThrow()

            onProgress(SpeechToText.Progress(1, 4, "오디오 디코드"))
            val pcmShort = pcmDecoder.decode(videoUri, SAMPLE_RATE)
            if (pcmShort.isEmpty()) error("디코드된 오디오가 비어 있음")

            onProgress(SpeechToText.Progress(2, 4, "PCM 정규화"))
            val pcmFloat = FloatArray(pcmShort.size) { pcmShort[it] / 32768f }

            onProgress(SpeechToText.Progress(3, 4, "whisper.cpp 추론 (시간 걸림)"))
            val handle = nativeInit(modelManager.modelPath())
            check(handle != 0L) { "whisper context 초기화 실패" }
            val resultJson = try {
                nativeTranscribe(handle, pcmFloat, SAMPLE_RATE, language ?: "")
            } finally {
                nativeRelease(handle)
            }

            parseSegments(resultJson)
        }
    }

    private fun parseSegments(json: String): List<Cue> {
        val parsed = moshi.adapter(NativeResult::class.java).fromJson(json)
            ?: error("결과 JSON 파싱 실패")
        parsed.error?.let { error("native: $it") }
        val segs = parsed.segments.orEmpty()
        return segs.mapIndexed { i, s ->
            Cue(
                index = i + 1,
                startMs = s.t0_ms,
                endMs = s.t1_ms,
                text = s.text.trim(),
            )
        }
    }

    @JsonClass(generateAdapter = true)
    internal data class NativeResult(
        val segments: List<NativeSegment>? = null,
        val error: String? = null,
    )

    @JsonClass(generateAdapter = true)
    internal data class NativeSegment(
        val t0_ms: Long,
        val t1_ms: Long,
        val text: String,
    )

    // --- Native ---
    private external fun nativeInit(modelPath: String): Long
    private external fun nativeTranscribe(
        handle: Long,
        pcm: FloatArray,
        sampleRate: Int,
        language: String,
    ): String
    private external fun nativeRelease(handle: Long)

    companion object {
        private const val SAMPLE_RATE = 16_000

        @Volatile
        private var nativeLoaded: Boolean = false

        init {
            nativeLoaded = try {
                System.loadLibrary("studiopop_native")
                true
            } catch (_: Throwable) {
                false
            }
        }
    }
}
