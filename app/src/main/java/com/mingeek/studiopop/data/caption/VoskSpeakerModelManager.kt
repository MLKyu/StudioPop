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
import java.util.zip.ZipInputStream

/**
 * Vosk Speaker 모델(약 13MB)을 첫 사용 시 다운로드 + 압축해제 + 캐시. 본 ASR 모델([VoskModelManager])
 * 과 별개 — fail-open 정책: 다운로드 실패해도 ASR 자체는 계속 동작하고 화자 라벨링만 비활성.
 *
 * 호스팅: alphacephei.com/vosk/models/vosk-model-spk-0.4.zip
 * R6 Vosk 화자 분리 라운드.
 */
class VoskSpeakerModelManager(
    private val context: Context,
    private val client: OkHttpClient,
) {

    enum class State { NOT_INSTALLED, DOWNLOADING, UNZIPPING, READY, FAILED }

    private val _state = MutableStateFlow(
        if (modelDir().exists() && modelDir().isDirectory) State.READY else State.NOT_INSTALLED
    )
    val state: StateFlow<State> = _state.asStateFlow()

    private val _progress = MutableStateFlow(0f)
    val progress: StateFlow<Float> = _progress.asStateFlow()

    fun modelPath(): String = modelDir().absolutePath

    fun isReady(): Boolean = _state.value == State.READY

    private fun rootDir(): File = File(context.filesDir, "vosk")
    private fun modelDir(): File = File(rootDir(), MODEL_DIR_NAME)
    private fun zipFile(): File = File(rootDir(), "$MODEL_DIR_NAME.zip")

    suspend fun ensureInstalled(): Result<Unit> = withContext(Dispatchers.IO) {
        if (modelDir().exists() && modelDir().isDirectory) {
            _state.value = State.READY
            return@withContext Result.success(Unit)
        }
        runCatching {
            rootDir().mkdirs()
            download()
            unzip()
            zipFile().delete()
            _state.value = State.READY
        }.onFailure {
            _state.value = State.FAILED
            runCatching { zipFile().delete() }
        }
    }

    private suspend fun download() = withContext(Dispatchers.IO) {
        _state.value = State.DOWNLOADING
        _progress.value = 0f
        val req = Request.Builder().url(MODEL_URL).build()
        client.newCall(req).execute().use { resp ->
            if (!resp.isSuccessful) error("화자 모델 다운로드 실패: HTTP ${resp.code}")
            val total = resp.body?.contentLength()?.takeIf { it > 0 }
            val source = resp.body?.byteStream() ?: error("응답 바디 없음")
            FileOutputStream(zipFile()).use { out ->
                val buf = ByteArray(64 * 1024)
                var written = 0L
                while (true) {
                    val n = source.read(buf)
                    if (n <= 0) break
                    out.write(buf, 0, n)
                    written += n
                    if (total != null) {
                        _progress.value = (written.toFloat() / total).coerceIn(0f, 1f)
                    }
                }
            }
        }
    }

    private suspend fun unzip() = withContext(Dispatchers.IO) {
        _state.value = State.UNZIPPING
        _progress.value = 0f
        val parent = rootDir()
        ZipInputStream(zipFile().inputStream()).use { zin ->
            var entry = zin.nextEntry
            while (entry != null) {
                val outFile = File(parent, entry.name)
                if (entry.isDirectory) {
                    outFile.mkdirs()
                } else {
                    outFile.parentFile?.mkdirs()
                    FileOutputStream(outFile).use { out ->
                        zin.copyTo(out)
                    }
                }
                zin.closeEntry()
                entry = zin.nextEntry
            }
        }
        if (!modelDir().exists()) {
            // 다른 이름으로 풀린 경우 첫 매칭 디렉토리를 modelDir 로 rename
            val nested = parent.listFiles()
                ?.firstOrNull { it.isDirectory && it.name.startsWith("vosk-model-spk") }
            nested?.renameTo(modelDir())
        }
        if (!modelDir().exists()) error("화자 모델 압축해제 후 디렉토리 없음")
    }

    companion object {
        const val MODEL_DIR_NAME = "vosk-model-spk-0.4"
        private const val MODEL_URL = "https://alphacephei.com/vosk/models/$MODEL_DIR_NAME.zip"
    }
}
