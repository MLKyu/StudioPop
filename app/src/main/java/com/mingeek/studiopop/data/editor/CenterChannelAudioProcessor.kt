package com.mingeek.studiopop.data.editor

import androidx.media3.common.C
import androidx.media3.common.audio.AudioProcessor
import androidx.media3.common.audio.BaseAudioProcessor
import androidx.media3.common.util.UnstableApi
import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * 스테레오 PCM 16-bit 오디오에서 **center 채널만** 추출해 mono 로 출력하는 AudioProcessor.
 *
 * 원리: `(L + R) / 2` 는 두 채널에 동일하게 있는 성분(주로 vocal/중앙 대사),
 * `(L - R) / 2` 는 L/R 분산 성분(주로 음악 반주·리버브). 이 processor 는 center 만 남김.
 *
 * **한계** — 가볍고 즉각적인 대신 품질 낮음:
 * - 원본이 mono 면 L = R 이라 사실상 no-op (그대로 음악 + 사람소리 남음)
 * - 음악이 정확히 중앙 pan (드럼/베이스) 이면 같이 남음
 * - reverb/stereo 이미징이 강한 음악은 감소 효과 뚜렷
 *
 * Output: 입력이 stereo 면 mono, 그 외(이미 mono/기타) 면 no-op pass-through.
 */
@UnstableApi
class CenterChannelAudioProcessor : BaseAudioProcessor() {

    private var isStereoInput: Boolean = false

    override fun onConfigure(
        inputAudioFormat: AudioProcessor.AudioFormat,
    ): AudioProcessor.AudioFormat {
        if (inputAudioFormat.encoding != C.ENCODING_PCM_16BIT) {
            throw AudioProcessor.UnhandledAudioFormatException(inputAudioFormat)
        }
        isStereoInput = inputAudioFormat.channelCount == 2
        return if (isStereoInput) {
            AudioProcessor.AudioFormat(
                inputAudioFormat.sampleRate,
                /* channelCount = */ 1,
                inputAudioFormat.encoding,
            )
        } else {
            inputAudioFormat
        }
    }

    override fun isActive(): Boolean = super.isActive() && isStereoInput

    override fun queueInput(inputBuffer: ByteBuffer) {
        val remaining = inputBuffer.remaining()
        if (remaining < 4) {
            // stereo PCM16 frame = 4 bytes (L short + R short). 미달이면 버림.
            inputBuffer.position(inputBuffer.limit())
            return
        }
        val frameCount = remaining / 4
        val out = replaceOutputBuffer(frameCount * 2).order(ByteOrder.nativeOrder())
        val input = inputBuffer.order(ByteOrder.nativeOrder())
        repeat(frameCount) {
            val l = input.short.toInt()
            val r = input.short.toInt()
            val center = ((l + r) / 2).coerceIn(
                Short.MIN_VALUE.toInt(),
                Short.MAX_VALUE.toInt(),
            )
            out.putShort(center.toShort())
        }
        inputBuffer.position(inputBuffer.limit())
        out.flip()
    }
}
