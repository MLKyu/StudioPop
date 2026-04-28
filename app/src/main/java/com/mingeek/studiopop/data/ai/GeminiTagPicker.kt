package com.mingeek.studiopop.data.ai

import com.squareup.moshi.JsonClass
import com.squareup.moshi.Moshi
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.util.concurrent.ConcurrentHashMap

/**
 * Gemini 로 주제 + 단순 키워드 → YouTube 태그 10~15개 추출.
 *
 * 한 번의 호출로 검색 친화적 태그를 뽑아준다. 키워드 단순 빈도 추출과 달리 트렌드/문맥/연관어를
 * 모델이 함께 고려.
 *
 * 모델 폴백/쿨다운 패턴은 [com.mingeek.studiopop.data.thumbnail.GeminiCopywriter] 와 동일.
 */
class GeminiTagPicker(
    private val client: OkHttpClient,
    private val moshi: Moshi,
    private val apiKeyProvider: suspend () -> String,
) {

    private val cooldownUntilMs = ConcurrentHashMap<String, Long>()

    @Volatile private var cachedModels: List<String> = emptyList()
    @Volatile private var cacheApiKey: String? = null
    @Volatile private var cacheExpiresAtMs: Long = 0L

    suspend fun pickTags(
        topic: String,
        keywordsHint: List<String> = emptyList(),
        targetCount: Int = 12,
    ): Result<List<String>> = withContext(Dispatchers.IO) {
        runCatching {
            require(topic.isNotBlank()) { "주제가 비어 있습니다." }
            val apiKey = apiKeyProvider()
            require(apiKey.isNotBlank()) {
                "GEMINI_API_KEY 가 비어 있습니다. 설정 화면에서 입력하세요."
            }

            val models = listAvailableModels(apiKey)
            require(models.isNotEmpty()) {
                "Gemini 사용 가능 모델 목록이 비어있습니다."
            }

            val prompt = buildPrompt(topic, keywordsHint, targetCount)
            val attempts = mutableListOf<String>()

            for (model in models) {
                val now = System.currentTimeMillis()
                val until = cooldownUntilMs[model] ?: 0L
                if (until > now) {
                    attempts += "$model (쿨다운 ${(until - now) / 1000}s 남음)"
                    continue
                }

                when (val res = callModel(model, apiKey, prompt, targetCount)) {
                    is ModelResult.Success -> return@runCatching res.tags
                    is ModelResult.Retryable -> {
                        cooldownUntilMs[model] = System.currentTimeMillis() + res.cooldownMs
                        attempts += "$model: ${res.reason}"
                    }
                }
            }

            error(
                "사용 가능한 모든 Gemini 모델이 응답 실패. 잠시 후 재시도하세요.\n" +
                    attempts.joinToString("\n") { "- $it" }
            )
        }
    }

    private fun listAvailableModels(apiKey: String): List<String> {
        val now = System.currentTimeMillis()
        if (cacheApiKey == apiKey && now < cacheExpiresAtMs && cachedModels.isNotEmpty()) {
            return cachedModels
        }
        val fresh = fetchAndSortModels(apiKey)
        if (fresh.isNotEmpty()) {
            cachedModels = fresh
            cacheApiKey = apiKey
            cacheExpiresAtMs = now + MODEL_CACHE_TTL_MS
        }
        return fresh
    }

    private fun fetchAndSortModels(apiKey: String): List<String> = try {
        val request = Request.Builder()
            .url("${BASE_URL}models")
            .addHeader("x-goog-api-key", apiKey)
            .get()
            .build()
        client.newCall(request).execute().use { response ->
            if (!response.isSuccessful) return@use emptyList()
            val raw = response.body?.string().orEmpty()
            val parsed = runCatching {
                moshi.adapter(ListModelsResponse::class.java).fromJson(raw)
            }.getOrNull() ?: return@use emptyList()

            parsed.models.orEmpty().asSequence()
                .filter { m ->
                    val name = m.name
                    !name.isNullOrBlank() &&
                        name.startsWith("models/gemini-") &&
                        m.supportedGenerationMethods?.contains("generateContent") == true
                }
                .mapNotNull { it.name?.removePrefix("models/") }
                .distinct()
                .toList()
                .sortedWith(modelQualityComparator())
        }
    } catch (_: Exception) {
        emptyList()
    }

    private fun modelQualityComparator(): Comparator<String> =
        compareByDescending<String> { versionScore(it) }
            .thenByDescending { tierScore(it) }
            .thenBy { stabilityPenalty(it) }
            .thenBy { it }

    private fun versionScore(name: String): Double =
        Regex("gemini-(\\d+(?:\\.\\d+)?)").find(name)
            ?.groupValues?.getOrNull(1)?.toDoubleOrNull() ?: 0.0

    private fun tierScore(name: String): Int = when {
        name.contains("pro") -> 3
        name.contains("flash-lite") -> 1
        name.contains("flash") -> 2
        else -> 0
    }

    private fun stabilityPenalty(name: String): Int =
        if (name.contains("exp") || name.contains("preview")) 1 else 0

    private sealed class ModelResult {
        data class Success(val tags: List<String>) : ModelResult()
        data class Retryable(val reason: String, val cooldownMs: Long) : ModelResult()
    }

    private fun callModel(model: String, apiKey: String, prompt: String, count: Int): ModelResult {
        val body = GenerateContentRequest(
            contents = listOf(Content(role = "user", parts = listOf(Part(text = prompt)))),
            generationConfig = GenerationConfig(maxOutputTokens = 400, temperature = 0.6f),
        )
        val json = moshi.adapter(GenerateContentRequest::class.java).toJson(body)
        val request = Request.Builder()
            .url("${BASE_URL}models/$model:generateContent")
            .addHeader("x-goog-api-key", apiKey)
            .addHeader("content-type", "application/json")
            .post(json.toRequestBody("application/json; charset=utf-8".toMediaType()))
            .build()

        return try {
            client.newCall(request).execute().use { response ->
                val raw = response.body?.string().orEmpty()
                if (!response.isSuccessful) {
                    return@use mapErrorToRetryable(response.code, response.header("Retry-After"), raw)
                }
                val parsed = runCatching {
                    moshi.adapter(GenerateContentResponse::class.java).fromJson(raw)
                }.getOrNull() ?: return@use ModelResult.Retryable("응답 파싱 실패", SHORT_COOLDOWN_MS)
                val text = parsed.candidates
                    ?.firstOrNull()
                    ?.content
                    ?.parts
                    ?.firstNotNullOfOrNull { it.text }
                    .orEmpty()
                val tags = parseTags(text, count)
                if (tags.isEmpty()) ModelResult.Retryable("빈 태그 응답", SHORT_COOLDOWN_MS)
                else ModelResult.Success(tags)
            }
        } catch (e: Exception) {
            ModelResult.Retryable(
                "예외: ${e.message?.take(80) ?: e.javaClass.simpleName}", SHORT_COOLDOWN_MS,
            )
        }
    }

    /**
     * 응답 텍스트(쉼표/줄바꿈/번호) → 깨끗한 태그 리스트. # 접두는 제거.
     */
    private fun parseTags(text: String, count: Int): List<String> {
        return text.split(Regex("[,\\n]"))
            .map { it.trim() }
            .map { it.removePrefix("#").removePrefix("-").removePrefix("•").trim() }
            .map { it.removePrefix("\"").removeSuffix("\"") }
            .filter { it.isNotBlank() && it.length <= 30 }
            .distinct()
            .take(count)
    }

    private fun mapErrorToRetryable(code: Int, retryAfter: String?, body: String): ModelResult.Retryable {
        val retryAfterMs = retryAfter?.toLongOrNull()?.times(1000L)
        val quotaHint = body.contains("RESOURCE_EXHAUSTED", ignoreCase = true) ||
            body.contains("quota", ignoreCase = true)
        val cooldownMs = retryAfterMs ?: when {
            code == 429 || quotaHint -> LONG_COOLDOWN_MS
            code == 404 -> LONG_COOLDOWN_MS
            code in 500..599 -> SHORT_COOLDOWN_MS
            else -> SHORT_COOLDOWN_MS
        }
        return ModelResult.Retryable(
            reason = if (quotaHint) "HTTP $code (quota)" else "HTTP $code",
            cooldownMs = cooldownMs,
        )
    }

    private fun buildPrompt(topic: String, keywords: List<String>, count: Int): String {
        val hint = if (keywords.isEmpty()) "(없음)" else keywords.take(8).joinToString(", ")
        return """
            당신은 한국 유튜브 검색 최적화 도우미입니다.
            영상 주제: $topic
            본문 키워드 힌트: $hint

            검색 친화적인 태그 ${count}개를 한 줄에 하나씩 출력하세요.
            - 한국어 위주, 필요하면 영어 일부 혼용
            - # 기호·번호·따옴표 없이 단어/구만
            - 각 태그 30자 이하
            - 채널 정체성에 도움 되는 일반 태그 + 영상 특화 태그 혼합
        """.trimIndent()
    }

    @JsonClass(generateAdapter = true)
    internal data class GenerateContentRequest(
        val contents: List<Content>,
        val generationConfig: GenerationConfig? = null,
    )

    @JsonClass(generateAdapter = true)
    internal data class Content(
        val role: String? = null,
        val parts: List<Part>? = null,
    )

    @JsonClass(generateAdapter = true)
    internal data class Part(val text: String? = null)

    @JsonClass(generateAdapter = true)
    internal data class GenerationConfig(
        val maxOutputTokens: Int? = null,
        val temperature: Float? = null,
    )

    @JsonClass(generateAdapter = true)
    internal data class GenerateContentResponse(
        val candidates: List<Candidate>? = null,
    )

    @JsonClass(generateAdapter = true)
    internal data class Candidate(val content: Content? = null)

    @JsonClass(generateAdapter = true)
    internal data class ListModelsResponse(val models: List<ModelInfo>? = null)

    @JsonClass(generateAdapter = true)
    internal data class ModelInfo(
        val name: String? = null,
        val displayName: String? = null,
        val supportedGenerationMethods: List<String>? = null,
    )

    companion object {
        private const val BASE_URL = "https://generativelanguage.googleapis.com/v1beta/"
        private const val SHORT_COOLDOWN_MS = 30_000L
        private const val LONG_COOLDOWN_MS = 10 * 60_000L
        private const val MODEL_CACHE_TTL_MS = 60 * 60_000L
    }
}
