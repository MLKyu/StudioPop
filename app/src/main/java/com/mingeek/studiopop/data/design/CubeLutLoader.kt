package com.mingeek.studiopop.data.design

import java.io.BufferedReader
import java.io.InputStream
import java.io.InputStreamReader

/**
 * IRIDAS Cube 형식 LUT 파서.
 *
 * 지원 키워드:
 *  - TITLE "..."         (옵셔널)
 *  - LUT_1D_SIZE N       또는 LUT_3D_SIZE N
 *  - DOMAIN_MIN r g b    (옵셔널, 기본 0 0 0)
 *  - DOMAIN_MAX r g b    (옵셔널, 기본 1 1 1)
 *  - 데이터: 한 줄당 r g b (size 또는 size³ 줄)
 *
 * `#` 로 시작하는 줄은 주석. 여러 공백/탭 무시.
 */
object CubeLutLoader {

    fun loadFromInputStream(stream: InputStream): Result<CubeLut> = runCatching {
        BufferedReader(InputStreamReader(stream, Charsets.UTF_8)).use { reader ->
            parse(reader.lineSequence())
        }
    }

    fun loadFromString(content: String): Result<CubeLut> = runCatching {
        parse(content.lineSequence())
    }

    private fun parse(lines: Sequence<String>): CubeLut {
        var title: String = ""
        var dimension: CubeLut.Dimension? = null
        var size: Int = 0
        var domainMin = floatArrayOf(0f, 0f, 0f)
        var domainMax = floatArrayOf(1f, 1f, 1f)
        val data = ArrayList<Float>(0)

        for (raw in lines) {
            val line = raw.substringBefore('#').trim()
            if (line.isEmpty()) continue
            val tokens = line.split(Regex("\\s+"))
            when (tokens[0].uppercase()) {
                "TITLE" -> {
                    // TITLE "..." — 따옴표 안 내용 추출
                    val quoteStart = line.indexOf('"')
                    val quoteEnd = line.lastIndexOf('"')
                    title = if (quoteStart in 0 until quoteEnd) {
                        line.substring(quoteStart + 1, quoteEnd)
                    } else tokens.drop(1).joinToString(" ")
                }
                "LUT_1D_SIZE" -> {
                    require(dimension == null) { "Duplicate dimension declaration" }
                    dimension = CubeLut.Dimension.ONE_D
                    size = tokens[1].toInt()
                }
                "LUT_3D_SIZE" -> {
                    require(dimension == null) { "Duplicate dimension declaration" }
                    dimension = CubeLut.Dimension.THREE_D
                    size = tokens[1].toInt()
                }
                "DOMAIN_MIN" -> {
                    require(tokens.size >= 4) { "DOMAIN_MIN needs 3 values" }
                    domainMin = floatArrayOf(
                        tokens[1].toFloat(), tokens[2].toFloat(), tokens[3].toFloat(),
                    )
                }
                "DOMAIN_MAX" -> {
                    require(tokens.size >= 4) { "DOMAIN_MAX needs 3 values" }
                    domainMax = floatArrayOf(
                        tokens[1].toFloat(), tokens[2].toFloat(), tokens[3].toFloat(),
                    )
                }
                else -> {
                    // 데이터 줄: 3 float 값 또는 무시
                    if (tokens.size >= 3) {
                        val r = tokens[0].toFloatOrNull()
                        val g = tokens[1].toFloatOrNull()
                        val b = tokens[2].toFloatOrNull()
                        if (r != null && g != null && b != null) {
                            data += r; data += g; data += b
                        }
                    }
                }
            }
        }

        require(dimension != null) { "Missing LUT_1D_SIZE or LUT_3D_SIZE" }
        require(size > 0) { "Invalid LUT size: $size" }

        val expected = when (dimension!!) {
            CubeLut.Dimension.ONE_D -> size * 3
            CubeLut.Dimension.THREE_D -> size * size * size * 3
        }
        require(data.size == expected) {
            "Data row count mismatch: expected=$expected actual=${data.size / 3}"
        }

        return CubeLut(
            title = title,
            dimension = dimension!!,
            size = size,
            domainMin = domainMin,
            domainMax = domainMax,
            data = data.toFloatArray(),
        )
    }
}
