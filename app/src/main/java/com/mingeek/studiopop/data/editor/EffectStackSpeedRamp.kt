package com.mingeek.studiopop.data.editor

import androidx.media3.common.C
import androidx.media3.common.audio.SpeedProvider
import androidx.media3.common.util.UnstableApi
import com.mingeek.studiopop.data.effects.EffectStack
import com.mingeek.studiopop.data.effects.builtins.VideoFxPresets

/**
 * R7: SPEED_RAMP EffectInstance → Media3 [SpeedProvider]. 한 SpeedProvider 가 영상 전체에 적용
 * 되므로 composition.audioProcessors / videoEffects 양쪽에 동일 인스턴스를 꽂아 audio/video 시간축을
 * 맞춘다 ([androidx.media3.common.audio.SpeedChangingAudioProcessor] +
 * [androidx.media3.effect.SpeedChangeEffect]).
 *
 * 1차 스코프 — params.minSpeed 에서 1.0 까지 선형 슬로우 램프. params.maxSpeed 는 미사용 (속도
 * 펀치 같은 fast ramp 는 다음 라운드).
 *
 * 한 EffectInstance 의 source 범위는 [Timeline.rangeToOutputWindows] 로 출력 시각으로 매핑 —
 * SpeedProvider 의 input 은 composition 의 input time(=concat 후 output time, pre-speed) 이라
 * 우리가 가진 출력 시각이 그대로 좌표계와 일치.
 *
 * 주의: SpeedChangeEffect 가 frame 의 PTS 를 다시 쓰면 영상의 총 길이가 바뀐다. 자막/오버레이는
 * 이 effect 보다 앞 단계에서 합성돼 함께 재시간화 — 호출 측 [VideoEditor] 의 effect 체인 순서에
 * 의존.
 */
@UnstableApi
object EffectStackSpeedRamp {

    /**
     * effectStack 에서 활성 SPEED_RAMP 인스턴스를 모아 단일 [SpeedProvider] 생성. 적용할 램프가
     * 없으면 null — 호출 측이 SpeedChangeEffect 자체를 추가하지 않게.
     */
    fun build(stack: EffectStack, timeline: Timeline): SpeedProvider? {
        val ramps = mutableListOf<RampWindow>()
        for (inst in stack.instances) {
            if (!inst.enabled) continue
            if (inst.definitionId != VideoFxPresets.SPEED_RAMP) continue
            val minSpeed = inst.params.float("minSpeed", 0.4f).coerceIn(0.1f, 4f)
            // maxSpeed 기본 1.0 = normal (slow → normal ramp). 사용자가 1.0 보다 크게 두면 fast ramp,
            // 1.0 보다 작게 두면 점점 더 느려지는 ramp. minSpeed 와 같으면 사실상 일정 속도 구간.
            val maxSpeed = inst.params.float("maxSpeed", 1.0f).coerceIn(0.1f, 4f)
            // 같은 효과가 cut 으로 분할돼 여러 출력 윈도우에 떨어질 수 있음. 윈도우마다 독립 ramp.
            val windows = timeline.rangeToOutputWindows(inst.sourceStartMs, inst.sourceEndMs)
            for (w in windows) {
                ramps += RampWindow(
                    startUs = w.first * 1_000L,
                    endUs = w.last * 1_000L,
                    minSpeed = minSpeed,
                    maxSpeed = maxSpeed,
                )
            }
        }
        if (ramps.isEmpty()) return null
        // 시간순 정렬 + 겹침 검증 (겹치면 첫 번째 우선 — getSpeed 가 firstOrNull 이라 자연 처리).
        ramps.sortBy { it.startUs }
        return SpeedRampProvider(ramps)
    }

