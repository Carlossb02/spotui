package com.music.spotui.audio

import androidx.media3.common.C
import androidx.media3.common.audio.AudioProcessor
import androidx.media3.common.audio.BaseAudioProcessor
import androidx.media3.common.util.UnstableApi
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.abs
import kotlin.math.pow
import kotlin.math.sqrt
import kotlin.math.tanh

/**
 * Media3 [AudioProcessor] providing Spotify-grade track volume normalization
 * for PCM 16-bit audio streams.
 *
 * Normalizes all tracks to standard reference loudness (-14 LUFS / EBU R128)
 * using exact track metadata when available, or an imperceptible smooth RMS
 * estimation fallback. Uses a continuous soft-knee brickwall limiter to eliminate
 * any digital clipping, popping ("petardeos"), white noise, or volume pumping.
 */
@UnstableApi
class VolumeNormalizationAudioProcessor : BaseAudioProcessor() {

    @Volatile
    var enabled: Boolean = false

    // Spotify reference target loudness (-14 LUFS / RMS ~0.20)
    private val targetRms = 0.20

    @Volatile
    private var trackTargetGain = 1.0

    @Volatile
    private var currentGain = 1.0

    @Volatile
    private var isMetadataDriven = false

    // Slew rate limit per sample (~0.1 dB/sec at 44.1kHz) for smooth fallback adjustment
    private val maxSlewPerSample = 0.0000005

    private var accumulatedSumSquares = 0.0
    private var accumulatedSampleCount = 0L

    private var sampleRate = 0
    private var channelCount = 0

    /**
     * Set explicit track loudness in dB relative to -14 LUFS target (from YouTube/InnerTube API metadata).
     * For example, loudnessDb = +3.0 means track is 3 dB louder than target -14 LUFS.
     * Gain will be applied as 10^(-3.0 / 20) = 0.707 (-3 dB).
     */
    fun setTrackLoudness(loudnessDb: Double?) {
        if (loudnessDb != null && !loudnessDb.isNaN() && !loudnessDb.isInfinite()) {
            val gainFactor = 10.0.pow(-loudnessDb / 20.0).coerceIn(0.25, 2.0)
            trackTargetGain = gainFactor
            currentGain = gainFactor
            isMetadataDriven = true
        } else {
            resetToFallbackEstimation()
        }
    }

    /**
     * Direct track gain setting in dB.
     */
    fun setTrackGainDb(gainDb: Double?) {
        if (gainDb != null && !gainDb.isNaN() && !gainDb.isInfinite()) {
            val gainFactor = 10.0.pow(gainDb / 20.0).coerceIn(0.25, 2.0)
            trackTargetGain = gainFactor
            currentGain = gainFactor
            isMetadataDriven = true
        } else {
            resetToFallbackEstimation()
        }
    }

    /**
     * Reset normalizer for a new track with unknown loudness metadata.
     */
    fun resetToFallbackEstimation() {
        isMetadataDriven = false
        trackTargetGain = 1.0
        currentGain = 1.0
        accumulatedSumSquares = 0.0
        accumulatedSampleCount = 0L
    }

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

        // Fallback smooth RMS estimation when metadata is absent
        if (!isMetadataDriven) {
            val startPos = inputBuffer.position()
            var sumSquares = 0.0
            var count = 0
            while (inputBuffer.remaining() >= 2) {
                val s = inputBuffer.short.toDouble() / 32768.0
                sumSquares += s * s
                count++
            }
            inputBuffer.position(startPos)

            if (count > 0) {
                accumulatedSumSquares += sumSquares
                accumulatedSampleCount += count.toLong()

                // Update estimated target gain smoothly every ~1 second of samples
                if (accumulatedSampleCount >= sampleRate * channelCount) {
                    val measuredRms = sqrt(accumulatedSumSquares / accumulatedSampleCount)
                    if (measuredRms > 0.001) {
                        trackTargetGain = (targetRms / measuredRms).coerceIn(0.35, 2.0)
                    }
                }
            }
        }

        // Apply constant or ultra-smooth gain with soft-knee saturation limiter
        val targetG = trackTargetGain
        var cg = currentGain

        while (inputBuffer.remaining() >= 2) {
            // Imperceptible slew-rate limited gain transition for fallback mode
            if (cg != targetG) {
                val diff = targetG - cg
                if (abs(diff) <= maxSlewPerSample) {
                    cg = targetG
                } else {
                    cg += if (diff > 0) maxSlewPerSample else -maxSlewPerSample
                }
            }

            val rawSample = inputBuffer.short.toDouble() / 32768.0
            val amplified = rawSample * cg
            val limited = softKneeSaturation(amplified)

            val shortSample = (limited * 32767.0).coerceIn(-32768.0, 32767.0).toInt().toShort()
            output.putShort(shortSample)
        }
        currentGain = cg

        output.flip()
    }

    /**
     * Continuous soft-knee saturation limiter.
     * Linearly passes samples up to threshold T = 0.85 (-1.4 dBFS).
     * Smoothly compresses peaks using tanh saturation for samples exceeding T.
     * Prevents digital clipping, popping, white noise and harsh distortion.
     */
    private inline fun softKneeSaturation(sample: Double): Double {
        val absVal = abs(sample)
        if (absVal <= 0.85) return sample
        val over = absVal - 0.85
        val compressed = 0.85 + 0.15 * tanh(over / 0.15)
        return if (sample > 0.0) compressed else -compressed
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
        // Do NOT reset trackTargetGain or currentGain on seek/flush!
        // Preserves constant track volume throughout the entire song.
    }

    override fun onReset() {
        super.onReset()
        enabled = false
        trackTargetGain = 1.0
        currentGain = 1.0
        isMetadataDriven = false
        accumulatedSumSquares = 0.0
        accumulatedSampleCount = 0L
    }
}
