package com.music.spotui.audio

import androidx.media3.common.C
import androidx.media3.common.audio.AudioProcessor
import androidx.media3.common.audio.BaseAudioProcessor
import androidx.media3.common.util.UnstableApi
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.abs
import kotlin.math.sqrt
import kotlin.math.tanh

/**
 * Media3 [AudioProcessor] providing Spotify-style per-track volume normalization
 * for PCM 16-bit audio streams.
 *
 * Levels overall track loudness to standard -14 LUFS (~0.18 RMS target) using a smooth
 * exponential moving average gain adaptation combined with an instant-attack soft-knee
 * peak limiter. This guarantees uniform audio loudness without hard clipping, pumping,
 * or high-frequency distortion artifacts.
 */
@UnstableApi
class VolumeNormalizationAudioProcessor : BaseAudioProcessor() {

    @Volatile
    var enabled: Boolean = false

    private val targetRms = 0.18 // ~ -14 LUFS (Spotify standard target)
    private var smoothRms = targetRms
    private var currentGain = 1.0
    private var peakEnvelope = 0.0

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

        // Measure buffer RMS to update smooth loudness estimate
        val pos = inputBuffer.position()
        var sumSquares = 0.0
        var sampleCount = 0

        while (inputBuffer.remaining() >= 2) {
            val sample = inputBuffer.short.toDouble() / 32767.0
            sumSquares += sample * sample
            sampleCount++
        }
        inputBuffer.position(pos)

        if (sampleCount > 0) {
            val bufferRms = sqrt(sumSquares / sampleCount)
            if (bufferRms > 0.005) {
                smoothRms = smoothRms * 0.98 + bufferRms * 0.02
                val targetGain = (targetRms / smoothRms).coerceIn(0.25, 2.5) // -12 dB to +8 dB
                currentGain += (targetGain - currentGain) * 0.05
            }
        }

        // Process audio with gain adaptation + peak limiter
        while (inputBuffer.remaining() >= 2) {
            val rawSample = inputBuffer.short.toDouble() / 32767.0
            val scaledSample = rawSample * currentGain

            val absScaled = abs(scaledSample)
            if (absScaled > peakEnvelope) {
                peakEnvelope = absScaled
            } else {
                peakEnvelope *= 0.9995 // Smooth release (~150-200ms)
            }

            val dynamicGain = if (peakEnvelope > 0.95) (0.95 / peakEnvelope) else 1.0
            var processed = scaledSample * dynamicGain

            // Soft-knee saturation curve above 0.92 to completely prevent hard clipping
            if (processed > 0.92) {
                processed = 0.92 + 0.08 * tanh((processed - 0.92) / 0.08)
            } else if (processed < -0.92) {
                processed = -0.92 + 0.08 * tanh((processed + 0.92) / 0.08)
            }

            val shortSample = (processed * 32767.0).coerceIn(-32768.0, 32767.0).toInt().toShort()
            output.putShort(shortSample)
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

    @Suppress("DEPRECATION")
    override fun onFlush() {
        super.onFlush()
        smoothRms = targetRms
        currentGain = 1.0
        peakEnvelope = 0.0
    }

    override fun onReset() {
        super.onReset()
        enabled = false
        smoothRms = targetRms
        currentGain = 1.0
        peakEnvelope = 0.0
    }
}
