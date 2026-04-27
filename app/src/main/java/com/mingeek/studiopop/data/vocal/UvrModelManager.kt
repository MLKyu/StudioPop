package com.mingeek.studiopop.data.vocal

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
 * UVR MDX-Net ONNX 모델 lazy 다운로드 + 캐시.
 *
 * 모델: `UVR_MDXNET_9482.onnx` (29.7MB, MIT license)
 * 호스팅: HuggingFace `seanghay/uvr_models`
 * 출력: 2-stem (vocals / accompaniment) 중 vocals 이 우리 용도.
 *
 * 앱 첫 사용 시 한 번만 다운로드 후 filesDir/uvr/ 에 저장. 이후 오프라인.
 */
class UvrModelManager(
    private val context: Context,
    private val client: OkHttpClient,
) {

    enum class InstallState { NOT_INSTALLED, DOWNLOADING, READY, FAILED }

    private val _state = MutableStateFlow(initialState())
    val state: StateFlow<InstallState> = _state.asStateFlow()

    private val _downloadProgress = MutableStateFlow(0f)
    val downloadProgress: StateFlow<Float> = _downloadProgress.asStateFlow()

    fun modelPath(): String = modelFile().absolutePath

    fun isReady(): Boolean = modelFile().let { it.exists() && it.length() > 1_000_000 }

    private fun rootDir(): File = File(context.filesDir, "uvr").also { it.mkdirs() }
    private fun modelFile(): File = File(rootDir(), MODEL_FILE_NAME)

    private fun initialState(): InstallState =
        if (isReady()) InstallState.READY else InstallState.NOT_INSTALLED

    suspend fun ensureInstalled(): Result<Unit> = withContext(Dispatchers.IO) {
        if (isReady()) {
            _state.value = InstallState.READY
            return@withContext Result.success(Unit)
        }
        runCatching {
            _state.value = InstallState.DOWNLOADING
            _downloadProgress.value = 0f
            val req = Request.Builder().url(MODEL_URL).build()
            client.newCall(req).execute().use { resp ->
                if (!resp.isSuccessful) error("UVR 모델 다운로드 실패: HTTP ${resp.code}")
                val total = resp.body?.contentLength()?.takeIf { it > 0 } ?: APPROX_BYTES
                val source = resp.body?.byteStream() ?: error("응답 바디 없음")
                FileOutputStream(modelFile()).use { out ->
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
            _state.value = InstallState.READY
        }.onFailure {
            _state.value = InstallState.FAILED
            runCatching { modelFile().delete() }
        }
    }

    companion object {
        private const val MODEL_FILE_NAME = "UVR_MDXNET_9482.onnx"
        private const val MODEL_URL =
            "https://huggingface.co/seanghay/uvr_models/resolve/main/$MODEL_FILE_NAME"
        private const val APPROX_BYTES = 30L * 1024 * 1024 // 29.7MB
    }
}
