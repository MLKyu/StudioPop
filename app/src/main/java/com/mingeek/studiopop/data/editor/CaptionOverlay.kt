package com.mingeek.studiopop.data.editor

import android.graphics.Color
import android.text.Spannable
import android.text.SpannableString
import android.text.style.BackgroundColorSpan
import android.text.style.ForegroundColorSpan
import androidx.media3.common.util.UnstableApi
import androidx.media3.effect.OverlaySettings
import androidx.media3.effect.TextOverlay
import com.mingeek.studiopop.data.caption.Cue

/**
 * 자막 큐 리스트를 presentationTime 기반 동적 텍스트 오버레이로 변환.
 *
 * cues 의 시각은 **출력 영상 기준** 이어야 한다.
 * (원본 영상에서 트림한 경우 startMs 오프셋을 미리 빼서 전달할 것)
 */
@UnstableApi
class CaptionOverlay(
    private val cues: List<Cue>,
) : TextOverlay() {

    private val empty = SpannableString("")

    // 영상 하단 중앙 근처에 배치. y = -0.8 (NDC 하단 쪽)
    private val settings: OverlaySettings =
        OverlaySettings.Builder()
            .setBackgroundFrameAnchor(0f, -0.8f)
            .build()

    override fun getText(presentationTimeUs: Long): SpannableString {
        val ms = presentationTimeUs / 1000
        val active = cues.firstOrNull { ms in it.startMs..it.endMs } ?: return empty
        val s = SpannableString("  ${active.text}  ")
        s.setSpan(
            BackgroundColorSpan(Color.argb(160, 0, 0, 0)),
            0, s.length, Spannable.SPAN_EXCLUSIVE_EXCLUSIVE,
        )
        s.setSpan(
            ForegroundColorSpan(Color.WHITE),
            0, s.length, Spannable.SPAN_EXCLUSIVE_EXCLUSIVE,
        )
        return s
    }

    override fun getOverlaySettings(presentationTimeUs: Long): OverlaySettings = settings
}
