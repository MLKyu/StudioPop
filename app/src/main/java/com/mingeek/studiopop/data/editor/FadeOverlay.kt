package com.mingeek.studiopop.data.editor

import android.graphics.Bitmap
import android.graphics.Color
import androidx.media3.common.util.UnstableApi
import androidx.media3.effect.BitmapOverlay
import androidx.media3.effect.OverlaySettings

/**
 * 지정된 출력 시각([boundariesMs]) 근처에서 프레임을 검정으로 덮는 페이드 오버레이.
 * 각 경계 시각의 ±[halfDurationMs] 범위에서 알파가 0→1→0 으로 변함.
 *
 * 세그먼트 사이 cut 전환을 부드럽게(페이드-투-블랙) 만들 때 사용.
 */
@UnstableApi
class FadeAtBoundariesOverlay(
    private val boundariesMs: List<Long>,
    private val halfDurationMs: Long = 200L,
) : BitmapOverlay() {

    private val blackBitmap: Bitmap by lazy {
        Bitmap.createBitmap(64, 64, Bitmap.Config.ARGB_8888).apply {
            eraseColor(Color.BLACK)
        }
    }

    override fun getBitmap(presentationTimeUs: Long): Bitmap = blackBitmap

    override fun getOverlaySettings(presentationTimeUs: Long): OverlaySettings {
        val ms = presentationTimeUs / 1000
        val closestDist = boundariesMs.minOfOrNull { kotlin.math.abs(it - ms) } ?: Long.MAX_VALUE
        val alpha = if (closestDist < halfDurationMs) {
            1f - (closestDist.toFloat() / halfDurationMs)
        } else 0f
        // 64x64 bitmap 을 프레임 전체를 덮도록 큰 배율로 스케일
        return OverlaySettings.Builder()
            .setAlphaScale(alpha.coerceIn(0f, 1f))
            .setScale(100f, 100f)
            .build()
    }
}
