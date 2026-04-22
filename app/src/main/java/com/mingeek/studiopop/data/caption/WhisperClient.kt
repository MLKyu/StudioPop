package com.mingeek.studiopop.data.caption

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.MultipartBody
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.asRequestBody
import java.io.File

/**
 * OpenAI Whisper API (/v1/audio/transcriptions) 호출 클라이언트.
 * response_format=srt 로 요청하면 SRT 문자열을 그대로 받음.
 *
 * [apiKeyProvider] 는 호출 시점에 최신 값을 읽음 → 설정 화면에서 변경 즉시 반영.
 */
class WhisperClient(
    private val client: OkHttpClient,
    private val apiKeyProvider: suspend () -> String,
) {

    /**
     * @param audioFile m4a/mp3/wav 등 Whisper 가 받는 포맷
     * @param language ISO-639-1 (예: "ko"). null 이면 자동 감지
     * @return SRT 문자열
     */
    suspend fun transcribeToSrt(
        audioFile: File,
        language: String? = "ko",
        model: String = "whisper-1",
    ): Result<String> = withContext(Dispatchers.IO) {
        runCatching {
            val apiKey = apiKeyProvider()
            require(apiKey.isNotBlank()) {
                "OPENAI_API_KEY 가 비어 있습니다. 설정 화면에서 입력하세요."
            }
            require(audioFile.length() <= MAX_FILE_BYTES) {
                "파일이 ${MAX_FILE_MB}MB 를 초과합니다 (${audioFile.length() / 1024 / 1024}MB). " +
                        "분할 업로드는 이후 단계에서 구현 예정."
            }

            val mediaType = "audio/mp4".toMediaType()
            val bodyBuilder = MultipartBody.Builder()
                .setType(MultipartBody.FORM)
                .addFormDataPart("model", model)
                .addFormDataPart("response_format", "srt")
                .addFormDataPart("file", audioFile.name, audioFile.asRequestBody(mediaType))

            if (!language.isNullOrBlank()) {
                bodyBuilder.addFormDataPart("language", language)
            }

            val request = Request.Builder()
                .url(ENDPOINT)
                .addHeader("Authorization", "Bearer $apiKey")
                .post(bodyBuilder.build())
                .build()

            client.newCall(request).execute().use { response ->
                val bodyString = response.body?.string().orEmpty()
                if (!response.isSuccessful) {
                    error("Whisper API ${response.code}: $bodyString")
                }
                bodyString
            }
        }
    }

    companion object {
        private const val ENDPOINT = "https://api.openai.com/v1/audio/transcriptions"
        private const val MAX_FILE_MB = 25L
        private const val MAX_FILE_BYTES = MAX_FILE_MB * 1024 * 1024
    }
}
