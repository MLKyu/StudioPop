package com.mingeek.studiopop.data.editor

import androidx.media3.common.C
import androidx.media3.common.audio.AudioProcessor
import androidx.media3.common.audio.BaseAudioProcessor
import androidx.media3.common.util.UnstableApi
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.tanh

/**
 * PCM 16-bit 오디오 볼륨 배율 처리기.
 * Media3 Transformer 의 [EditedMediaItem] 에 체인으로 걸어 해당 sequence 의 오디오 전체 레벨 조절.
 *
 * - [volume] 1.0 = 원본, 0.0 = 음소거, 2.0 = 2배. 1.0 근접이면 pass-through (활성 X).
 * - Hard clip 으로 [Short.MIN_VALUE, Short.MAX_VALUE] 범위 넘는 값 제한.
 */
@UnstableApi
class VolumeAudioProcessor(private val volume: Float) : BaseAudioProcessor() {

    override fun onConfigure(
        inputAudioFormat: AudioProcessor.AudioFormat,
    ): AudioProcessor.AudioFormat {
        if (inputAudioFormat.encoding != C.ENCODING_PCM_16BIT) {
            throw AudioProcessor.UnhandledAudioFormatException(inputAudioFormat)
        }
        return inputAudioFormat
    }

    override fun isActive(): Boolean =
        super.isActive() && kotlin.math.abs(volume - 1f) > VOLUME_EPSILON

    override fun queueInput(inputBuffer: ByteBuffer) {
        val size = inputBuffer.remaining()
        if (size == 0) return
        val out = replaceOutputBuffer(size).order(ByteOrder.nativeOrder())
        val input = inputBuffer.order(ByteOrder.nativeOrder())
        val scale = volume.coerceAtLeast(0f)
        val softClip = scale > 1f
        while (input.hasRemaining()) {
            val sample = input.short.toInt()
            val shapedShort = if (softClip) {
                // 부동소수 [-1, 1] 로 정규화 후 tanh 로 매끄러운 saturation.
                // 1.0 이하는 거의 선형, 초과분은 -1..1 안으로 눌림 → 하드 clipping 의 square-wave
                // 왜곡 대신 mild warmth 로 변환.
                val normalized = (sample / NORM.toFloat()) * scale
                val shaped = tanh(normalized)
                (shaped * NORM.toFloat()).toInt()
                    .coerceIn(Short.MIN_VALUE.toInt(), Short.MAX_VALUE.toInt())
            } else {
                (sample * scale).toInt()
                    .coerceIn(Short.MIN_VALUE.toInt(), Short.MAX_VALUE.toInt())
            }
            out.putShort(shapedShort.toShort())
        }
        inputBuffer.position(inputBuffer.limit())
        out.flip()
    }

    companion object {
        private const val VOLUME_EPSILON = 0.001f
        /** PCM16 정규화 배수 (Short.MAX_VALUE 근사). */
        private const val NORM = 32767
    }
}
