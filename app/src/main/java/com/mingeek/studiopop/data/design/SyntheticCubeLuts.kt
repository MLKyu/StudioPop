package com.mingeek.studiopop.data.design

import kotlin.math.max
import kotlin.math.min

/**
 * .cube 파일 자산 없이 코드로 5종 LUT 데이터를 합성. 무료 / 외부 의존 0 — 사용자가 자산
 * 라이선스 결정하지 않아도 즉시 시각적 효과 적용 가능.
 *
 * 각 LUT 은 16x16x16 그리드 + 3채널(R,G,B) = 12,288 floats. 입력 RGB ∈ [0,1] 을 변환된 RGB 로
 * 매핑. ThumbnailComposer / Media3 ColorLut effect 양쪽이 같은 [CubeLut] 형식을 받음.
 *
 * 5종 정의 (대략적 톤):
 *  - Cinematic: 따뜻함 + 어두운 그림자 + 부드러운 하이라이트 (영화 컬러)
 *  - Vivid: 채도 부스트 + 컨트라스트 강화 (숏츠/뽑기/리액션)
 *  - Mono: 채도 0, 밝기 = 휘도 보존
 *  - Vintage: 빛바램 + 약간의 sepia + 낮은 컨트라스트
 *  - Cool: 차가운 푸른빛 + 약간의 채도 감소
 */
object SyntheticCubeLuts {

    private const val SIZE = 16

    val CINEMATIC: CubeLut by lazy {
        build("Cinematic Synthetic") { r, g, b ->
            // 어두운 그림자 lift + 따뜻함 (R 약간 +, B 약간 -) + 약간의 contrast S-curve
            var nr = r + 0.04f
            var ng = g + 0.01f
            var nb = b - 0.04f
            // 그림자/하이라이트 부드럽게 — sigmoid 같은 곡선
            nr = sCurve(nr, 0.85f)
            ng = sCurve(ng, 0.9f)
            nb = sCurve(nb, 0.95f)
            Triple(nr, ng, nb)
        }
    }

    val VIVID: CubeLut by lazy {
        build("Vivid Synthetic") { r, g, b ->
            // 채도 부스트: 휘도 기준으로 거리 확대
            val lum = 0.299f * r + 0.587f * g + 0.114f * b
            val sat = 1.4f
            val nr = lum + (r - lum) * sat
            val ng = lum + (g - lum) * sat
            val nb = lum + (b - lum) * sat
            // 컨트라스트 (S 커브)
            Triple(sCurve(nr, 0.65f), sCurve(ng, 0.65f), sCurve(nb, 0.65f))
        }
    }

    val MONO: CubeLut by lazy {
        build("Mono Synthetic") { r, g, b ->
            // 휘도만 사용 — Rec.601 가중치
            val lum = 0.299f * r + 0.587f * g + 0.114f * b
            // 약간의 컨트라스트 부스트
            val out = sCurve(lum, 0.7f)
            Triple(out, out, out)
        }
    }

    val VINTAGE: CubeLut by lazy {
        build("Vintage Synthetic") { r, g, b ->
            // 빛바램: 컨트라스트 낮추고 sepia tint
            val lum = 0.299f * r + 0.587f * g + 0.114f * b
            val faded = lum * 0.6f + 0.2f // 톤 압축
            // sepia 색조 — R 강조, B 감쇠
            val nr = faded + 0.10f
            val ng = faded + 0.04f
            val nb = faded - 0.05f
            // 원본 색상도 살짝 섞기
            Triple(
                blend(r, nr, 0.75f),
                blend(g, ng, 0.75f),
                blend(b, nb, 0.75f),
            )
        }
    }

    val COOL: CubeLut by lazy {
        build("Cool Synthetic") { r, g, b ->
            // 푸른빛 시프트 + 채도 약간 감소
            val lum = 0.299f * r + 0.587f * g + 0.114f * b
            val sat = 0.85f
            var nr = lum + (r - lum) * sat - 0.03f
            var ng = lum + (g - lum) * sat
            var nb = lum + (b - lum) * sat + 0.05f
            Triple(nr, ng, nb)
        }
    }

    private fun build(
        title: String,
        transform: (Float, Float, Float) -> Triple<Float, Float, Float>,
    ): CubeLut {
        val data = FloatArray(SIZE * SIZE * SIZE * 3)
        var idx = 0
        // IRIDAS Cube 표준: b 가 가장 빠른 축, 그 다음 g, 그 다음 r
        for (rIdx in 0 until SIZE) {
            for (gIdx in 0 until SIZE) {
                for (bIdx in 0 until SIZE) {
                    val r = rIdx / (SIZE - 1f)
                    val g = gIdx / (SIZE - 1f)
                    val b = bIdx / (SIZE - 1f)
                    val (nr, ng, nb) = transform(r, g, b)
                    data[idx++] = nr.coerceIn(0f, 1f)
                    data[idx++] = ng.coerceIn(0f, 1f)
                    data[idx++] = nb.coerceIn(0f, 1f)
                }
            }
        }
        return CubeLut(
            title = title,
            dimension = CubeLut.Dimension.THREE_D,
            size = SIZE,
            domainMin = floatArrayOf(0f, 0f, 0f),
            domainMax = floatArrayOf(1f, 1f, 1f),
            data = data,
        )
    }

    /**
     * S-curve — 0..1 입력에 대해 [strength] 만큼 컨트라스트 부스트. strength 1.0 = 항등,
     * 0.5 = 강한 부스트, 0.7 = 적당.
     */
    private fun sCurve(x: Float, strength: Float): Float {
        val t = x.coerceIn(0f, 1f)
        // 시그모이드 근사: (t - 0.5) 를 0 중심으로 확대 후 0..1 로 매핑
        val s = strength.coerceIn(0.4f, 1.0f)
        val expanded = (t - 0.5f) / s + 0.5f
        return expanded.coerceIn(0f, 1f)
    }

    private fun blend(a: Float, b: Float, mix: Float): Float =
        a * (1f - mix) + b * mix

    /** id → CubeLut 매핑 — BuiltinLuts 의 id 와 일치. */
    fun forId(lutId: String): CubeLut? = when (lutId) {
        BuiltinLuts.CINEMATIC.id -> CINEMATIC
        BuiltinLuts.VIVID.id -> VIVID
        BuiltinLuts.MONO.id -> MONO
        BuiltinLuts.VINTAGE.id -> VINTAGE
        BuiltinLuts.COOL.id -> COOL
        else -> null
    }
}
