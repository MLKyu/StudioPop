package com.mingeek.studiopop.data.ai

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
 * R6: Gemini 멀티모달로 영상의 의미적 톤/무드를 추정. [com.mingeek.studiopop.data.ai.ToneEstimator]
 * 의 4축 수치 휴리스틱을 보완하는 사람이 읽을 수 있는 무드 라벨 + LUT/테마 추천.
 *
 * 설계는 [com.mingeek.studiopop.data.thumbnail.GeminiThumbnailAdvisor] 와 동일 — ListModels
 * 동적 발견 + 1시간 캐시, 품질 휴리스틱 정렬, 429/503 → 다음 모델, 빈 응답 → Retryable.
 *
 * 무료 tier 토큰 절약: 키프레임 최대 3장 (호출 측에서 균등 샘플) + JPEG quality 80.
 */
class GeminiToneAnalyzer(
    private val client: OkHttpClient,
    private val moshi: Moshi,
    private val apiKeyProvider: suspend () -> String,
) {

    private val cooldownUntilMs = ConcurrentHashMap<String, Long>()

    @Volatile private var cachedModels: List<String> = emptyList()
    @Volatile private var cacheApiKey: String? = null
    @Volatile private var cacheExpiresAtMs: Long = 0L

    suspend fun analyze(
        keyframes: List<Bitmap>,
        topic: String? = null,
    ): Result<AiToneAnalysis> = withContext(Dispatchers.IO) {
        runCatching {
            require(keyframes.isNotEmpty()) { "keyframes 가 비어 있어 톤 분석 불가" }
            val apiKey = apiKeyProvider()
            require(apiKey.isNotBlank()) {
                "GEMINI_API_KEY 가 비어 있습니다. 설정 화면에서 입력하세요."
            }

            val models = listVisionModels(apiKey)
            require(models.isNotEmpty()) {
                "Gemini vision 가능 모델 목록이 비어있습니다. 키가 유효한지 확인하세요."
            }

            // 토큰 절약 — 최대 3장만 전송 (균등 샘플)
            val sample = sampleEvenly(keyframes, MAX_FRAMES)
            val base64s = sample.map { encodeJpegBase64(it) }
            val prompt = buildPrompt(topic)
            val attempts = mutableListOf<String>()

            for (model in models) {
                val now = System.currentTimeMillis()
                val until = cooldownUntilMs[model] ?: 0L
                if (until > now) {
                    attempts += "$model (쿨다운 ${(until - now) / 1000}s 남음)"
                    continue
                }
                when (val res = callModel(model, apiKey, base64s, prompt)) {
                    is ModelResult.Success -> return@runCatching res.analysis
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

    private fun <T> sampleEvenly(list: List<T>, n: Int): List<T> {
        if (list.size <= n) return list
        val step = list.size.toDouble() / n
        return List(n) { i -> list[(i * step).toInt().coerceAtMost(list.size - 1)] }
    }

    // --- 모델 발견 (GeminiThumbnailAdvisor 와 동일 정책) ---------------------

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
        data class Success(val analysis: AiToneAnalysis) : ModelResult()
        data class Retryable(val reason: String, val cooldownMs: Long) : ModelResult()
    }

    private fun callModel(
        model: String,
        apiKey: String,
        imageBase64s: List<String>,
        prompt: String,
    ): ModelResult {
        val parts = buildList<Part> {
            for (b64 in imageBase64s) {
                add(Part(inlineData = InlineData(mimeType = "image/jpeg", data = b64)))
            }
            add(Part(text = prompt))
        }
        val body = GenerateContentRequest(
            contents = listOf(Content(role = "user", parts = parts)),
            generationConfig = GenerationConfig(
                maxOutputTokens = 600,
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
                val analysis = parseToneAnalysis(text)
                if (analysis == null) {
                    ModelResult.Retryable("빈/파싱 불가 응답", SHORT_COOLDOWN_MS)
                } else {
                    ModelResult.Success(analysis)
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

    private fun parseToneAnalysis(text: String): AiToneAnalysis? {
        if (text.isBlank()) return null
        val cleaned = text.trim()
            .removePrefix("```json").removePrefix("```")
            .removeSuffix("```")
            .trim()
        val raw = runCatching {
            moshi.adapter(ToneAnalysisResponse::class.java).fromJson(cleaned)
        }.getOrNull() ?: return null
        // 빈 mood + 빈 descriptors + 빈 reasoning 이면 의미 없는 응답으로 간주
        val mood = raw.mood?.trim().orEmpty()
        val descriptors = raw.descriptors.orEmpty().mapNotNull { it?.trim()?.takeIf { s -> s.isNotEmpty() } }
        val reasoning = raw.reasoning?.trim().orEmpty()
        if (mood.isBlank() && descriptors.isEmpty() && reasoning.isBlank()) return null
        // recommendedLutId / themeId 는 화이트리스트 — 알 수 없는 id 면 null 로 떨어뜨림
        val lut = raw.recommendedLutId?.trim()?.takeIf { it in ALLOWED_LUT_IDS }
        val theme = raw.recommendedThemeId?.trim()?.takeIf { it in ALLOWED_THEME_IDS }
        return AiToneAnalysis(
            mood = mood,
            descriptors = descriptors,
            recommendedLutId = lut,
            recommendedThemeId = theme,
            reasoning = reasoning.take(120),
        )
    }

    // --- 프롬프트 ---------------------------------------------------------

    private fun buildPrompt(topic: String?): String {
        val topicLine = if (topic.isNullOrBlank()) ""
            else "- 영상 주제 힌트: $topic\n"
        return """
            첨부된 ${MAX_FRAMES}장 이내의 이미지는 한 영상의 균등 간격 키프레임이다. 영상 전체의
            톤/무드를 한국 유튜브 컨텍스트에서 판단하라. 짧고 구체적으로.

            ${topicLine}허용 LUT id (이외 추천 금지): ${ALLOWED_LUT_IDS.joinToString(", ")}
            허용 테마 id (이외 추천 금지): ${ALLOWED_THEME_IDS.joinToString(", ")}

            JSON 한 객체로만 답하라. 스키마:
            {
              "mood": "energetic|calm|dramatic|playful|tense|warm|cool|mysterious",
              "descriptors": ["밝은", "역동적인"],
              "recommendedLutId": "lut.cinematic 또는 null",
              "recommendedThemeId": "theme.vlog 또는 null",
              "reasoning": "한 줄 한국어, 80자 이내"
            }

            규칙:
            - mood 는 한 단어, 위 8개 중 하나.
            - descriptors 는 한국어 형용사 0~5개.
            - recommendedLutId/ThemeId 는 위 화이트리스트 외 금지 — 적합 없으면 null.
            - JSON 객체만, 다른 설명/문장/코드 펜스 금지.
        """.trimIndent()
    }

    private fun encodeJpegBase64(bitmap: Bitmap): String {
        val baos = ByteArrayOutputStream()
        bitmap.compress(Bitmap.CompressFormat.JPEG, 80, baos)
        return Base64.encodeToString(baos.toByteArray(), Base64.NO_WRAP)
    }

    // --- 내부 JSON 모델 ---------------------------------------------------

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

    @JsonClass(generateAdapter = true)
    internal data class ToneAnalysisResponse(
        val mood: String? = null,
        val descriptors: List<String?>? = null,
        val recommendedLutId: String? = null,
        val recommendedThemeId: String? = null,
        val reasoning: String? = null,
    )

    companion object {
        private const val BASE_URL = "https://generativelanguage.googleapis.com/v1beta/"
        private const val SHORT_COOLDOWN_MS = 30_000L
        private const val LONG_COOLDOWN_MS = 10 * 60_000L
        private const val MODEL_CACHE_TTL_MS = 60 * 60_000L
        private const val MAX_FRAMES = 3

        // BuiltinLuts / BuiltinThemes 의 id 와 동기 — 새 자산 추가 시 같이 갱신.
        private val ALLOWED_LUT_IDS = setOf(
            "lut.cinematic", "lut.vivid", "lut.mono", "lut.vintage", "lut.cool",
        )
        private val ALLOWED_THEME_IDS = setOf(
            "theme.vlog", "theme.mukbang", "theme.review", "theme.game", "theme.gacha",
            "studiopop.default",
        )
    }
}
