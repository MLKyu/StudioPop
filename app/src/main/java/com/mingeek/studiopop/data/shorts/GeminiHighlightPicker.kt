package com.mingeek.studiopop.data.shorts

import com.mingeek.studiopop.data.caption.Cue
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
 * Gemini 로 SRT 큐 + 주제를 분석해 숏츠용 하이라이트 1구간을 추천.
 *
 * 입력: 전체 영상 길이, SRT 큐, 주제(사용자 입력), 최대 숏츠 길이.
 * 출력: [HighlightSuggestion] — 시작/끝 ms + 상단 훅 텍스트 + 보조 텍스트.
 *
 * 모델 폴백/쿨다운 전략은 [com.mingeek.studiopop.data.thumbnail.GeminiCopywriter] 와 동일.
 */
class GeminiHighlightPicker(
    private val client: OkHttpClient,
    private val moshi: Moshi,
    private val apiKeyProvider: suspend () -> String,
) {

    private val cooldownUntilMs = ConcurrentHashMap<String, Long>()

    @Volatile private var cachedModels: List<String> = emptyList()
    @Volatile private var cacheApiKey: String? = null
    @Volatile private var cacheExpiresAtMs: Long = 0L

    suspend fun pickHighlight(
        cues: List<Cue>,
        topic: String,
        videoDurationMs: Long,
        maxShortMs: Long = 60_000L,
    ): Result<HighlightSuggestion> = withContext(Dispatchers.IO) {
        runCatching {
            require(cues.isNotEmpty()) { "SRT 큐가 비어 있어 하이라이트를 분석할 수 없습니다." }
            val apiKey = apiKeyProvider()
            require(apiKey.isNotBlank()) {
                "GEMINI_API_KEY 가 비어 있습니다. 설정 화면에서 입력하세요."
            }

            val models = listAvailableModels(apiKey)
            require(models.isNotEmpty()) {
                "Gemini 사용 가능 모델 목록이 비어있습니다. 키가 유효한지 확인하세요."
            }

            val prompt = buildPrompt(cues, topic, videoDurationMs, maxShortMs)
            val attempts = mutableListOf<String>()

            for (model in models) {
                val now = System.currentTimeMillis()
                val until = cooldownUntilMs[model] ?: 0L
                if (until > now) {
                    attempts += "$model (쿨다운 ${(until - now) / 1000}s 남음)"
                    continue
                }

                when (val res = callModel(model, apiKey, prompt)) {
                    is ModelResult.Success -> return@runCatching clampToRange(
                        res.suggestion, videoDurationMs, maxShortMs,
                    )
                    is ModelResult.Retryable -> {
                        cooldownUntilMs[model] =
                            System.currentTimeMillis() + res.cooldownMs
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

    /**
     * 모델이 반환한 범위가 영상 길이를 벗어나거나 maxShortMs 보다 길면 안전하게 잘라 맞춤.
     * 너무 짧은 경우(5초 미만) 는 뒤로 확장해 최소 5초 보장.
     */
    private fun clampToRange(
        s: HighlightSuggestion,
        videoDurationMs: Long,
        maxShortMs: Long,
    ): HighlightSuggestion {
        val start = s.startMs.coerceIn(0L, (videoDurationMs - 1000L).coerceAtLeast(0L))
        val rawEnd = s.endMs.coerceAtMost(videoDurationMs)
        val end = maxOf(rawEnd, start + 5_000L)
            .coerceAtMost(videoDurationMs)
            .coerceAtMost(start + maxShortMs)
        return s.copy(startMs = start, endMs = end)
    }

    // --- 모델 발견 (Copywriter 패턴 재사용) --------------------------------

    private fun listAvailableModels(apiKey: String): List<String> {
        val now = System.currentTimeMillis()
        if (cachedModels.isNotEmpty() && cacheApiKey == apiKey && cacheExpiresAtMs > now) {
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

            parsed.models.orEmpty()
                .asSequence()
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
            ?.groupValues?.getOrNull(1)
            ?.toDoubleOrNull() ?: 0.0

    private fun tierScore(name: String): Int = when {
        name.contains("pro") -> 3
        name.contains("flash-lite") -> 1
        name.contains("flash") -> 2
        else -> 0
    }

    private fun stabilityPenalty(name: String): Int =
        if (name.contains("exp") || name.contains("preview")) 1 else 0

    // --- 호출 --------------------------------------------------------------

    private sealed class ModelResult {
        data class Success(val suggestion: HighlightSuggestion) : ModelResult()
        data class Retryable(val reason: String, val cooldownMs: Long) : ModelResult()
    }

    private fun callModel(model: String, apiKey: String, prompt: String): ModelResult {
        val body = GenerateContentRequest(
            contents = listOf(Content(role = "user", parts = listOf(Part(text = prompt)))),
            generationConfig = GenerationConfig(
                maxOutputTokens = 800,
                temperature = 0.6f,
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
                }.getOrNull()
                if (parsed == null) {
                    return@use ModelResult.Retryable("응답 파싱 실패", SHORT_COOLDOWN_MS)
                }
                val text = parsed.candidates
                    ?.firstOrNull()
                    ?.content
                    ?.parts
                    ?.firstNotNullOfOrNull { it.text }
                    .orEmpty()
                val suggestion = parseSuggestion(text)
                if (suggestion == null) {
                    ModelResult.Retryable("빈/파싱 불가 응답", SHORT_COOLDOWN_MS)
                } else {
                    ModelResult.Success(suggestion)
                }
            }
        } catch (e: Exception) {
            ModelResult.Retryable("예외: ${e.message?.take(80) ?: e.javaClass.simpleName}", SHORT_COOLDOWN_MS)
        }
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
            reason = "HTTP $code${if (quotaHint) " (quota)" else ""}",
            cooldownMs = cooldownMs,
        )
    }

    private fun parseSuggestion(text: String): HighlightSuggestion? {
        if (text.isBlank()) return null
        val cleaned = text.trim()
            .removePrefix("```json").removePrefix("```")
            .removeSuffix("```")
            .trim()
        return runCatching {
            moshi.adapter(HighlightSuggestion::class.java).fromJson(cleaned)
        }.getOrNull()?.takeIf { it.endMs > it.startMs }
    }

    private fun buildPrompt(
        cues: List<Cue>,
        topic: String,
        videoDurationMs: Long,
        maxShortMs: Long,
    ): String {
        val srtBlock = cues.joinToString("\n") { c ->
            "[${formatMs(c.startMs)}→${formatMs(c.endMs)}] ${c.text.replace("\n", " ")}"
        }
        return """
            당신은 한국 유튜브 숏츠 전문 편집자입니다. 아래 긴 영상의 자막을 읽고,
            60초 이하로 잘랐을 때 가장 바이럴 포텐셜이 높은 한 구간을 골라주세요.

            영상 총 길이: ${formatMs(videoDurationMs)} (${videoDurationMs}ms)
            숏츠 최대 길이: ${maxShortMs}ms
            주제/분위기 힌트: $topic

            자막 (cue 단위, 원본 영상 시각):
            $srtBlock

            출력 규칙:
            - 반드시 단일 JSON 객체. 설명/마크다운/코드펜스 금지.
            - 스키마:
              {
                "startMs": 시작 ms (정수),
                "endMs":   끝 ms (정수),
                "hookText": "화면 상단에 띄울 8~15자 훅 한국어",
                "subText":  "보조 텍스트 (없으면 빈 문자열)",
                "reason":   "왜 이 구간을 골랐는지 한 줄"
              }
            - startMs/endMs 는 cue 경계 근처로 잡아 어색한 컷 방지.
            - endMs - startMs 는 10000 이상, ${maxShortMs} 이하.
            - hookText 는 짧고 강렬하게. 과한 낚시성 금지.
        """.trimIndent()
    }

    private fun formatMs(ms: Long): String {
        val totalSec = ms / 1000
        val m = totalSec / 60
        val s = totalSec % 60
        val rem = ms % 1000
        return "%02d:%02d.%03d".format(m, s, rem)
    }

    // --- 외부 노출 응답 --------------------------------------------------

    @JsonClass(generateAdapter = true)
    data class HighlightSuggestion(
        val startMs: Long = 0L,
        val endMs: Long = 0L,
        val hookText: String? = null,
        val subText: String? = null,
        val reason: String? = null,
    )

    // --- 내부 JSON 모델 --------------------------------------------------

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
