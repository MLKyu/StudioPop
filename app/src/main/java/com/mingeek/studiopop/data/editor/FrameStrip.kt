package com.mingeek.studiopop.data.editor

import android.content.Context
import android.graphics.Bitmap
import android.media.MediaMetadataRetriever
import android.net.Uri
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * 타임라인 썸네일 스트립을 생성.
 * 영상 전체 길이를 균등 구간으로 나눠 [count] 개 프레임을 가져와 낮은 해상도 Bitmap 리스트로 반환.
 *
 * 결과는 호출부에서 캐시해 재사용하세요. 한 번 생성에 수백 ms ~ 수 초 소요.
 */
class FrameStripGenerator(private val context: Context) {

    suspend fun generate(
        uri: Uri,
        durationMs: Long,
        count: Int = DEFAULT_FRAME_COUNT,
        targetWidthPx: Int = 120,
    ): List<Bitmap> = withContext(Dispatchers.IO) {
        if (durationMs <= 0 || count <= 0) return@withContext emptyList()

        val retriever = MediaMetadataRetriever()
        val frames = mutableListOf<Bitmap>()
        try {
            retriever.setDataSource(context, uri)

            val step = durationMs / count
            for (i in 0 until count) {
                val t = (i * step + step / 2).coerceIn(0L, durationMs - 1)
                val bmp = retriever.getScaledFrameAtTime(
                    t * 1000,
                    MediaMetadataRetriever.OPTION_CLOSEST_SYNC,
                    targetWidthPx,
                    (targetWidthPx * 9) / 16,
                ) ?: retriever.getFrameAtTime(
                    t * 1000,
                    MediaMetadataRetriever.OPTION_CLOSEST_SYNC,
                )
                if (bmp != null) frames += bmp
            }
        } catch (_: Exception) {
            // 부분 추출 허용
        } finally {
            runCatching { retriever.release() }
        }
        frames
    }

    companion object {
        const val DEFAULT_FRAME_COUNT = 30
    }
}
