package com.mingeek.studiopop.data.editor

import androidx.media3.common.C
import androidx.media3.common.audio.AudioProcessor
import androidx.media3.common.audio.BaseAudioProcessor
import androidx.media3.common.util.UnstableApi
import com.mingeek.studiopop.data.keyframe.KeyframeTrack
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.tanh

/**
 * 시간 가변 볼륨 PCM 16-bit 오디오 프로세서. [KeyframeTrack] 의 multiplier 를 매 sample 시점에
 * 보간해서 적용 — 자동 더킹(보이스 위 BGM 자동 -6dB 등) 의 export 측 구현.
 *
 * 시간 추정: 입력 buffer 의 sample 개수를 누적해 sampleRate 와 channelCount 로 ms 환산.
 * Media3 AudioProcessor 가 시간 정보를 직접 제공하지 않아 sample-count 누적이 가장 신뢰 높은 방법.
 *
 * 트랙이 비거나(키프레임 0개) 모든 키프레임이 1.0 근처면 isActive=false 로 pass-through.
 *
 * 클립핑은 [VolumeAudioProcessor] 와 동일 — 1.0 이하는 선형, 초과는 tanh saturation.
 */
@UnstableApi
class DuckingAudioProcessor(
    private val track: KeyframeTrack<Float>,
) : BaseAudioProcessor() {

    private var sampleRateHz: Int = 0
    private var channelCount: Int = 0
    private var samplesProcessed: Long = 0

    override fun onConfigure(
        inputAudioFormat: AudioProcessor.AudioFormat,
    ): AudioProcessor.AudioFormat {
        if (inputAudioFormat.encoding != C.ENCODING_PCM_16BIT) {
            throw AudioProcessor.UnhandledAudioFormatException(inputAudioFormat)
        }
        sampleRateHz = inputAudioFormat.sampleRate
        channelCount = inputAudioFormat.channelCount.coerceAtLeast(1)
        samplesProcessed = 0
        return inputAudioFormat
    }

    override fun isActive(): Boolean {
        if (!super.isActive()) return false
        if (track.isEmpty) return false
        // 모든 키프레임이 1.0 근처면 사실상 NOOP — 파이프라인에서 제외해 비용 절감.
        val allUnity = track.keyframes().all { kotlin.math.abs(it.value - 1f) <= UNITY_EPSILON }
        return !allUnity
    }

    override fun queueInput(inputBuffer: ByteBuffer) {
        val size = inputBuffer.remaining()
        if (size == 0) return
        val out = replaceOutputBuffer(size).order(ByteOrder.nativeOrder())
        val input = inputBuffer.order(ByteOrder.nativeOrder())

        // sample 단위 = 16-bit PCM × channelCount frame. 매 frame(= channelCount samples) 끝에
        // samplesProcessed 한 번 증가해서 각 channel sample 이 같은 timeMs 를 공유하도록.
        var channelInFrame = 0
        while (input.hasRemaining()) {
            val timeMs = if (sampleRateHz > 0) {
                (samplesProcessed.toDouble() * 1000.0 / sampleRateHz).toLong()
            } else 0L
            val volume = track.sampleAt(timeMs) ?: 1f
            val sample = input.short.toInt()
            val scale = volume.coerceAtLeast(0f)
            val shapedShort = if (scale > 1f) {
                val normalized = (sample / NORM.toFloat()) * scale
                val shaped = tanh(normalized)
                (shaped * NORM.toFloat()).toInt()
                    .coerceIn(Short.MIN_VALUE.toInt(), Short.MAX_VALUE.toInt())
            } else {
                (sample * scale).toInt()
                    .coerceIn(Short.MIN_VALUE.toInt(), Short.MAX_VALUE.toInt())
            }
            out.putShort(shapedShort.toShort())
            channelInFrame++
            if (channelInFrame >= channelCount) {
                samplesProcessed++
                channelInFrame = 0
            }
        }
        inputBuffer.position(inputBuffer.limit())
        out.flip()
    }

    override fun onFlush() {
        // seek 또는 새 sequence 진입 시 시간 리셋. Media3 가 flush 후 새 buffer 부터 다시 시작.
        samplesProcessed = 0
        super.onFlush()
    }

    override fun onReset() {
        samplesProcessed = 0
        super.onReset()
    }

    companion object {
        private const val UNITY_EPSILON = 0.001f
        private const val NORM = 32767
    }
}
