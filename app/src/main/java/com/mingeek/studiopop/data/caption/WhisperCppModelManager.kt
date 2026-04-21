package com.mingeek.studiopop.data.caption

import android.content.Context
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.File
import java.io.FileOutputStream

/**
 * whisper.cpp 의 GGML 모델(.bin) 자동 다운로드 + 캐시.
 *
 * Variant 별 크기/품질:
 *  - tiny      : 75MB     낮은 정확도, 가장 빠름
 *  - base      : 142MB    중상, 한국어 권장 시작점 (기본값)
 *  - base.q5_0 : 57MB     5-bit 양자화. base 와 거의 동일 정확도, 빠르고 가벼움
 *  - small     : 466MB    상, 가장 정확하나 느림
 *  - small.q5_0: 190MB    5-bit 양자화. small 의 정확도 + 더 작고 빠름 ★ 추천
 *
 * 호스팅: HuggingFace ggerganov/whisper.cpp
 *
 * 멀티 variant 지원: 사용자가 바꿔도 이전 다운로드 유지.
 */
class WhisperCppModelManager(
    private val context: Context,
    private val client: OkHttpClient,
) {

    enum class Variant(
        val displayName: String,
        val fileName: String,
        val approxBytes: Long,
    ) {
        TINY    ("tiny (75MB · 빠름 · 정확도 낮음)",      "ggml-tiny.bin",       75L * 1024 * 1024),
        BASE    ("base (142MB · 균형)",                    "ggml-base.bin",      142L * 1024 * 1024),
        BASE_Q5 ("base.q5 (57MB · base 와 동급, 빠름) ★",  "ggml-base.q5_0.bin",  57L * 1024 * 1024),
        SMALL   ("small (466MB · 가장 정확)",              "ggml-small.bin",     466L * 1024 * 1024),
        SMALL_Q5("small.q5 (190MB · small 동급, 더 빠름)", "ggml-small.q5_0.bin", 190L * 1024 * 1024),
    }

    enum class InstallState { NOT_INSTALLED, DOWNLOADING, READY, FAILED }

    /** Variant → 현재 설치 상태 (UI 가 관찰) */
    private val _states = MutableStateFlow<Map<Variant, InstallState>>(initialStates())
    val states: StateFlow<Map<Variant, InstallState>> = _states.asStateFlow()

    /** 현재 다운로드 중인 variant 의 진행률 (0..1) */
    private val _downloadProgress = MutableStateFlow(0f)
    val downloadProgress: StateFlow<Float> = _downloadProgress.asStateFlow()

    fun modelPath(variant: Variant): String = modelFile(variant).absolutePath

    fun isReady(variant: Variant): Boolean =
        modelFile(variant).exists() && modelFile(variant).length() > 1_000_000

    private fun rootDir(): File = File(context.filesDir, "whisper-cpp").also { it.mkdirs() }
    private fun modelFile(variant: Variant): File = File(rootDir(), variant.fileName)

    private fun initialStates(): Map<Variant, InstallState> =
        Variant.entries.associateWith {
            if (modelFile(it).exists() && modelFile(it).length() > 1_000_000)
                InstallState.READY else InstallState.NOT_INSTALLED
        }

    suspend fun ensureInstalled(variant: Variant): Result<Unit> = withContext(Dispatchers.IO) {
        if (isReady(variant)) {
            updateState(variant, InstallState.READY)
            return@withContext Result.success(Unit)
        }
        runCatching {
            updateState(variant, InstallState.DOWNLOADING)
            _downloadProgress.value = 0f
            val url = "$BASE_URL${variant.fileName}"
            val req = Request.Builder().url(url).build()
            client.newCall(req).execute().use { resp ->
                if (!resp.isSuccessful) error("모델 다운로드 실패: HTTP ${resp.code}")
                val total = resp.body?.contentLength()?.takeIf { it > 0 } ?: variant.approxBytes
                val source = resp.body?.byteStream() ?: error("응답 바디 없음")
                FileOutputStream(modelFile(variant)).use { out ->
                    val buf = ByteArray(64 * 1024)
                    var written = 0L
                    while (true) {
                        val n = source.read(buf)
                        if (n <= 0) break
                        out.write(buf, 0, n)
                        written += n
                        _downloadProgress.value = (written.toFloat() / total).coerceIn(0f, 1f)
                    }
                }
            }
            updateState(variant, InstallState.READY)
        }.onFailure {
            updateState(variant, InstallState.FAILED)
            runCatching { modelFile(variant).delete() }
        }
    }

    private fun updateState(variant: Variant, state: InstallState) {
        _states.update { it + (variant to state) }
    }

    companion object {
        private const val BASE_URL =
            "https://huggingface.co/ggerganov/whisper.cpp/resolve/main/"
    }
}
