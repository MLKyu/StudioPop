package com.mingeek.studiopop.data.editor

import android.content.Context
import android.graphics.Bitmap
import android.media.MediaMetadataRetriever
import android.net.Uri
import com.mingeek.studiopop.data.thumbnail.FaceDetector
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * 주어진 영상의 [sourceStartMs, sourceEndMs] 구간을 일정 간격으로 샘플링해
 * 가장 큰 얼굴의 박스를 [MosaicKeyframe] 리스트로 반환.
 *
 * MVP 단순화: 얼굴은 프레임마다 1명(가장 큰 얼굴) 만 추적.
 * 멀티 얼굴은 후속 확장.
 */
class FaceTracker(
    private val context: Context,
    private val faceDetector: FaceDetector,
) {

    /**
     * @param sampleIntervalMs 샘플링 간격 (작을수록 정확도↑ 비용↑). 기본 200ms.
     */
    suspend fun track(
        videoUri: Uri,
        sourceStartMs: Long,
        sourceEndMs: Long,
        sampleIntervalMs: Long = 200L,
    ): List<MosaicKeyframe> = withContext(Dispatchers.IO) {
        val retriever = MediaMetadataRetriever()
        try {
            retriever.setDataSource(context, videoUri)
            val videoWidth = retriever.extractMetadata(
                MediaMetadataRetriever.METADATA_KEY_VIDEO_WIDTH
            )?.toIntOrNull() ?: return@withContext emptyList()
            val videoHeight = retriever.extractMetadata(
                MediaMetadataRetriever.METADATA_KEY_VIDEO_HEIGHT
            )?.toIntOrNull() ?: return@withContext emptyList()
            if (videoWidth <= 0 || videoHeight <= 0) return@withContext emptyList()

            val keyframes = mutableListOf<MosaicKeyframe>()
            var t = sourceStartMs
            while (t <= sourceEndMs) {
                val frame = runCatching {
                    retriever.getFrameAtTime(
                        t * 1000L,
                        MediaMetadataRetriever.OPTION_CLOSEST,
                    )
                }.getOrNull()
                if (frame != null) {
                    val faces = faceDetector.detect(frame)
                    val largest = faces.firstOrNull()
                    if (largest != null) {
                        // 이미지 픽셀 기반 bounding box → 프레임 NDC(0..1)
                        val cx = (largest.exactCenterX() / frame.width).coerceIn(0f, 1f)
                        val cy = (largest.exactCenterY() / frame.height).coerceIn(0f, 1f)
                        val w = (largest.width().toFloat() / frame.width).coerceIn(0f, 1f)
                        val h = (largest.height().toFloat() / frame.height).coerceIn(0f, 1f)
                        // MosaicBlockOverlay 는 NDC(-1..1 center origin) 을 기대하므로 변환
                        keyframes += MosaicKeyframe(
                            sourceTimeMs = t,
                            cx = cx * 2f - 1f,
                            cy = -(cy * 2f - 1f), // 화면 좌표 y-down → NDC y-up
                            w = w,
                            h = h,
                        )
                    }
                    runCatching { frame.recycleIfPossible() }
                }
                t += sampleIntervalMs
            }
            keyframes
        } catch (_: Exception) {
            emptyList()
        } finally {
            runCatching { retriever.release() }
        }
    }

    private fun Bitmap.recycleIfPossible() {
        if (!isRecycled) recycle()
    }
}
