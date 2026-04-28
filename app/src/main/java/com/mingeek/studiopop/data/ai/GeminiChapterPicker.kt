package com.mingeek.studiopop.data.ai

import com.mingeek.studiopop.data.caption.Cue
import com.squareup.moshi.JsonClass
import com.squareup.moshi.Moshi
import com.squareup.moshi.Types
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.util.concurrent.ConcurrentHashMap

/**
 * Gemini 로 SRT 큐를 분석해 YouTube 챕터(목차) 5~10 개 추출.
 *
 * 입력: 영상 길이 + SRT 큐
 * 출력: [Chapter] 리스트 — 첫 챕터는 항상 0ms, 시간 오름차순으로 정렬됨.
 *
 * 모델 폴백/쿨다운 패턴은 [com.mingeek.studiopop.data.thumbnail.GeminiCopywriter] 와 동일 —
 * R6 에서 헬퍼로 추출하기 전까지 의도적 복제.
 */
class GeminiChapterPicker(
    private val client: OkHttpClient,
    private val moshi: Moshi,
    private val apiKeyProvider: suspend () -> String,
) {

    private val cooldownUntilMs = ConcurrentHashMap<String, Long>()

    @Volatile private var cachedModels: List<String> = emptyList()
    @Volatile private var cacheApiKey: String? = null
    @Volatile private var cacheExpiresAtMs: Long = 0L

    suspend fun pickChapters(
        cues: List<Cue>,
        videoDurationMs: Long,
        targetCount: Int = 7,
    ): Result<List<Chapter>> = withContext(Dispatchers.IO) {
        runCatching {
            require(cues.isNotEmpty()) { "SRT 큐가 비어 있어 챕터를 추출할 수 없습니다." }
            require(videoDurationMs > 0L) { "영상 길이가 유효하지 않습니다." }
            val apiKey = apiKeyProvider()
            require(apiKey.isNotBlank()) {
                "GEMINI_API_KEY 가 비어 있습니다. 설정 화면에서 입력하세요."
            }

            val models = listAvailableModels(apiKey)
            require(models.isNotEmpty()) {
                "Gemini 사용 가능 모델 목록이 비어있습니다. 키가 유효한지 확인하세요."
            }

            val prompt = buildPrompt(cues, videoDurationMs, targetCount)
            val attempts = mutableListOf<String>()

            for (model in models) {
                val now = System.currentTimeMillis()
                val until = cooldownUntilMs[model] ?: 0L
                if (until > now) {
                    attempts += "$model (쿨다운 ${(until - now) / 1000}s 남음)"
                    continue
                }

                when (val res = callModel(model, apiKey, prompt)) {
                    is ModelResult.Success -> return@runCatching res.chapters
                        .sortedBy { it.startMs }
                        .let { sorted ->
                            // 첫 챕터는 0ms 보장 (YouTube 요구사항)
                            if (sorted.isNotEmpty() && sorted.first().startMs > 0L) {
                                listOf(Chapter(0L, sorted.first().title)) + sorted.drop(1)
                            } else sorted
                        }
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
        data class Success(val chapters: List<Chapter>) : ModelResult()
        data class Retryable(val reason: String, val cooldownMs: Long) : ModelResult()
    }

    private fun callModel(model: String, apiKey: String, prompt: String): ModelResult {
        val body = GenerateContentRequest(
            contents = listOf(Content(role = "user", parts = listOf(Part(text = prompt)))),
            generationConfig = GenerationConfig(
                maxOutputTokens = 1200,
                temperature = 0.5f,
                responseMimeType = "application/json",
            ),
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
                }.getOrNull() ?: return@use ModelResult.Retryable(
                    "응답 파싱 실패", SHORT_COOLDOWN_MS,
                )
                val text = parsed.candidates
                    ?.firstOrNull()
                    ?.content
                    ?.parts
                    ?.firstNotNullOfOrNull { it.text }
                    .orEmpty()
                val chapters = parseChapters(text)
                if (chapters.isEmpty()) {
                    ModelResult.Retryable("빈 챕터 응답", SHORT_COOLDOWN_MS)
                } else {
                    ModelResult.Success(chapters)
                }
            }
        } catch (e: Exception) {
            ModelResult.Retryable(
                "예외: ${e.message?.take(80) ?: e.javaClass.simpleName}", SHORT_COOLDOWN_MS,
            )
        }
    }

    /**
     * 응답 텍스트(JSON 또는 JSON 포함 마크다운) → Chapter 리스트.
     * Gemini 가 ```json``` 블록으로 감싸는 케이스도 처리.
     */
    private fun parseChapters(text: String): List<Chapter> {
        val cleaned = text.trim()
            .removePrefix("```json").removePrefix("```")
            .removeSuffix("```").trim()
        // JSON array 시작 위치를 찾아 그 부분만 시도
        val start = cleaned.indexOf('[')
        val end = cleaned.lastIndexOf(']')
        if (start < 0 || end <= start) return emptyList()
        val payload = cleaned.substring(start, end + 1)
        return runCatching {
            val type = Types.newParameterizedType(List::class.java, ChapterDto::class.java)
            val adapter = moshi.adapter<List<ChapterDto>>(type)
            val list = adapter.fromJson(payload).orEmpty()
            list.mapNotNull { dto ->
                val title = dto.title?.trim().orEmpty()
                if (title.isBlank()) null
                else Chapter(startMs = dto.startMs ?: 0L, title = title)
            }
        }.getOrDefault(emptyList())
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

    private fun buildPrompt(cues: List<Cue>, durationMs: Long, count: Int): String {
        val cueLines = cues.joinToString("\n") { c ->
            "[${c.startMs}ms] ${c.text.replace("\n", " ")}"
        }.take(8000) // prompt 너무 길어지지 않게 cap
        return """
            당신은 한국 유튜브 챕터 추천 어시스턴트입니다.
            아래 자막을 보고 영상 흐름을 ${count}개 챕터로 나눠 주세요.
            첫 챕터는 0ms 시작이어야 하며, 모든 startMs 는 ${durationMs}ms 미만이어야 합니다.
            제목은 한국어 5~14자로 호기심을 자극하되 정확한 내용을 반영합니다.

            응답은 JSON 배열만 출력 — 설명/마크다운 금지:
            [{"startMs": 0, "title": "오프닝"}, ...]

            영상 자막:
            $cueLines
        """.trimIndent()
    }

    @JsonClass(generateAdapter = true)
    internal data class ChapterDto(
        val startMs: Long? = null,
        val title: String? = null,
    )

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
        val responseMimeType: String? = null,
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
