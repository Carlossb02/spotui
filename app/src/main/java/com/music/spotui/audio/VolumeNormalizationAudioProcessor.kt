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

/**
 * Media3 [AudioProcessor] providing transparent linear track volume normalization
 * for PCM 16-bit audio streams.
 *
 * Normalizes tracks to standard reference loudness (-14 LUFS) using pure linear
 * scaling without any compression, waveshaping (tanh), or coloration, preserving
 * the exact original sound while boosting quiet tracks upward or lowering loud tracks.
 */
@UnstableApi
class VolumeNormalizationAudioProcessor : BaseAudioProcessor() {

    @Volatile
    var enabled: Boolean = false

    private val targetRms = 0.20

    @Volatile
    private var trackTargetGain = 1.0

    @Volatile
    private var currentGain = 1.0

    @Volatile
    private var isMetadataDriven = false

    private val maxSlewPerSample = 0.0000005

    private var accumulatedSumSquares = 0.0
    private var accumulatedSampleCount = 0L

    private var sampleRate = 0
    private var channelCount = 0

    /**
     * Set explicit track loudness in dB relative to -14 LUFS target.
     * Allows upward boost for quiet tracks (up to 6.0x / +15.5 dB) and downward scaling for loud tracks.
     */
    fun setTrackLoudness(loudnessDb: Double?) {
        if (loudnessDb != null && !loudnessDb.isNaN() && !loudnessDb.isInfinite()) {
            // Pure linear gain factor: 10^(-loudnessDb / 20)
            // Coerce in range [0.1, 6.0] to support powerful upward boosting ("igualar al alza")
            val gainFactor = 10.0.pow(-loudnessDb / 20.0).coerceIn(0.1, 6.0)
            trackTargetGain = gainFactor
            currentGain = gainFactor
            isMetadataDriven = true
        } else {
            resetToFallbackEstimation()
        }
    }

    fun setTrackGainDb(gainDb: Double?) {
        if (gainDb != null && !gainDb.isNaN() && !gainDb.isInfinite()) {
            val gainFactor = 10.0.pow(gainDb / 20.0).coerceIn(0.1, 6.0)
            trackTargetGain = gainFactor
            currentGain = gainFactor
            isMetadataDriven = true
        } else {
            resetToFallbackEstimation()
        }
    }

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

                if (accumulatedSampleCount >= sampleRate * channelCount) {
                    val measuredRms = sqrt(accumulatedSumSquares / accumulatedSampleCount)
                    if (measuredRms > 0.001) {
                        trackTargetGain = (targetRms / measuredRms).coerceIn(0.2, 4.0)
                    }
                }
            }
        }

        val targetG = trackTargetGain
        var cg = currentGain

        while (inputBuffer.remaining() >= 2) {
            if (cg != targetG) {
                val diff = targetG - cg
                if (abs(diff) <= maxSlewPerSample) {
                    cg = targetG
                } else {
                    cg += if (diff > 0) maxSlewPerSample else -maxSlewPerSample
                }
            }

            val rawSample = inputBuffer.short.toDouble() / 32768.0
            // Pure linear amplification: zero compression, zero tanh waveshaping
            val scaled = rawSample * cg

            val shortSample = (scaled * 32767.0).coerceIn(-32768.0, 32767.0).toInt().toShort()
            output.putShort(shortSample)
        }
        currentGain = cg

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
