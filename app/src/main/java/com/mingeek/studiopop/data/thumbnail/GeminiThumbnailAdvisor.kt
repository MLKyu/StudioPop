package com.mingeek.studiopop.data.thumbnail

import android.graphics.Bitmap
import android.util.Base64
import com.squareup.moshi.JsonClass
import com.squareup.moshi.Moshi
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.io.ByteArrayOutputStream
import java.util.concurrent.ConcurrentHashMap

/**
 * Gemini multimodal 어드바이저: 프레임 이미지 + 주제를 보내 N개의 시각 변형 추천을 받는다.
 *
 * 응답은 JSON 배열로 받아 [VariantSuggestion] 으로 파싱. ThumbnailVariant 변환은 호출부([VariantGenerator]) 가 담당.
 *
 * [GeminiCopywriter] 와 동일한 폴백 전략:
 * - ListModels 로 동적 발견 (1시간 캐시)
 * - 품질 heuristic 정렬, 429/503 → 다음 모델
 * - 빈 응답/파싱 실패 → Retryable
 */
class GeminiThumbnailAdvisor(
    private val client: OkHttpClient,
    private val moshi: Moshi,
    private val apiKeyProvider: suspend () -> String,
) {

    private val cooldownUntilMs = ConcurrentHashMap<String, Long>()

    @Volatile private var cachedModels: List<String> = emptyList()
    @Volatile private var cacheApiKey: String? = null
    @Volatile private var cacheExpiresAtMs: Long = 0L

    suspend fun suggestVariants(
        frame: Bitmap,
        topic: String,
        count: Int = 5,
    ): Result<List<VariantSuggestion>> = withContext(Dispatchers.IO) {
        runCatching {
            val apiKey = apiKeyProvider()
            require(apiKey.isNotBlank()) {
                "GEMINI_API_KEY 가 비어 있습니다. 설정 화면에서 입력하세요."
            }

            val models = listVisionModels(apiKey)
            require(models.isNotEmpty()) {
                "Gemini vision 가능 모델 목록이 비어있습니다. 키가 유효한지 확인하세요."
            }

            val base64 = encodeJpegBase64(frame)
            val prompt = buildPrompt(topic, count)
            val attempts = mutableListOf<String>()

            for (model in models) {
                val now = System.currentTimeMillis()
                val until = cooldownUntilMs[model] ?: 0L
                if (until > now) {
                    attempts += "$model (쿨다운 ${(until - now) / 1000}s 남음)"
                    continue
                }

                when (val res = callModel(model, apiKey, base64, prompt, count)) {
                    is ModelResult.Success -> return@runCatching res.suggestions
                    is ModelResult.Retryable -> {
                        cooldownUntilMs[model] = System.currentTimeMillis() + res.cooldownMs
                        attempts += "$model: ${res.reason}"
                    }
                }
            }
            error(
                "사용 가능한 모든 Gemini 모델 응답 실패. 잠시 후 재시도하세요.\n" +
                    attempts.joinToString("\n") { "- $it" }
            )
        }
    }

    // --- 모델 발견 + 정렬 (vision 지원만) -------------------------------

    private fun listVisionModels(apiKey: String): List<String> {
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
                .filter { isLikelyVisionModel(it) }
                .distinct()
                .toList()
                .sortedWith(modelQualityComparator())
        }
    } catch (_: Exception) {
        emptyList()
    }

    /** Gemini 1.5+ / 2.0+ 는 모두 vision. lite 도 vision 지원. text-only/embedding 만 제외. */
    private fun isLikelyVisionModel(name: String): Boolean =
        !name.contains("embedding") && !name.contains("aqa") && !name.contains("tts")

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

    // --- 호출 ---------------------------------------------------------------

    private sealed class ModelResult {
        data class Success(val suggestions: List<VariantSuggestion>) : ModelResult()
        data class Retryable(val reason: String, val cooldownMs: Long) : ModelResult()
    }

    private fun callModel(
        model: String,
        apiKey: String,
        imageBase64: String,
        prompt: String,
        count: Int,
    ): ModelResult {
        val body = GenerateContentRequest(
            contents = listOf(
                Content(
                    role = "user",
                    parts = listOf(
                        Part(inlineData = InlineData(mimeType = "image/jpeg", data = imageBase64)),
                        Part(text = prompt),
                    ),
                )
            ),
            generationConfig = GenerationConfig(
                maxOutputTokens = 1200,
                temperature = 0.85f,
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
                val suggestions = parseSuggestions(text).take(count)
                if (suggestions.isEmpty()) {
                    ModelResult.Retryable("빈/파싱 불가 응답", SHORT_COOLDOWN_MS)
                } else {
                    ModelResult.Success(suggestions)
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

    /**
     * Gemini 응답에서 JSON 배열을 추출해 [VariantSuggestion] 으로 디코딩.
     * `responseMimeType = application/json` 을 지정해도 모델이 ```json``` 펜스로 감싸 보내는 경우가 있어
     * 양쪽 모두 처리.
     */
    private fun parseSuggestions(text: String): List<VariantSuggestion> {
        if (text.isBlank()) return emptyList()
        val cleaned = text.trim()
            .removePrefix("```json").removePrefix("```")
            .removeSuffix("```")
            .trim()
        return runCatching {
            val type = com.squareup.moshi.Types.newParameterizedType(
                List::class.java, VariantSuggestion::class.java
            )
            val adapter = moshi.adapter<List<VariantSuggestion>>(type)
            adapter.fromJson(cleaned)
        }.getOrNull().orEmpty()
    }

    // --- 프롬프트 + base64 -------------------------------------------------

    private fun buildPrompt(topic: String, count: Int): String =
        """
            첨부된 이미지는 한국 유튜브 영상의 한 장면이다. 이 이미지를 썸네일로 만들 때 클릭률을
            높일 수 있는 시각 구성안을 ${count}개 추천하라.

            영상 주제: $topic

            JSON 배열로만 답하라. 각 항목 스키마:
            {
              "mainText": "8~18자 강렬한 한국어 카피",
              "subText": "선택, 짧은 보조 카피 (없으면 \"\")",
              "mainColorHex": "#FFFFFF 같은 16진 색상 (메인 텍스트)",
              "accentColorHex": "#FFEB00 같은 강조 색",
              "anchor": "TOP_LEFT|TOP_RIGHT|BOTTOM_LEFT|BOTTOM_RIGHT|CENTER",
              "decoration": "NONE|BOX|OUTLINE|ARROW|GLOW",
              "subjectEmphasis": "NONE|BORDER|GLOW",
              "reasoning": "왜 이 구성이 클릭을 유발할지 한 줄 한국어"
            }

            규칙:
            - mainText 는 반드시 한국어, 8~18자.
            - 색은 어두운 배경엔 흰/노랑, 밝은 배경엔 검정/빨강 등 대비 고려.
            - 다양한 anchor 와 decoration 조합으로 추천 (전부 같으면 안 됨).
            - 선정성/낚시성 과도한 표현 금지.
            - JSON 배열만, 다른 설명/문장/코드 펜스 금지.
        """.trimIndent()

    private fun encodeJpegBase64(bitmap: Bitmap): String {
        val baos = ByteArrayOutputStream()
        // JPEG quality 80 — 무료 tier 토큰 절약 + 시각 품질 충분
        bitmap.compress(Bitmap.CompressFormat.JPEG, 80, baos)
        return Base64.encodeToString(baos.toByteArray(), Base64.NO_WRAP)
    }

    // --- 외부 노출 응답 모델 --------------------------------------------

    @JsonClass(generateAdapter = true)
    data class VariantSuggestion(
        val mainText: String? = null,
        val subText: String? = null,
        val mainColorHex: String? = null,
        val accentColorHex: String? = null,
        val anchor: String? = null,
        val decoration: String? = null,
        val subjectEmphasis: String? = null,
        val reasoning: String? = null,
    )

    // --- 내부 JSON 모델 (모두 nullable) ---------------------------------

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
    internal data class Part(
        val text: String? = null,
        val inlineData: InlineData? = null,
    )

    @JsonClass(generateAdapter = true)
    internal data class InlineData(
        val mimeType: String,
        val data: String,
    )

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
