package com.mingeek.studiopop.data.effects.builtins

import com.mingeek.studiopop.data.effects.EffectCategory
import com.mingeek.studiopop.data.effects.EffectDefinition
import com.mingeek.studiopop.data.effects.EffectParameter
import com.mingeek.studiopop.data.effects.EffectRegistry
import com.mingeek.studiopop.data.keyframe.Easing
import com.mingeek.studiopop.data.keyframe.Keyframe
import com.mingeek.studiopop.data.keyframe.KeyframeTrack
import com.mingeek.studiopop.data.keyframe.Vec2
import com.mingeek.studiopop.data.keyframe.lerpFloat
import com.mingeek.studiopop.data.keyframe.lerpVec2

/**
 * 영상 자체에 적용되는 변환 효과 — 카메라 무브먼트 시뮬레이션. R3 단계에선 효과 정의 등록 +
 * 키프레임 생성 헬퍼 + 트랙 샘플러까지. 실제 Media3 export 적용은 R3 후반/R4 에서
 * VideoEditor 어댑터 추가 (현재는 모자이크처럼 export 시점에 변환 합성).
 *
 * 키프레임 트랙 빌더 패턴은 [Keyframe] / [KeyframeTrack] 일반화 모델 위에 직접 올라가
 * 새 변환 효과를 추가할 때 그대로 활용 가능.
 */
object VideoFxPresets {

    const val KEN_BURNS = "video_fx.ken_burns"
    const val ZOOM_PUNCH = "video_fx.zoom_punch"
    const val SPEED_RAMP = "video_fx.speed_ramp"

    private val durationParam = EffectParameter.IntRange(
        key = "durationMs", label = "지속 시간(ms)",
        min = 200, max = 8000, default = 2000,
    )

    private val intensityParam = EffectParameter.FloatRange(
        key = "intensity", label = "강도",
        min = 0f, max = 1.5f, default = 1f,
    )

    private val directionParam = EffectParameter.Choice(
        key = "direction", label = "방향",
        options = listOf(
            EffectParameter.Choice.Option(KenBurnsDirection.IN.name, "줌 인"),
            EffectParameter.Choice.Option(KenBurnsDirection.OUT.name, "줌 아웃"),
            EffectParameter.Choice.Option(KenBurnsDirection.PAN_LEFT.name, "좌→우"),
            EffectParameter.Choice.Option(KenBurnsDirection.PAN_RIGHT.name, "우→좌"),
            EffectParameter.Choice.Option(KenBurnsDirection.DIAGONAL.name, "대각"),
        ),
        defaultId = KenBurnsDirection.IN.name,
    )

    val DEFINITIONS: List<EffectDefinition> = listOf(
        EffectDefinition(
            id = KEN_BURNS, displayName = "켄 번즈 (자동 줌·팬)",
            category = EffectCategory.VIDEO_FX,
            parameters = listOf(durationParam, intensityParam, directionParam),
            previewHint = EffectDefinition.PreviewHint(
                emoji = "🎥", tagLine = "정지 컷에 미세한 줌·팬으로 생기 부여",
            ),
        ),
        EffectDefinition(
            id = ZOOM_PUNCH, displayName = "줌 펀치",
            category = EffectCategory.VIDEO_FX,
            parameters = listOf(durationParam, intensityParam),
            previewHint = EffectDefinition.PreviewHint(
                emoji = "💥", tagLine = "강조 시점에 짧고 강하게 줌 인",
            ),
        ),
        EffectDefinition(
            id = SPEED_RAMP, displayName = "속도 램프",
            category = EffectCategory.VIDEO_FX,
            parameters = listOf(
                durationParam,
                EffectParameter.FloatRange(
                    key = "minSpeed", label = "최저 속도(슬로우)",
                    min = 0.1f, max = 1.0f, default = 0.4f,
                ),
                EffectParameter.FloatRange(
                    key = "maxSpeed", label = "최고 속도",
                    min = 1.0f, max = 4.0f, default = 1.8f,
                ),
            ),
            previewHint = EffectDefinition.PreviewHint(
                emoji = "🌀", tagLine = "비트 직전 슬로우 → 비트에서 정상속도",
            ),
        ),
    )

    enum class KenBurnsDirection { IN, OUT, PAN_LEFT, PAN_RIGHT, DIAGONAL }
}

