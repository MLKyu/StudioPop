package com.mingeek.studiopop.data.editor

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Color
import androidx.media3.common.util.UnstableApi
import androidx.media3.effect.BitmapOverlay
import androidx.media3.effect.OverlaySettings

/**
 * 하나의 [ImageLayer] 를 Media3 파이프라인에서 그리는 오버레이.
 *
 * - presentationTimeUs 가 [activeWindowsMs] 안에 있으면 비트맵 alpha 적용
 * - 바깥이면 alpha 0 으로 숨김(= 프레임에 영향 X)
 *
 * 한 Layer 당 하나의 인스턴스를 만들어 [androidx.media3.effect.OverlayEffect] 에 같이 넣음.
 * 서로 다른 Layer 는 각기 다른 bitmap/위치/회전이 필요해 캡션처럼 묶기 어려움.
 */
@UnstableApi
class ImageStickerOverlay(
    private val layer: ImageLayer,
    /**
     * 이 레이어가 보여야 하는 **출력 시각** 창들(Segment cut 반영 후 매핑된 결과).
     * 여러 세그먼트에 걸쳐 있으면 조각들이 여러 개일 수 있음.
     */
    private val activeWindowsMs: List<LongRange>,
) : BitmapOverlay() {

    private val fallback: Bitmap by lazy {
        Bitmap.createBitmap(64, 64, Bitmap.Config.ARGB_8888).apply {
            eraseColor(Color.MAGENTA)
        }
    }

    private val bitmap: Bitmap by lazy {
        val decoded = runCatching {
            BitmapFactory.decodeFile(layer.imageUri.path)
        }.getOrNull()
        decoded ?: fallback
    }

    override fun getBitmap(presentationTimeUs: Long): Bitmap = bitmap

    override fun getOverlaySettings(presentationTimeUs: Long): OverlaySettings {
        val ms = presentationTimeUs / 1000
        val isActive = activeWindowsMs.any { ms in it }
        val alpha = if (isActive) layer.alpha.coerceIn(0f, 1f) else 0f
        return OverlaySettings.Builder()
            .setBackgroundFrameAnchor(
                layer.centerX.coerceIn(-1f, 1f),
                layer.centerY.coerceIn(-1f, 1f),
            )
            .setScale(layer.scale, layer.scale)
            .setRotationDegrees(layer.rotationDeg)
            .setAlphaScale(alpha)
            .build()
    }
}
