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
 * Media3 [AudioProcessor] providing Spotify-style stable track volume normalization
 * for PCM 16-bit audio streams.
 *
 * Targets a consistent loudness (-12 to -14 LUFS equivalent) with a stable gain factor
 * that avoids mid-song volume jumps/pumping and prevents digital distortion or low volume.
 */
@UnstableApi
class VolumeNormalizationAudioProcessor : BaseAudioProcessor() {

    @Volatile
    var enabled: Boolean = false

    private val targetRms = 0.20 // Standard Spotify/YouTube balanced loudness target
    private var smoothRms = targetRms
    private var currentGain = 1.0
    private var peakEnvelope = 0.0
    private var isCalibrated = false
    private var calibrationBufferCount = 0

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

        // Measure buffer RMS
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
                if (!isCalibrated) {
                    // Fast initial adaptation during the first few buffers to establish baseline
                    smoothRms = smoothRms * 0.9 + bufferRms * 0.1
                    calibrationBufferCount++
                    if (calibrationBufferCount > 15) {
                        isCalibrated = true
                    }
                } else {
                    // Ultra-slow adaptation once calibrated to completely prevent mid-song volume changes/pumping
                    smoothRms = smoothRms * 0.9995 + bufferRms * 0.0005
                }
                val targetGain = (targetRms / smoothRms).coerceIn(0.7, 1.3) // Safe, distortion-free gain range (-3 dB to +2.3 dB)
                currentGain += (targetGain - currentGain) * 0.02
            }
        }

        // Process audio with stable gain + transparent peak limiter
        while (inputBuffer.remaining() >= 2) {
            val rawSample = inputBuffer.short.toDouble() / 32767.0
            val scaledSample = rawSample * currentGain

            val absScaled = abs(scaledSample)
            if (absScaled > peakEnvelope) {
                peakEnvelope = absScaled
            } else {
                peakEnvelope *= 0.9999 // Smooth release
            }

            val dynamicGain = if (peakEnvelope > 0.96) (0.96 / peakEnvelope) else 1.0
            var processed = scaledSample * dynamicGain

            // Gentle soft-knee saturation above 0.95 to eliminate any possibility of harsh clipping/distortion
            if (processed > 0.95) {
                processed = 0.95 + 0.05 * tanh((processed - 0.95) / 0.05)
            } else if (processed < -0.95) {
                processed = -0.95 + 0.05 * tanh((processed + 0.95) / 0.05)
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
        isCalibrated = false
        calibrationBufferCount = 0
    }

    override fun onReset() {
        super.onReset()
        enabled = false
        smoothRms = targetRms
        currentGain = 1.0
        peakEnvelope = 0.0
        isCalibrated = false
        calibrationBufferCount = 0
    }
}
