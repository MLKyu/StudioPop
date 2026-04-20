package com.mingeek.studiopop.data.editor

import android.graphics.Color
import android.graphics.Typeface
import android.text.Spannable
import android.text.SpannableString
import android.text.style.BackgroundColorSpan
import android.text.style.ForegroundColorSpan
import android.text.style.StyleSpan
import androidx.media3.common.util.UnstableApi
import androidx.media3.effect.OverlaySettings
import androidx.media3.effect.TextOverlay
import com.mingeek.studiopop.data.caption.Cue

/**
 * 출력 시각(presentationTime) 기반으로 활성화되는 동적 텍스트 오버레이.
 *
 * - [cues] 의 시각은 **출력 영상 기준**
 * - [style] 로 색/굵기/배경 투명도/세로 위치 조절
 * - 한 그룹의 자막은 동일 스타일을 공유. 여러 스타일을 섞고 싶으면
 *   스타일별로 오버레이 인스턴스를 여러 개 만들어 [androidx.media3.effect.OverlayEffect]
 *   에 함께 전달할 것.
 */
@UnstableApi
class CaptionOverlay(
    private val cues: List<Cue>,
    private val style: CaptionStyle = CaptionStyle.DEFAULT,
) : TextOverlay() {

    private val empty = SpannableString("")

    private val settings: OverlaySettings =
        OverlaySettings.Builder()
            .setBackgroundFrameAnchor(0f, style.anchorY)
            .setScale(style.sizeScale, style.sizeScale)
            .build()

    override fun getText(presentationTimeUs: Long): SpannableString {
        val ms = presentationTimeUs / 1000
        val active = cues.firstOrNull { ms in it.startMs..it.endMs } ?: return empty

        val padded = if (style.backgroundAlpha > 0) "  ${active.text}  " else active.text
        val s = SpannableString(padded)

        s.setSpan(
            ForegroundColorSpan(style.textColor),
            0, s.length, Spannable.SPAN_EXCLUSIVE_EXCLUSIVE,
        )
        if (style.backgroundAlpha > 0) {
            s.setSpan(
                BackgroundColorSpan(Color.argb(style.backgroundAlpha, 0, 0, 0)),
                0, s.length, Spannable.SPAN_EXCLUSIVE_EXCLUSIVE,
            )
        }
        if (style.bold) {
            s.setSpan(
                StyleSpan(Typeface.BOLD),
                0, s.length, Spannable.SPAN_EXCLUSIVE_EXCLUSIVE,
            )
        }
        return s
    }

    override fun getOverlaySettings(presentationTimeUs: Long): OverlaySettings = settings
}
