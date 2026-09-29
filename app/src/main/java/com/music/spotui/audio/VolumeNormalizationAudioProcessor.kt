package com.music.spotui.audio

import androidx.media3.common.C
import androidx.media3.common.audio.AudioProcessor
import androidx.media3.common.audio.BaseAudioProcessor
import androidx.media3.common.util.UnstableApi
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.sqrt

/**
 * Media3 [AudioProcessor] providing real-time volume normalization (automatic gain control)
 * for PCM 16-bit audio streams, ensuring consistent loudness across tracks.
 */
@UnstableApi
class VolumeNormalizationAudioProcessor : BaseAudioProcessor() {

    @Volatile
    var enabled: Boolean = false

    private val targetRms = 0.25
    private var currentGain = 1.0

    private var sampleRate = 0
    private var channelCount = 0

    override fun onConfigure(inputAudioFormat: AudioProcessor.AudioFormat): AudioProcessor.AudioFormat {
        if (inputAudioFormat.encoding != C.ENCODING_PCM_16BIT) {
            return AudioProcessor.AudioFormat.NOT_SET
        }
        sampleRate = inputAudioFormat.sampleRate
        channelCount = inputAudioFormat.channelCount
        return inputAudioFormat
    }

    override fun queueInput(inputBuffer: ByteBuffer) {
        val remaining = inputBuffer.remaining()
        if (remaining == 0) return

        if (!enabled || sampleRate == 0) {
            val output = replaceOutputBuffer(remaining)
            copyBuffer(inputBuffer, output, remaining)
            output.flip()
            return
        }

        inputBuffer.order(ByteOrder.nativeOrder())
        val output = replaceOutputBuffer(remaining)

        var sumSquares = 0.0
        var sampleCount = 0

        val pos = inputBuffer.position()

        while (inputBuffer.remaining() >= 2) {
            val sample = inputBuffer.short.toDouble() / Short.MAX_VALUE
            sumSquares += sample * sample
            sampleCount++
        }
        inputBuffer.position(pos)

        if (sampleCount > 0) {
            val rms = sqrt(sumSquares / sampleCount)
            if (rms > 0.01) {
                val targetGain = (targetRms / rms).coerceIn(0.2, 4.0)
                currentGain += (targetGain - currentGain) * 0.1
            }
        }

        while (inputBuffer.remaining() >= 2) {
            val sample = inputBuffer.short.toDouble() / Short.MAX_VALUE
            val processed = (sample * currentGain).coerceIn(-1.0, 1.0)
            output.putShort((processed * Short.MAX_VALUE).toInt().toShort())
        }

        output.flip()
    }

    private fun copyBuffer(src: ByteBuffer, dst: ByteBuffer, size: Int) {
        if (src === dst) {
            dst.position(0)
            dst.limit(size)
            return
        }
        val pos = src.position()
        for (i in 0 until size) {
            dst.put(src.get(pos + i))
        }
        src.position(pos + size)
    }

    override fun onFlush() {
        super.onFlush()
        currentGain = 1.0
    }

    override fun onReset() {
        super.onReset()
        enabled = false
        currentGain = 1.0
    }
}
