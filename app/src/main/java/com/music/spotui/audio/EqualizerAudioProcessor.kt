package com.music.spotui.audio

import androidx.media3.common.C
import androidx.media3.common.audio.AudioProcessor
import androidx.media3.common.audio.BaseAudioProcessor
import androidx.media3.common.util.UnstableApi
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.pow
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * Media3 [AudioProcessor] providing a 5-band parametric equalizer matching Spotify.
 * Bands: 60 Hz, 230 Hz, 910 Hz, 3.6 kHz, 14 kHz.
 * Supports standard presets and custom gain adjustments (-12 dB to +12 dB per band).
 */
@UnstableApi
class EqualizerAudioProcessor : BaseAudioProcessor() {

    @Volatile
    var enabled: Boolean = false

    private var sampleRate = 0
    private var channelCount = 0

    // 5-band gain settings in dB (-12.0 to +12.0)
    private val bandGains = FloatArray(5) { 0f }

    // 5 filters for Left and 5 filters for Right
    private val filtersLeft = Array(5) { EqBiquad() }
    private val filtersRight = Array(5) { EqBiquad() }

    enum class FilterType { LOW_SHELF, PEAKING, HIGH_SHELF }

    private val bandFrequencies = floatArrayOf(60f, 230f, 910f, 3600f, 14000f)
    private val bandFilterTypes = arrayOf(
        FilterType.LOW_SHELF,
        FilterType.PEAKING,
        FilterType.PEAKING,
        FilterType.PEAKING,
        FilterType.HIGH_SHELF,
    )

    companion object {
        val FREQUENCIES = floatArrayOf(60f, 230f, 910f, 3600f, 14000f)
        val FREQUENCY_LABELS = arrayOf("60 Hz", "230 Hz", "910 Hz", "3.6 kHz", "14 kHz")

        val PRESETS: Map<String, FloatArray> = mapOf(
            "Flat" to floatArrayOf(0f, 0f, 0f, 0f, 0f),
            "Bass Booster" to floatArrayOf(6f, 4f, 0f, 0f, 0f),
            "Bass Reducer" to floatArrayOf(-6f, -4f, 0f, 0f, 0f),
            "Treble Booster" to floatArrayOf(0f, 0f, 0f, 4f, 6f),
            "Treble Reducer" to floatArrayOf(0f, 0f, 0f, -4f, -6f),
            "Vocal Booster" to floatArrayOf(-2f, 1f, 4f, 3f, 0f),
            "Acoustic" to floatArrayOf(3f, 1f, 2f, 3f, 2f),
            "Classical" to floatArrayOf(4f, 2f, -1f, 2f, 3f),
            "Dance" to floatArrayOf(5f, 3f, 0f, 3f, 4f),
            "Electronic" to floatArrayOf(4f, 2f, -1f, 2f, 4f),
            "Hip-Hop" to floatArrayOf(5f, 3f, 0f, 1f, 3f),
            "Jazz" to floatArrayOf(3f, 2f, -2f, 2f, 3f),
            "Pop" to floatArrayOf(-1f, 2f, 4f, 2f, -1f),
            "Rock" to floatArrayOf(4f, 2f, -1f, 3f, 4f),
            "Small Speakers" to floatArrayOf(5f, 3f, 0f, -2f, -4f),
        )
    }

    fun setBandGains(gains: FloatArray) {
        require(gains.size == 5)
        for (i in 0 until 5) {
            bandGains[i] = gains[i].coerceIn(-12f, 12f)
        }
        updateAllCoefficients()
    }

    fun setPreset(presetName: String) {
        val gains = PRESETS[presetName] ?: PRESETS["Flat"]!!
        setBandGains(gains)
    }

    private fun updateAllCoefficients() {
        if (sampleRate <= 0) return
        for (i in 0 until 5) {
            val freq = bandFrequencies[i]
            val type = bandFilterTypes[i]
            val gainDb = bandGains[i]
            filtersLeft[i].updateCoefficients(freq, gainDb, sampleRate, type)
            filtersRight[i].updateCoefficients(freq, gainDb, sampleRate, type)
        }
    }

