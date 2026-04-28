package com.mingeek.studiopop.data.audio

import android.net.Uri
import java.util.concurrent.ConcurrentHashMap

/**
 * 영상별 분석 결과 캐시. 비트 검출은 비싼 작업이라 한 영상당 한 번만 수행해야 한다.
 *
 * R1 단계: 메모리만 (앱 실행 중에만 유효). R4 에서 Room 영속화로 교체 — 분석은 비싸지만
 * 결과는 작아서 영구 캐시 비용이 낮다.
 */
class AnalysisCache {

    private val byUri = ConcurrentHashMap<String, AudioAnalysis>()

    fun get(uri: Uri): AudioAnalysis? = byUri[uri.toString()]

    fun put(uri: Uri, analysis: AudioAnalysis) {
        byUri[uri.toString()] = analysis
    }

    fun invalidate(uri: Uri) {
        byUri.remove(uri.toString())
    }

    fun clear() {
        byUri.clear()
    }
}
