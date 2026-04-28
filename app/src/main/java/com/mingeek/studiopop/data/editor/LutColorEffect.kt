package com.mingeek.studiopop.data.editor

import androidx.media3.common.Effect
import androidx.media3.common.util.UnstableApi
import androidx.media3.effect.SingleColorLut
import com.mingeek.studiopop.data.design.CubeLut
import com.mingeek.studiopop.data.design.SyntheticCubeLuts

/**
 * [CubeLut] (IRIDAS 데이터 레이아웃) → Media3 [SingleColorLut] 변환.
 *
 * Media3 의 [SingleColorLut.createFromCube] 는 `cube[B][G][R]` ARGB int 배열을 기대한다.
 * [SyntheticCubeLuts] 가 만드는 데이터는 IRIDAS 표준 — 평탄화된 FloatArray 에서
 * `idx = (rIdx * N + gIdx) * N + bIdx`, RGB 3채널이 인접. 두 좌표계 변환 + intensity 블렌드를
 * 한 번에 처리한다.
 *
 * intensity:
 *  - 1.0  = LUT 효과 그대로
 *  - 0.0  = 원본(=identity LUT) 과 동일
 *  - 그 사이 = 픽셀별 (identity, lutColor) 선형 보간
 * shader 단에서 블렌딩하지 않고 큐브 데이터에서 미리 lerp 해 둠 — Effect 한 개만 추가하면 됨.
 */
@UnstableApi
object LutColorEffect {

    /** themeId/lutId 로부터 LUT Effect 를 만들어준다. id 가 null/미등록이면 null 반환. */
    fun forLutId(lutId: String?, intensity: Float = 1f): Effect? {
        if (lutId.isNullOrBlank()) return null
        val cube = SyntheticCubeLuts.forId(lutId) ?: return null
        return fromCubeLut(cube, intensity)
    }

    fun fromCubeLut(lut: CubeLut, intensity: Float = 1f): Effect {
        require(lut.dimension == CubeLut.Dimension.THREE_D) {
            "Media3 SingleColorLut 은 3D LUT 만 지원 — 1D 는 별도 매핑 필요"
        }
        val n = lut.size
        val mix = intensity.coerceIn(0f, 1f)
        // cube[b][g][r] 형식. Java 의 int[][][] 와 호환되도록 Array<Array<IntArray>> 사용.
        val cube = Array(n) { b ->
            Array(n) { g ->
                IntArray(n) { r ->
                    val base = ((r * n + g) * n + b) * 3
                    val rf = lut.data[base]
                    val gf = lut.data[base + 1]
                    val bf = lut.data[base + 2]
                    // identity 색 = 큐브 격자 위치 그대로 (정규화 0..1)
                    val ir = r / (n - 1f)
                    val ig = g / (n - 1f)
                    val ib = b / (n - 1f)
                    val mr = ir + (rf - ir) * mix
                    val mg = ig + (gf - ig) * mix
                    val mb = ib + (bf - ib) * mix
                    argbFromFloat(mr, mg, mb)
                }
            }
        }
        return SingleColorLut.createFromCube(cube)
    }

    private fun argbFromFloat(r: Float, g: Float, b: Float): Int {
        val ri = (r.coerceIn(0f, 1f) * 255f + 0.5f).toInt()
        val gi = (g.coerceIn(0f, 1f) * 255f + 0.5f).toInt()
        val bi = (b.coerceIn(0f, 1f) * 255f + 0.5f).toInt()
        return (0xFF shl 24) or (ri shl 16) or (gi shl 8) or bi
    }
}