    override fun onConfigure(inputAudioFormat: AudioProcessor.AudioFormat): AudioProcessor.AudioFormat {
        if (inputAudioFormat.encoding != C.ENCODING_PCM_16BIT) {
            return AudioProcessor.AudioFormat.NOT_SET
        }
        sampleRate = inputAudioFormat.sampleRate
        channelCount = inputAudioFormat.channelCount
        updateAllCoefficients()
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

        if (channelCount == 1) {
            while (inputBuffer.remaining() >= 2) {
                var sample = inputBuffer.short.toDouble() / 32767.0
                for (i in 0 until 5) {
                    if (bandGains[i] != 0f) {
                        sample = filtersLeft[i].process(sample)
                    }
                }
                val shortSample = (sample * 32767.0).coerceIn(-32768.0, 32767.0).toInt().toShort()
                output.putShort(shortSample)
            }
        } else {
            while (inputBuffer.remaining() >= 4) {
                var left = inputBuffer.short.toDouble() / 32767.0
                var right = inputBuffer.short.toDouble() / 32767.0

                for (i in 0 until 5) {
                    if (bandGains[i] != 0f) {
                        left = filtersLeft[i].process(left)
                        right = filtersRight[i].process(right)
                    }
                }

                val shortLeft = (left * 32767.0).coerceIn(-32768.0, 32767.0).toInt().toShort()
                val shortRight = (right * 32767.0).coerceIn(-32768.0, 32767.0).toInt().toShort()
                output.putShort(shortLeft)
                output.putShort(shortRight)
            }
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
        for (i in 0 until 5) {
            filtersLeft[i].reset()
            filtersRight[i].reset()
        }
    }

    override fun onReset() {
        super.onReset()
        enabled = false
        for (i in 0 until 5) {
            filtersLeft[i].reset()
            filtersRight[i].reset()
        }
    }

    /**
     * Single-stage Biquad Filter for Peaking, Low Shelf, or High Shelf EQ.
     */
    private class EqBiquad {
        @Volatile private var b0 = 1.0
        @Volatile private var b1 = 0.0
        @Volatile private var b2 = 0.0
        @Volatile private var a1 = 0.0
        @Volatile private var a2 = 0.0

        private var x1 = 0.0
        private var x2 = 0.0
        private var y1 = 0.0
        private var y2 = 0.0

        fun updateCoefficients(freqHz: Float, gainDb: Float, sampleRate: Int, type: FilterType) {
            if (gainDb == 0f) {
                b0 = 1.0; b1 = 0.0; b2 = 0.0
                a1 = 0.0; a2 = 0.0
                return
            }

            val A = 10.0.pow(gainDb / 40.0)
            val w0 = 2.0 * PI * freqHz.coerceIn(20f, (sampleRate / 2f) - 10f) / sampleRate
            val cosW0 = cos(w0)
            val sinW0 = sin(w0)
            val Q = 1.0
            val alpha = sinW0 / (2.0 * Q)

            val rawA0: Double
            val rawA1: Double
            val rawA2: Double
            val rawB0: Double
            val rawB1: Double
            val rawB2: Double

            when (type) {
                FilterType.PEAKING -> {
                    rawB0 = 1.0 + alpha * A
                    rawB1 = -2.0 * cosW0
                    rawB2 = 1.0 - alpha * A
                    rawA0 = 1.0 + alpha / A
                    rawA1 = -2.0 * cosW0
                    rawA2 = 1.0 - alpha / A
                }
                FilterType.LOW_SHELF -> {
                    val beta = sqrt(A) / Q
                    rawB0 = A * ((A + 1.0) - (A - 1.0) * cosW0 + beta * sinW0)
                    rawB1 = 2.0 * A * ((A - 1.0) - (A + 1.0) * cosW0)
                    rawB2 = A * ((A + 1.0) - (A - 1.0) * cosW0 - beta * sinW0)
                    rawA0 = (A + 1.0) + (A - 1.0) * cosW0 + beta * sinW0
                    rawA1 = -2.0 * ((A - 1.0) + (A + 1.0) * cosW0)
                    rawA2 = (A + 1.0) + (A - 1.0) * cosW0 - beta * sinW0
                }
                FilterType.HIGH_SHELF -> {
                    val beta = sqrt(A) / Q
                    rawB0 = A * ((A + 1.0) + (A - 1.0) * cosW0 + beta * sinW0)
                    rawB1 = -2.0 * A * ((A - 1.0) + (A + 1.0) * cosW0)
                    rawB2 = A * ((A + 1.0) + (A - 1.0) * cosW0 - beta * sinW0)
                    rawA0 = (A + 1.0) - (A - 1.0) * cosW0 + beta * sinW0
                    rawA1 = 2.0 * ((A - 1.0) - (A + 1.0) * cosW0)
                    rawA2 = (A + 1.0) - (A - 1.0) * cosW0 - beta * sinW0
                }
            }

            b0 = rawB0 / rawA0
            b1 = rawB1 / rawA0
            b2 = rawB2 / rawA0
            a1 = rawA1 / rawA0
            a2 = rawA2 / rawA0
        }

        fun process(input: Double): Double {
            val output = b0 * input + b1 * x1 + b2 * x2 - a1 * y1 - a2 * y2
            x2 = x1; x1 = input
            y2 = y1; y1 = output
            return output
        }

        fun reset() {
            x1 = 0.0; x2 = 0.0; y1 = 0.0; y2 = 0.0
        }
    }
}