    /**
     * 다른 SpeedProvider 를 시간축으로 평행 이동한 view. composition-level [SpeedProvider] 를
     * per-EditedMediaItem audio processor 가 item-relative 시간으로 사용할 때 필요 — item k 의
     * 시작이 composition-time `offsetUs` 이면 item-time t 가 곧 base 의 `t + offsetUs` 에 해당.
     */
    @UnstableApi
    internal class TranslatedSpeedProvider(
        private val base: SpeedProvider,
        private val offsetUs: Long,
    ) : SpeedProvider {
        // Media3 는 item-relative timeUs 가 [0, item.duration] 범위 안 만 호출한다는 가정. base 가
        // 잘 동작하면(strictly increasing nextChange) translated 결과도 자동 계약 준수. 그래도
        // base 구현 버그/엣지에 대비해 next-change 가 timeUs 이하로 떨어지면 TIME_UNSET 으로 막음.
        override fun getSpeed(timeUs: Long): Float = base.getSpeed(timeUs + offsetUs)
        override fun getNextSpeedChangeTimeUs(timeUs: Long): Long {
            val nextBase = base.getNextSpeedChangeTimeUs(timeUs + offsetUs)
            if (nextBase == C.TIME_UNSET) return C.TIME_UNSET
            val translated = nextBase - offsetUs
            return if (translated > timeUs) translated else C.TIME_UNSET
        }
    }

    internal data class RampWindow(
        val startUs: Long,
        val endUs: Long,
        val minSpeed: Float,
        val maxSpeed: Float = 1.0f,
    )

    /**
     * 시간 가변 SpeedProvider. ramp 안에서는 [STEP_US] 마다 고정 속도(linear interpolation 의 piecewise
     * 근사) — Media3 가 step 경계마다 SpeedChangingAudioProcessor 의 Sonic 파라미터를 다시 적용한다.
     */
    internal class SpeedRampProvider(
        private val ramps: List<RampWindow>,
    ) : SpeedProvider {

        override fun getSpeed(timeUs: Long): Float {
            // [startUs, endUs) — exclusive on endUs 로 [getNextSpeedChangeTimeUs] 와 일치. 두 ramp 가
            // 맞닿을 때(prev.endUs == next.startUs) prev 의 1.0 으로 snap 되지 않고 next 의 minSpeed
            // 부터 시작 — boundary 에서 audio click / 영상 지터 방지.
            val r = ramps.firstOrNull { timeUs >= it.startUs && timeUs < it.endUs } ?: return 1f
            val span = (r.endUs - r.startUs).coerceAtLeast(1L)
            val t = ((timeUs - r.startUs).toFloat() / span).coerceIn(0f, 1f)
            // start = minSpeed (slow), end = maxSpeed (보통 1.0 = normal). t=0 → minSpeed, t=1 → maxSpeed
            return r.minSpeed + (r.maxSpeed - r.minSpeed) * t
        }

        override fun getNextSpeedChangeTimeUs(timeUs: Long): Long {
            // SpeedProvider 계약: 반환값은 **strictly > timeUs** ("the *following* speed change") 이거나
            // C.TIME_UNSET. 같은 시각을 돌려주면 Media3 가 무한 루프/스톨 가능.
            // 1) 현재 시각이 ramp 시작 전이면, 그 ramp 시작
            // 2) ramp 안([startUs, endUs)) 이면, 그 안의 다음 stepUs 경계 (capped to endUs)
            // 3) timeUs == endUs 이거나 ramp 사이 공백 — 다음 loop 반복으로 다음 ramp 검사
            // 4) 모든 ramp 뒤면 TIME_UNSET
            for (r in ramps) {
                if (timeUs < r.startUs) return r.startUs
                if (timeUs >= r.startUs && timeUs < r.endUs) {
                    val rel = timeUs - r.startUs
                    val nextStep = (rel / STEP_US + 1) * STEP_US + r.startUs
                    return minOf(nextStep, r.endUs)
                }
                // timeUs == r.endUs 또는 ramp 뒤에 위치 — 다음 ramp 검사로 넘어감.
            }
            return C.TIME_UNSET
        }
    }

    // 50ms — Sonic 처리 단위. 너무 짧으면 오버헤드 큼, 너무 길면 ramp 곡선이 계단처럼 보임.
    internal const val STEP_US = 50_000L
}
