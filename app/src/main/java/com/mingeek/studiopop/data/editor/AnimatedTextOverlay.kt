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

/**
 * R6 R7+: 시간 가변 OverlaySettings 를 갖는 [TextOverlay]. 활성 [AnimatedSegment] 안에서
 * 진행도 t∈[0,1] 를 [AnimatedSegment.animator] 에 흘려 scale/jitter/alpha 를 매 프레임 결정.
 *
 * Media3 [OverlaySettings] 의 setScale/setBackgroundFrameAnchor/setAlpha 만으로 표현 가능한
 * 애니메이션 — 펄스/바운스/페이드/흔들림 — 은 BitmapOverlay 풀 렌더 없이 이걸로 처리. 셰이더가
 * 필요한 변형(블러, 글로우, 색 분리)이 들어오면 그땐 별도 BitmapOverlay 가 필요.
 *
 * 화면에 두 개 이상의 폭탄/카운트다운이 동시에 활성화될 일은 거의 없지만, 겹치면 첫 번째 활성
 * 세그먼트만 표시 — 같은 OverlaySettings 한 번만 반환할 수 있는 [TextOverlay] 구조 한계.
 *
 * @param segments 출력 시각 기반(ms) 세그먼트들. cut 분할 매핑은 호출 측 책임 (rangeToOutputWindows
 *   결과를 풀어 넣어 줘야 함).
 * @param style 글꼴/색/굵기/배경/anchor — base. animator 는 그 위에서 변동만 결정.
 */
@UnstableApi
class AnimatedTextOverlay(
    private val segments: List<AnimatedSegment>,
    private val style: CaptionStyle = CaptionStyle.DEFAULT,
) : TextOverlay() {

    private val empty = SpannableString("")

    private val baseSettings: OverlaySettings = OverlaySettings.Builder()
        .setBackgroundFrameAnchor(style.anchorX, style.anchorY)
        .setScale(style.sizeScale, style.sizeScale)
        .build()

    override fun getText(presentationTimeUs: Long): SpannableString {
        val ms = presentationTimeUs / 1000
        // [startMs, endMs) — half-open. 인접 세그먼트("3"→"2") 경계에서 첫 번째가 t=1 으로 fade
        // 끝난 frame 이 한 번 더 노출되는 1-frame 깜빡임 방지. 다른 시간 매칭 컨벤션과도 일치.
        val active = segments.firstOrNull { ms >= it.startMs && ms < it.endMs } ?: return empty
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

    override fun getOverlaySettings(presentationTimeUs: Long): OverlaySettings {
        val ms = presentationTimeUs / 1000
        val active = segments.firstOrNull { ms >= it.startMs && ms < it.endMs } ?: return baseSettings
        val span = (active.endMs - active.startMs).coerceAtLeast(1L)
        val t = ((ms - active.startMs).toFloat() / span).coerceIn(0f, 1f)
        val frame = active.animator(t)
        val finalScale = style.sizeScale * frame.scale
        // anchor 는 NDC -1..1 범위라 jitter 가 더해져 벗어나면 clamp.
        val anchorX = (style.anchorX + frame.jitterX).coerceIn(-1f, 1f)
        return OverlaySettings.Builder()
            .setBackgroundFrameAnchor(anchorX, style.anchorY)
            .setScale(finalScale, finalScale)
            // alpha 는 [0,1] — Media3 setAlphaScale 자체는 >=0 만 요구하지만 값이 1 보다 크면 매우
            // 밝게 합성돼 의도와 다름. 미래에 추가될 animator 가 잘못 계산해도 안전망으로 [0,1] 클램프.
            .setAlphaScale(frame.alpha.coerceIn(0f, 1f))
            .build()
    }
}

/**
 * 한 텍스트 + 그 텍스트가 떠 있는 출력 시간 창 + 시간별 변동 함수.
 */
data class AnimatedSegment(
    val startMs: Long,
    val endMs: Long,
    val text: String,
    val animator: (Float) -> AnimationFrame,
)

/**
 * 매 프레임 적용할 변동 — 모두 base 위에 곱해지거나(scale, alpha) 더해짐(jitterX).
 * jitterX 는 NDC(-1..1) 단위. 0.01 ≈ 1080p 에서 약 ±10px 흔들림.
 */
data class AnimationFrame(
    val scale: Float = 1f,
    val jitterX: Float = 0f,
    val alpha: Float = 1f,
)

/**
 * 표준 애니메이터 라이브러리 — 새 효과 추가 시 이 패턴으로 한 줄 함수 더하면 됨.
 */
object Animators {

    /** 카운트다운: 큰 → 작은 펄스. 시작에서 1.4 배, 끝으로 갈수록 1.0 배. 마지막 15% 는 페이드아웃. */
    fun countdownPulse(t: Float): AnimationFrame {
        val scale = lerp(1.4f, 1.0f, t.coerceIn(0f, 1f))
        val alpha = if (t > 0.85f) lerp(1f, 0f, ((t - 0.85f) / 0.15f).coerceIn(0f, 1f)) else 1f
        return AnimationFrame(scale = scale, alpha = alpha)
    }

    /**
     * 강조어 폭탄: 0.6 → 1.2 (entry, t<0.25) → 1.0 (steady, 0.25<=t<0.7) → 0.0 (exit, t>=0.7)
     * + steady 구간 sin 진동. 미리보기 [com.mingeek.studiopop.ui.text.BombCaptionOverlay] 와
     * 곡선/진동 동기화.
     */
    fun bombCaption(t: Float): AnimationFrame {
        val scale = when {
            t < 0.25f -> lerp(0.6f, 1.2f, t / 0.25f)
            t < 0.7f -> 1.0f
            else -> lerp(1.0f, 0f, ((t - 0.7f) / 0.3f).coerceIn(0f, 1f))
        }
        // sin 한 주기 = jitter 한 번 흔들기. NDC 0.012 ≈ 1080p 에서 ±13px (preview 6dp 비슷).
        val jitter = if (t in 0.25f..0.7f) {
            val phase = ((t - 0.25f) / 0.45f) * (2f * Math.PI.toFloat())
            0.012f * kotlin.math.sin(phase)
        } else 0f
        val alpha = if (t > 0.7f) lerp(1f, 0f, ((t - 0.7f) / 0.3f).coerceIn(0f, 1f)) else 1f
        return AnimationFrame(scale = scale, jitterX = jitter, alpha = alpha)
    }

    private fun lerp(a: Float, b: Float, t: Float): Float = a + (b - a) * t
}
