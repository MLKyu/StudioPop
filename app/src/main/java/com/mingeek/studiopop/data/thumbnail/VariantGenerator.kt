package com.mingeek.studiopop.data.thumbnail

import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.Rect
import com.mingeek.studiopop.data.thumbnail.GeminiThumbnailAdvisor.VariantSuggestion

/**
 * Gemini 추천 + 얼굴 감지 결과를 합쳐 최종 [ThumbnailVariant] 리스트 생성.
 *
 * Gemini 가 만든 "카피 + 색 + 위치 + 데코" 에 로컬에서 감지한 얼굴 박스를
 * 섞어 일부 변형은 얼굴 줌 + subject 강조를 자동 적용. 같은 추천이라도
 * 얼굴 줌 변형 / 전체 프레임 변형 2종으로 확장해 사용자 선택지를 늘림.
 */
class VariantGenerator(
    private val advisor: GeminiThumbnailAdvisor,
    private val faceDetector: FaceDetector,
) {

    suspend fun generate(
        frame: Bitmap,
        topic: String,
        baseCount: Int = 4,
    ): Result<List<ThumbnailVariant>> {
        val faces = faceDetector.detect(frame)
        val primaryFace = faces.firstOrNull()

        return advisor.suggestVariants(frame, topic, baseCount).map { suggestions ->
            val variants = mutableListOf<ThumbnailVariant>()

            suggestions.forEachIndexed { idx, s ->
                val base = suggestionToVariant(s)
                variants += base

                // 첫 2개 추천엔 얼굴 줌 + 강조 변형도 같이 생성 (얼굴 있을 때만)
                if (primaryFace != null && idx < 2) {
                    variants += base.copy(
                        id = java.util.UUID.randomUUID().toString(),
                        subjectCrop = primaryFace,
                        subjectEmphasis = if (idx == 0) SubjectEmphasis.BORDER else SubjectEmphasis.GLOW,
                        // 줌 변형은 텍스트 크기 살짝 줄여 얼굴이 돋보이게
                        sizeScale = (base.sizeScale * 0.9f).coerceAtLeast(0.7f),
                    )
                }
            }
            // Advisor 가 0 개 주거나 적으면 보조 preset 으로 채움 (아래 [fillPresets])
            fillPresets(variants, topic, primaryFace)
        }
    }

    private fun suggestionToVariant(s: VariantSuggestion): ThumbnailVariant = ThumbnailVariant(
        mainText = s.mainText?.takeIf { it.isNotBlank() } ?: "클릭 유발 카피",
        subText = s.subText.orEmpty(),
        mainColor = parseColorSafe(s.mainColorHex, Color.WHITE),
        accentColor = parseColorSafe(s.accentColorHex, Color.parseColor("#FFEB00")),
        anchor = parseEnumSafe(s.anchor, TextAnchor.BOTTOM_LEFT),
        decoration = parseEnumSafe(s.decoration, DecorationStyle.NONE),
        subjectEmphasis = parseEnumSafe(s.subjectEmphasis, SubjectEmphasis.NONE),
        reasoning = s.reasoning,
    )

    /**
     * Advisor 결과가 부족하면 내장 preset 으로 채워 항상 최소 5개는 보장.
     * Preset 은 anchor / decoration / 색 조합만 다르고 텍스트는 주제 기반 단순 라벨.
     */
    private fun fillPresets(
        current: MutableList<ThumbnailVariant>,
        topic: String,
        primaryFace: Rect?,
    ): List<ThumbnailVariant> {
        val minDesired = 5
        if (current.size >= minDesired) return current

        val fallbackText = topic.ifBlank { "이거 진짜 미쳤다" }.take(18)
        val presets = listOf(
            ThumbnailVariant(
                mainText = fallbackText,
                accentColor = Color.parseColor("#FFEB00"),
                anchor = TextAnchor.BOTTOM_LEFT,
                decoration = DecorationStyle.BOX,
                subjectCrop = primaryFace,
                subjectEmphasis = if (primaryFace != null) SubjectEmphasis.BORDER else SubjectEmphasis.NONE,
            ),
            ThumbnailVariant(
                mainText = fallbackText,
                mainColor = Color.parseColor("#FFFFFF"),
                accentColor = Color.parseColor("#FF1744"),
                anchor = TextAnchor.TOP_RIGHT,
                decoration = DecorationStyle.GLOW,
            ),
            ThumbnailVariant(
                mainText = fallbackText,
                mainColor = Color.parseColor("#FFEB00"),
                accentColor = Color.parseColor("#000000"),
                anchor = TextAnchor.CENTER,
                decoration = DecorationStyle.OUTLINE,
                sizeScale = 1.15f,
            ),
        )
        val need = minDesired - current.size
        current += presets.take(need)
        return current
    }

    private fun parseColorSafe(hex: String?, fallback: Int): Int {
        if (hex.isNullOrBlank()) return fallback
        return runCatching { Color.parseColor(hex.trim()) }.getOrDefault(fallback)
    }

    private inline fun <reified E : Enum<E>> parseEnumSafe(name: String?, fallback: E): E {
        if (name.isNullOrBlank()) return fallback
        return runCatching { enumValueOf<E>(name.trim().uppercase()) }.getOrDefault(fallback)
    }
}
