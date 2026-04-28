package com.mingeek.studiopop.data.caption

import kotlin.math.sqrt

/**
 * 화자 임베딩(Vosk x-vector ~128차원) 리스트를 화자 라벨(A/B/C/...)로 군집화.
 *
 * 알고리즘 — 온라인 그리디 클러스터링:
 *  1. 첫 벡터로 클러스터 1개 시작 (centroid = 그 벡터, count = 1)
 *  2. 다음 벡터마다 모든 클러스터 centroid 와 코사인 유사도 계산
 *     - 최대 sim ≥ [threshold] 인 클러스터에 합류 → centroid 를 (count*c + v)/(count+1) 로 갱신
 *     - 모두 미만이면 새 클러스터 생성
 *  3. 라벨은 등장 순서대로 'A','B','C',... ([MAX_LABELS] 초과는 'Z' 로 캡)
 *
 * 정밀도는 단순 — k-means/AHC 를 쓰면 더 안정적이지만, 짧은 영상(보통 화자 1~3명)엔 충분.
 * 임계값 0.85 는 vosk-model-spk-0.4 의 일반적 cosine 분포 기반 (저자 권장 영역).
 */
object SpeakerClusterer {

    fun cluster(embeddings: List<FloatArray?>, threshold: Float = 0.85f): List<String?> {
        if (embeddings.isEmpty()) return emptyList()
        val centroids = mutableListOf<FloatArray>()
        val counts = mutableListOf<Int>()
        val labels = MutableList<String?>(embeddings.size) { null }

        for ((i, vec) in embeddings.withIndex()) {
            if (vec == null || vec.isEmpty()) continue
            val v = normalize(vec)
            // 가장 유사한 centroid 찾기
            var bestIdx = -1
            var bestSim = Float.NEGATIVE_INFINITY
            for ((cIdx, c) in centroids.withIndex()) {
                val sim = dot(v, c)
                if (sim > bestSim) {
                    bestSim = sim
                    bestIdx = cIdx
                }
            }
            if (bestIdx >= 0 && bestSim >= threshold) {
                // 합류 — running mean 으로 centroid 갱신 후 재정규화
                val n = counts[bestIdx]
                val updated = FloatArray(v.size) { k -> (centroids[bestIdx][k] * n + v[k]) / (n + 1) }
                centroids[bestIdx] = normalize(updated)
                counts[bestIdx] = n + 1
                labels[i] = indexToLabel(bestIdx)
            } else {
                centroids += v
                counts += 1
                labels[i] = indexToLabel(centroids.lastIndex)
            }
        }
        return labels
    }

    private fun normalize(v: FloatArray): FloatArray {
        var sumSq = 0f
        for (x in v) sumSq += x * x
        val norm = sqrt(sumSq.toDouble()).toFloat()
        if (norm <= 1e-6f) return v.copyOf()
        return FloatArray(v.size) { i -> v[i] / norm }
    }

    private fun dot(a: FloatArray, b: FloatArray): Float {
        val n = minOf(a.size, b.size)
        var s = 0f
        for (i in 0 until n) s += a[i] * b[i]
        return s
    }

    private fun indexToLabel(idx: Int): String {
        if (idx >= MAX_LABELS - 1) return ('A' + (MAX_LABELS - 1)).toString()
        return ('A' + idx).toString()
    }

    private const val MAX_LABELS = 26 // A..Z
}
