package com.mingeek.studiopop.data.caption

import android.net.Uri
import com.squareup.moshi.JsonClass
import com.squareup.moshi.Moshi
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * whisper.cpp 온디바이스 STT.
 *
 *  1. WhisperCppModelManager 로 선택된 [Variant] 모델을 자동 다운로드
 *  2. PcmDecoder 로 영상 → 16kHz mono short[] PCM
 *  3. Short→Float 정규화 ([-1.0, 1.0])
 *  4. JNI 호출 → segments JSON
 *  5. Cue 리스트 변환
 *
 * 진행률은 native 의 atomic 변수에 누적되며, Kotlin 측에서 200ms 주기로 폴링.
 */
class WhisperCppEngine(
    private val pcmDecoder: PcmDecoder,
    private val modelManager: WhisperCppModelManager,
    private val moshi: Moshi,
    /** 사용자가 선택할 수 있도록 외부 람다로 노출 (CaptionViewModel 이 주입) */
    private val variantProvider: () -> WhisperCppModelManager.Variant =
        { WhisperCppModelManager.Variant.BASE },
) : SpeechToText {

    override val id: SttEngine = SttEngine.WHISPER_CPP

    override suspend fun isAvailable(): SpeechToText.Availability {
        if (!nativeLoaded) {
            return SpeechToText.Availability.Unsupported(
                reason = "네이티브 라이브러리 로드 실패. CPU ABI 확인.",
            )
        }
        val variant = variantProvider()
        return if (modelManager.isReady(variant)) SpeechToText.Availability.Ready
        else SpeechToText.Availability.NeedsSetup(
            reason = "${variant.displayName} 모델 다운로드 필요. 첫 실행 시 자동 진행.",
        )
    }

    override suspend fun transcribe(
        videoUri: Uri,
        language: String?,
        onProgress: (SpeechToText.Progress) -> Unit,
    ): Result<List<Cue>> = withContext(Dispatchers.Default) {
        runCatching {
            check(nativeLoaded) { "libstudiopop_native.so 로드 실패" }
            val variant = variantProvider()

            onProgress(SpeechToText.Progress(0, 100, "모델 준비 (${variant.displayName})"))
            // 다운로드 진행률을 200ms 마다 onProgress 로 발화
            coroutineScope {
                val downloadPoller = launch {
                    while (isActive) {
                        val pct = (modelManager.downloadProgress.value * 100).toInt().coerceIn(0, 100)
                        onProgress(SpeechToText.Progress(pct, 100, "모델 다운로드"))
                        delay(POLL_MS)
                    }
                }
                try {
                    modelManager.ensureInstalled(variant).getOrThrow()
                } finally {
                    downloadPoller.cancel()
                }
            }

            onProgress(SpeechToText.Progress(0, 100, "오디오 디코드"))
            val pcmShort = pcmDecoder.decode(videoUri, SAMPLE_RATE)
            if (pcmShort.isEmpty()) error("디코드된 오디오가 비어 있음")

            onProgress(SpeechToText.Progress(0, 100, "PCM 정규화"))
            val pcmFloat = FloatArray(pcmShort.size) { pcmShort[it] / 32768f }

            val handle = nativeInit(modelManager.modelPath(variant))
            check(handle != 0L) { "whisper context 초기화 실패" }
            val resultJson = try {
                runWithProgressPolling(onProgress) {
                    nativeTranscribe(handle, pcmFloat, SAMPLE_RATE, language ?: "")
                }
            } finally {
                nativeRelease(handle)
            }

            parseSegments(resultJson)
        }
    }

    /**
     * native 호출 동안 별도 코루틴이 [nativeProgress] 를 200ms 주기로 폴링해서
     * UI 로 발화한다. native 호출이 끝나면 폴링도 종료.
     */
    private suspend fun <T> runWithProgressPolling(
        onProgress: (SpeechToText.Progress) -> Unit,
        block: suspend () -> T,
    ): T = coroutineScope {
        val poller: Job = launch(Dispatchers.Default) {
            while (isActive) {
                val pct = nativeProgress().coerceIn(0, 100)
                onProgress(SpeechToText.Progress(pct, 100, "whisper.cpp 추론"))
                delay(POLL_MS)
            }
        }
        try {
            block()
        } finally {
            poller.cancel()
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
    private external fun nativeProgress(): Int

    companion object {
        private const val SAMPLE_RATE = 16_000
        private const val POLL_MS = 200L

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
