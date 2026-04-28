package com.mingeek.studiopop.data.design

/**
 * 파싱된 IRIDAS Cube 형식 LUT. 1D 또는 3D.
 *
 * - 1D: size N → [r,g,b] 1D 룩업 N 항목. 채널 독립적인 톤 매핑.
 * - 3D: size N → r×g×b 의 trilinear 인터폴레이션을 위한 N×N×N 그리드.
 *
 * 적용 시 입력 RGB ∈ [domainMin, domainMax] 를 [0, size-1] 로 스케일 후 보간.
 * R3 단계에선 데이터만 — 실제 적용은 R3 후반/R4 의 Media3 ColorLut Effect 변환에서.
 */
data class CubeLut(
    val title: String,
    val dimension: Dimension,
    val size: Int,
    val domainMin: FloatArray,
    val domainMax: FloatArray,
    /**
     * 1D: size×3 (r,g,b 순서로 size 개)
     * 3D: size³×3 (b 가 가장 빠른 축, 그 다음 g, 그 다음 r — IRIDAS Cube 표준)
     */
    val data: FloatArray,
) {
    enum class Dimension { ONE_D, THREE_D }

    init {
        val expected = when (dimension) {
            Dimension.ONE_D -> size * 3
            Dimension.THREE_D -> size * size * size * 3
        }
        require(data.size == expected) {
            "CubeLut data size mismatch: expected=$expected actual=${data.size}"
        }
        require(domainMin.size == 3 && domainMax.size == 3) {
            "domainMin/Max must be 3-channel"
        }
    }

    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is CubeLut) return false
        return title == other.title && dimension == other.dimension && size == other.size &&
            domainMin.contentEquals(other.domainMin) &&
            domainMax.contentEquals(other.domainMax) &&
            data.contentEquals(other.data)
    }
    override fun hashCode(): Int {
        var h = title.hashCode()
        h = 31 * h + dimension.hashCode()
        h = 31 * h + size
        h = 31 * h + domainMin.contentHashCode()
        h = 31 * h + domainMax.contentHashCode()
        h = 31 * h + data.contentHashCode()
        return h
    }
}