/**
 * 한 시점의 카메라 변환. center 는 NDC(-1..1) 의 viewport 중심, scale 은 1.0 = 원본,
 * 1.2 = 20% 줌인. RenderPlan 단계에서 이 값이 영상 변환 행렬로 변환된다.
 */
data class CameraTransform(
    val center: Vec2,
    val scale: Float,
)

/** Ken Burns 효과를 위한 키프레임 트랙 빌더. duration ms 동안 변환을 직선/easing 보간. */
object KenBurnsHelper {

    /**
     * @param startMs 효과 시작 시간(원본 영상 ms 기준).
     * @param durationMs 효과 지속 시간.
     * @param direction 줌·팬 방향.
     * @param intensity 0..1.5. 1 = 표준(8% 줌, 6% 팬). 0.5 = 절반, 1.2 = 20% 더 강함.
     */
    fun build(
        startMs: Long,
        durationMs: Long,
        direction: VideoFxPresets.KenBurnsDirection,
        intensity: Float = 1f,
    ): KeyframeTrack<CameraTransform> {
        val endMs = startMs + durationMs
        val zoomDelta = 0.08f * intensity
        val panDelta = 0.06f * intensity

        val (start, end) = when (direction) {
            VideoFxPresets.KenBurnsDirection.IN ->
                CameraTransform(Vec2(0f, 0f), 1f) to
                    CameraTransform(Vec2(0f, 0f), 1f + zoomDelta)
            VideoFxPresets.KenBurnsDirection.OUT ->
                CameraTransform(Vec2(0f, 0f), 1f + zoomDelta) to
                    CameraTransform(Vec2(0f, 0f), 1f)
            VideoFxPresets.KenBurnsDirection.PAN_LEFT ->
                CameraTransform(Vec2(panDelta, 0f), 1.04f) to
                    CameraTransform(Vec2(-panDelta, 0f), 1.04f)
            VideoFxPresets.KenBurnsDirection.PAN_RIGHT ->
                CameraTransform(Vec2(-panDelta, 0f), 1.04f) to
                    CameraTransform(Vec2(panDelta, 0f), 1.04f)
            VideoFxPresets.KenBurnsDirection.DIAGONAL ->
                CameraTransform(Vec2(-panDelta, panDelta), 1f) to
                    CameraTransform(Vec2(panDelta, -panDelta), 1f + zoomDelta)
        }

        return KeyframeTrack(
            keyframes = listOf(
                Keyframe(timeMs = startMs, value = start, easing = Easing.EASE_IN_OUT),
                Keyframe(timeMs = endMs, value = end, easing = Easing.EASE_IN_OUT),
            ),
            interpolator = ::lerpCameraTransform,
        )
    }
}

/**
 * Zoom Punch — 짧은 시간 강한 스파이크 줌. 시작 → 피크 → 복귀 3 키프레임.
 */
object ZoomPunchHelper {

    fun build(
        peakTimeMs: Long,
        durationMs: Long = 480L,
        intensity: Float = 1f,
    ): KeyframeTrack<CameraTransform> {
        val half = durationMs / 2
        val zoom = 1f + 0.18f * intensity
        return KeyframeTrack(
            keyframes = listOf(
                Keyframe(
                    timeMs = peakTimeMs - half,
                    value = CameraTransform(Vec2(0f, 0f), 1f),
                    easing = Easing.EASE_IN,
                ),
                Keyframe(
                    timeMs = peakTimeMs,
                    value = CameraTransform(Vec2(0f, 0f), zoom),
                    easing = Easing.SPRING,
                ),
                Keyframe(
                    timeMs = peakTimeMs + half,
                    value = CameraTransform(Vec2(0f, 0f), 1f),
                    easing = Easing.EASE_OUT,
                ),
            ),
            interpolator = ::lerpCameraTransform,
        )
    }
}

private fun lerpCameraTransform(a: CameraTransform, b: CameraTransform, t: Float): CameraTransform =
    CameraTransform(
        center = lerpVec2(a.center, b.center, t),
        scale = lerpFloat(a.scale, b.scale, t),
    )

/** 일괄 등록 진입점. */
fun EffectRegistry.registerVideoFxPresets() {
    registerAll(VideoFxPresets.DEFINITIONS)
}
