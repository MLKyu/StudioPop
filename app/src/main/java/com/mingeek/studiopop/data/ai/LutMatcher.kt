package com.mingeek.studiopop.data.ai

import com.mingeek.studiopop.data.design.BuiltinLuts
import com.mingeek.studiopop.data.design.LutAsset

/**
 * [ToneEstimate] → [LutAsset] 매핑 휴리스틱. R5a 단계의 단순 분기 — Gemini Vision 정밀 분석으로
 * 교체할 여지(R6+).
 *
 * 우선순위:
 *  1. 채도가 매우 낮음 (saturation < -0.6) → MONO
 *  2. 어둡고 따뜻함 (brightness < -0.1 && warmth > 0.05) → CINEMATIC
 *  3. 차가움 (warmth < -0.15) → COOL
 *  4. 밝고 채도 높음 (brightness > 0.05 && saturation > 0.0) → VIVID
 *  5. 평탄·중립 톤 (대비 낮음) → VINTAGE
 *  6. 매칭 없으면 null — LUT 적용 권장 안 함
 */
object LutMatcher {

    fun match(tone: ToneEstimate?): LutAsset? {
        if (tone == null) return null

        if (tone.saturation < -0.6f) return BuiltinLuts.MONO
        if (tone.brightness < -0.1f && tone.warmth > 0.05f) return BuiltinLuts.CINEMATIC
        if (tone.warmth < -0.15f) return BuiltinLuts.COOL
        if (tone.brightness > 0.05f && tone.saturation > 0.0f) return BuiltinLuts.VIVID
        if (tone.contrast < -0.2f) return BuiltinLuts.VINTAGE
        return null
    }
}
