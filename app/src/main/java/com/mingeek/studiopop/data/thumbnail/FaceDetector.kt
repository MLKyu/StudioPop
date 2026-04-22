package com.mingeek.studiopop.data.thumbnail

import android.graphics.Bitmap
import android.graphics.Rect
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.face.FaceDetection
import com.google.mlkit.vision.face.FaceDetectorOptions
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlin.coroutines.resume

/**
 * ML Kit Face Detection 래퍼 (on-device, 무료).
 * 큰 얼굴부터 정렬한 박스 리스트를 반환. 실패/무얼굴은 emptyList.
 */
class FaceDetector {

    private val detector by lazy {
        val opts = FaceDetectorOptions.Builder()
            .setPerformanceMode(FaceDetectorOptions.PERFORMANCE_MODE_FAST)
            .setLandmarkMode(FaceDetectorOptions.LANDMARK_MODE_NONE)
            .setClassificationMode(FaceDetectorOptions.CLASSIFICATION_MODE_NONE)
            .setMinFaceSize(0.1f)
            .build()
        FaceDetection.getClient(opts)
    }

    suspend fun detect(bitmap: Bitmap): List<Rect> = suspendCancellableCoroutine { cont ->
        val image = InputImage.fromBitmap(bitmap, 0)
        detector.process(image)
            .addOnSuccessListener { faces ->
                val sorted = faces
                    .map { it.boundingBox }
                    .sortedByDescending { it.width().toLong() * it.height().toLong() }
                if (cont.isActive) cont.resume(sorted)
            }
            .addOnFailureListener {
                // 감지 실패는 치명적 아님 — 빈 리스트로 graceful 처리
                if (cont.isActive) cont.resume(emptyList())
            }
    }
}
