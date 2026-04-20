package com.mingeek.studiopop.data.thumbnail

import com.squareup.moshi.JsonClass
import com.squareup.moshi.Moshi
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody

/**
 * Claude Messages API 로 썸네일 카피 후보 생성.
 */
class ClaudeCopywriter(
    private val client: OkHttpClient,
    private val moshi: Moshi,
    private val apiKey: String,
) {

    suspend fun suggestThumbnailCopies(
        topic: String,
        tone: String = "유튜브 뽑기/리액션 스타일, 강렬하고 호기심 유발",
        count: Int = 5,
    ): Result<List<String>> = withContext(Dispatchers.IO) {
        runCatching {
            require(apiKey.isNotBlank()) { "ANTHROPIC_API_KEY 가 비어 있습니다. local.properties 확인." }

            val body = MessagesRequest(
                model = MODEL,
                max_tokens = 600,
                system = "당신은 한국 유튜브 썸네일 카피라이터입니다. " +
                        "짧고(8~18자) 강렬하며 클릭을 유도하는 카피를 작성합니다. " +
                        "과도한 선정성/낚시성은 피하세요.",
                messages = listOf(
                    Message(
                        role = "user",
                        content = """
                            영상 주제: $topic
                            톤: $tone
                            썸네일 메인 카피 후보 ${count}개를 한 줄에 하나씩만 출력하세요.
                            번호/따옴표/설명 없이 카피만.
                        """.trimIndent()
                    )
                )
            )

            val json = moshi.adapter(MessagesRequest::class.java).toJson(body)
            val request = Request.Builder()
                .url(ENDPOINT)
                .addHeader("x-api-key", apiKey)
                .addHeader("anthropic-version", "2023-06-01")
                .addHeader("content-type", "application/json")
                .post(json.toRequestBody("application/json; charset=utf-8".toMediaType()))
                .build()

            client.newCall(request).execute().use { response ->
                val raw = response.body?.string().orEmpty()
                if (!response.isSuccessful) {
                    error("Claude API ${response.code}: $raw")
                }
                val parsed = moshi.adapter(MessagesResponse::class.java).fromJson(raw)
                    ?: error("Empty Claude response")
                val text = parsed.content.firstOrNull { it.type == "text" }?.text.orEmpty()
                text.lines()
                    .map { it.trim().removePrefix("-").removePrefix("•").trim() }
                    .filter { it.isNotEmpty() }
                    .take(count)
            }
        }
    }

    @JsonClass(generateAdapter = true)
    internal data class MessagesRequest(
        val model: String,
        val max_tokens: Int,
        val system: String,
        val messages: List<Message>,
    )

    @JsonClass(generateAdapter = true)
    internal data class Message(val role: String, val content: String)

    @JsonClass(generateAdapter = true)
    internal data class MessagesResponse(val content: List<ContentBlock>)

    @JsonClass(generateAdapter = true)
    internal data class ContentBlock(val type: String, val text: String?)

    companion object {
        private const val ENDPOINT = "https://api.anthropic.com/v1/messages"
        private const val MODEL = "claude-haiku-4-5-20251001"
    }
}
