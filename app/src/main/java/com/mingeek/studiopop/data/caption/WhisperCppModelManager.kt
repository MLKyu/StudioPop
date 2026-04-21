package com.mingeek.studiopop.data.caption

import android.content.Context
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.File
import java.io.FileOutputStream

/**
 * whisper.cpp 의 GGML 모델(.bin) 자동 다운로드 + 캐시.
 *
 * Variant 별 크기/품질:
 *  - tiny  : ~75MB   (가장 빠름, 한국어 정확도 낮음)
 *  - base  : ~142MB  (한국어 권장 시작점)
 *  - small : ~466MB  (느리지만 정확도 높음)
 *
 * 호스팅: HuggingFace ggerganov/whisper.cpp
 */
class WhisperCppModelManager(
    private val context: Context,
    private val client: OkHttpClient,
    private val variant: Variant = Variant.BASE,
) {

    enum class Variant(val fileName: String, val approxBytes: Long) {
        TINY ("ggml-tiny.bin",  75L * 1024 * 1024),
        BASE ("ggml-base.bin", 142L * 1024 * 1024),
        SMALL("ggml-small.bin", 466L * 1024 * 1024),
    }

    enum class State { NOT_INSTALLED, DOWNLOADING, READY, FAILED }

    private val _state = MutableStateFlow(if (modelFile().exists()) State.READY else State.NOT_INSTALLED)
    val state: StateFlow<State> = _state.asStateFlow()

    private val _progress = MutableStateFlow(0f)
    val progress: StateFlow<Float> = _progress.asStateFlow()

    fun modelPath(): String = modelFile().absolutePath

    fun isReady(): Boolean = modelFile().exists() && modelFile().length() > 1_000_000

    private fun rootDir(): File = File(context.filesDir, "whisper-cpp").also { it.mkdirs() }
    private fun modelFile(): File = File(rootDir(), variant.fileName)

    suspend fun ensureInstalled(): Result<Unit> = withContext(Dispatchers.IO) {
        if (isReady()) {
            _state.value = State.READY
            return@withContext Result.success(Unit)
        }
        runCatching {
            _state.value = State.DOWNLOADING
            _progress.value = 0f
            val url = "$BASE_URL${variant.fileName}"
            val req = Request.Builder().url(url).build()
            client.newCall(req).execute().use { resp ->
                if (!resp.isSuccessful) error("모델 다운로드 실패: HTTP ${resp.code}")
                val total = resp.body?.contentLength()?.takeIf { it > 0 } ?: variant.approxBytes
                val source = resp.body?.byteStream() ?: error("응답 바디 없음")
                FileOutputStream(modelFile()).use { out ->
                    val buf = ByteArray(64 * 1024)
                    var written = 0L
                    while (true) {
                        val n = source.read(buf)
                        if (n <= 0) break
                        out.write(buf, 0, n)
                        written += n
                        _progress.value = (written.toFloat() / total).coerceIn(0f, 1f)
                    }
                }
            }
            _state.value = State.READY
        }.onFailure {
            _state.value = State.FAILED
            runCatching { modelFile().delete() }
        }
    }

    companion object {
        private const val BASE_URL =
            "https://huggingface.co/ggerganov/whisper.cpp/resolve/main/"
    }
}
