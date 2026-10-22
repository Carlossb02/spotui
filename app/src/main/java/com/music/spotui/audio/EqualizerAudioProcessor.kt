package com.music.spotui.audio

import androidx.media3.common.C
import androidx.media3.common.audio.AudioProcessor
import androidx.media3.common.audio.BaseAudioProcessor
import androidx.media3.common.util.UnstableApi
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.log10
import kotlin.math.min
import kotlin.math.pow
import kotlin.math.roundToInt
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * Media3 [AudioProcessor] providing a high-fidelity 6-band parametric equalizer matching Spotify's audio DSP.
 * Bands: 60 Hz, 150 Hz, 400 Hz, 1.0 kHz, 2.4 kHz, 15.0 kHz.
 * Supports standard presets, custom gain adjustments (-12 dB to +12 dB per band), dynamic headroom
 * scaling, soft-knee peak limiting, anti-zipper parameter ramping, and support for PCM 16-bit and 32-bit Float.
 */
@UnstableApi
class EqualizerAudioProcessor : BaseAudioProcessor() {

    @Volatile
    var enabled: Boolean = false

    private var sampleRate = 0
    private var channelCount = 0
    private var encoding = C.ENCODING_INVALID

    // 6-band gain settings in dB (-12.0 to +12.0)
    private val bandGains = FloatArray(BAND_COUNT) { 0f }

    // Headroom linear gain factor (0.0..1.0)
    @Volatile
    private var headroomLinearGain = 1.0

    // Filters for Left and Right channels
    private val filtersLeft = Array(BAND_COUNT) { EqBiquad() }
    private val filtersRight = Array(BAND_COUNT) { EqBiquad() }

    enum class FilterType { LOW_SHELF, PEAKING, HIGH_SHELF }

    companion object {
        const val BAND_COUNT = 6

        val FREQUENCIES = floatArrayOf(60f, 150f, 400f, 1000f, 2400f, 15000f)
        val FREQUENCY_LABELS = arrayOf("60 Hz", "150 Hz", "400 Hz", "1 kHz", "2.4 kHz", "15 kHz")

        val BAND_FILTER_TYPES = arrayOf(
            FilterType.LOW_SHELF,
            FilterType.PEAKING,
            FilterType.PEAKING,
            FilterType.PEAKING,
            FilterType.PEAKING,
            FilterType.HIGH_SHELF,
        )

        val BAND_Q_FACTORS = floatArrayOf(0.707f, 1.2f, 1.2f, 1.2f, 1.2f, 0.707f)

        val PRESETS: Map<String, FloatArray> = mapOf(
            "Flat" to floatArrayOf(0f, 0f, 0f, 0f, 0f, 0f),
            "Bass Booster" to floatArrayOf(6f, 4.5f, 2f, 0f, 0f, 0f),
            "Bass Reducer" to floatArrayOf(-6f, -4.5f, -2f, 0f, 0f, 0f),
            "Treble Booster" to floatArrayOf(0f, 0f, 0f, 2f, 4.5f, 6f),
            "Treble Reducer" to floatArrayOf(0f, 0f, 0f, -2f, -4.5f, -6f),
            "Vocal Booster" to floatArrayOf(-2f, -1f, 2f, 4f, 3f, 0f),
            "Acoustic" to floatArrayOf(3.5f, 2.5f, 1f, 2f, 3f, 2.5f),
            "Classical" to floatArrayOf(4.5f, 3f, 1f, 1f, 2.5f, 3.5f),
            "Dance" to floatArrayOf(5f, 4f, 1.5f, 0f, 2.5f, 4f),
            "Deep" to floatArrayOf(6f, 3.5f, 1f, -1f, -2f, -3f),
            "Electronic" to floatArrayOf(4.5f, 3.5f, 0f, 1.5f, 3f, 4.5f),
            "Hip-Hop" to floatArrayOf(5.5f, 4f, 1f, 2f, 1f, 3.5f),
            "Jazz" to floatArrayOf(3.5f, 2.5f, -1f, 1.5f, 2.5f, 3.5f),
            "Latin" to floatArrayOf(4f, 2.5f, 0f, 1f, 2.5f, 4f),
            "Loudness" to floatArrayOf(5f, 3f, -1f, 0f, 2f, 4.5f),
            "Lounge" to floatArrayOf(-2f, 2f, 3.5f, 2f, -1f, -2f),
            "Piano" to floatArrayOf(2.5f, 1.5f, 1f, 2.5f, 3f, 2f),
            "Pop" to floatArrayOf(-1f, 2f, 4f, 3f, 1f, -1f),
            "R&B" to floatArrayOf(5f, 4f, 1f, 1.5f, 3f, 4f),
            "Rock" to floatArrayOf(4.5f, 3f, -1f, 2.5f, 3.5f, 4.5f),
            "Small Speakers" to floatArrayOf(5f, 3.5f, 1f, -1.5f, -3f, -4.5f),
            "Spoken Word" to floatArrayOf(-3f, 0f, 3f, 3.5f, 2f, -2f),
        )
    }

    fun setBandGains(gains: FloatArray) {
        val count = min(gains.size, BAND_COUNT)
        for (i in 0 until count) {
            bandGains[i] = gains[i].coerceIn(-12f, 12f)
        }
        for (i in count until BAND_COUNT) {
            bandGains[i] = 0f
        }
        updateAllCoefficients()
    }

    fun setPreset(presetName: String) {
        val gains = PRESETS[presetName] ?: PRESETS["Flat"]!!
        setBandGains(gains)
    }

    /**
     * Returns the frequency magnitude response in dB at a specified frequency [freqHz]
     * for the current equalizer gain settings.
     */
    fun getFrequencyResponse(freqHz: Float, customSampleRate: Int = 44100): Float {
        var totalDb = 0f
        for (i in 0 until BAND_COUNT) {
            val gainDb = bandGains[i]
            if (gainDb == 0f) continue
            val biquad = EqBiquad()
            biquad.updateCoefficients(
                FREQUENCIES[i],
                gainDb,
                customSampleRate,
                BAND_FILTER_TYPES[i],
                BAND_Q_FACTORS[i]
            )
            totalDb += biquad.getMagnitudeDbAt(freqHz, customSampleRate)
        }
        return totalDb
    }

    private fun updateAllCoefficients() {
        // Calculate dynamic headroom attenuation: if any gain > 0 dB, pre-attenuate by max positive gain
        val maxPositiveGainDb = bandGains.maxOrNull()?.coerceAtLeast(0f) ?: 0f
        headroomLinearGain = if (maxPositiveGainDb > 0f) {
            10.0.pow((-maxPositiveGainDb / 20.0))
        } else {
            1.0
        }

        if (sampleRate <= 0) return
        for (i in 0 until BAND_COUNT) {
            val freq = FREQUENCIES[i]
            val type = BAND_FILTER_TYPES[i]
            val q = BAND_Q_FACTORS[i]
            val gainDb = bandGains[i]
            filtersLeft[i].updateCoefficients(freq, gainDb, sampleRate, type, q)
            filtersRight[i].updateCoefficients(freq, gainDb, sampleRate, type, q)
        }
    }

    override fun onConfigure(inputAudioFormat: AudioProcessor.AudioFormat): AudioProcessor.AudioFormat {
        if (inputAudioFormat.encoding != C.ENCODING_PCM_16BIT &&
            inputAudioFormat.encoding != C.ENCODING_PCM_FLOAT
        ) {
            return AudioProcessor.AudioFormat.NOT_SET
        }
        sampleRate = inputAudioFormat.sampleRate
        channelCount = inputAudioFormat.channelCount
        encoding = inputAudioFormat.encoding
        updateAllCoefficients()
        return inputAudioFormat
    }

    override fun queueInput(inputBuffer: ByteBuffer) {
        val remaining = inputBuffer.remaining()
        if (remaining == 0 || channelCount <= 0) return

        if (!enabled || sampleRate == 0) {
            val output = replaceOutputBuffer(remaining)
            copyBuffer(inputBuffer, output, remaining)
            output.flip()
            return
        }

        inputBuffer.order(ByteOrder.nativeOrder())
        val output = replaceOutputBuffer(remaining)
        output.order(ByteOrder.nativeOrder())

        val hrGain = headroomLinearGain

        if (encoding == C.ENCODING_PCM_16BIT) {
            if (channelCount == 1) {
                while (inputBuffer.remaining() >= 2) {
                    var sample = (inputBuffer.short.toDouble() / 32768.0) * hrGain
                    for (i in 0 until BAND_COUNT) {
                        sample = filtersLeft[i].process(sample)
                    }
                    output.putShort(softClip16(sample))
                }
            } else {
                while (inputBuffer.remaining() >= 4) {
                    var left = (inputBuffer.short.toDouble() / 32768.0) * hrGain
                    var right = (inputBuffer.short.toDouble() / 32768.0) * hrGain

                    for (i in 0 until BAND_COUNT) {
                        left = filtersLeft[i].process(left)
                        right = filtersRight[i].process(right)
                    }

                    output.putShort(softClip16(left))
                    output.putShort(softClip16(right))
                }
            }
        } else if (encoding == C.ENCODING_PCM_FLOAT) {
            if (channelCount == 1) {
                while (inputBuffer.remaining() >= 4) {
                    var sample = inputBuffer.float.toDouble() * hrGain
                    for (i in 0 until BAND_COUNT) {
                        sample = filtersLeft[i].process(sample)
                    }
                    output.putFloat(softClipFloat(sample))
                }
            } else {
                while (inputBuffer.remaining() >= 8) {
                    var left = inputBuffer.float.toDouble() * hrGain
                    var right = inputBuffer.float.toDouble() * hrGain

                    for (i in 0 until BAND_COUNT) {
                        left = filtersLeft[i].process(left)
                        right = filtersRight[i].process(right)
                    }

                    output.putFloat(softClipFloat(left))
                    output.putFloat(softClipFloat(right))
                }
            }
        }

        output.flip()
    }

    private fun softClip16(scaled: Double): Short {
        val clipped = when {
            scaled > 0.9 -> 0.9 + 0.1 * kotlin.math.tanh((scaled - 0.9) / 0.1)
            scaled < -0.9 -> -0.9 + 0.1 * kotlin.math.tanh((scaled + 0.9) / 0.1)
            else -> scaled
        }
        return (clipped * 32767.0).roundToInt().coerceIn(-32768, 32767).toShort()
    }

    private fun softClipFloat(scaled: Double): Float {
        val clipped = when {
            scaled > 0.9 -> 0.9 + 0.1 * kotlin.math.tanh((scaled - 0.9) / 0.1)
            scaled < -0.9 -> -0.9 + 0.1 * kotlin.math.tanh((scaled + 0.9) / 0.1)
            else -> scaled
        }
        return clipped.toFloat()
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
        for (i in 0 until BAND_COUNT) {
            filtersLeft[i].reset()
            filtersRight[i].reset()
        }
    }

    override fun onReset() {
        super.onReset()
        enabled = false
        encoding = C.ENCODING_INVALID
        for (i in 0 until BAND_COUNT) {
            filtersLeft[i].reset()
            filtersRight[i].reset()
        }
    }

    /**
     * Biquad Filter with smooth parameter ramping and magnitude response calculation.
     */
    private class EqBiquad {
        // Target coefficients
        private var targetB0 = 1.0
        private var targetB1 = 0.0
        private var targetB2 = 0.0
        private var targetA1 = 0.0
        private var targetA2 = 0.0

        // Active smoothed coefficients
        private var b0 = 1.0
        private var b1 = 0.0
        private var b2 = 0.0
        private var a1 = 0.0
        private var a2 = 0.0

        private var x1 = 0.0
        private var x2 = 0.0
        private var y1 = 0.0
        private var y2 = 0.0

        fun updateCoefficients(
            freqHz: Float,
            gainDb: Float,
            sampleRate: Int,
            type: FilterType,
            qFactor: Float = 1.0f
        ) {
            if (gainDb == 0f) {
                targetB0 = 1.0; targetB1 = 0.0; targetB2 = 0.0
                targetA1 = 0.0; targetA2 = 0.0
                return
            }

            val A = 10.0.pow(gainDb / 40.0)
            val w0 = 2.0 * PI * freqHz.coerceIn(20f, (sampleRate / 2f) - 10f) / sampleRate
            val cosW0 = cos(w0)
            val sinW0 = sin(w0)
            val Q = qFactor.toDouble().coerceAtLeast(0.1)
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

            targetB0 = rawB0 / rawA0
            targetB1 = rawB1 / rawA0
            targetB2 = rawB2 / rawA0
            targetA1 = rawA1 / rawA0
            targetA2 = rawA2 / rawA0

            // If filter was newly reset, jump immediately to target coefficients
            if (b0 == 1.0 && b1 == 0.0 && b2 == 0.0 && a1 == 0.0 && a2 == 0.0) {
                b0 = targetB0; b1 = targetB1; b2 = targetB2
                a1 = targetA1; a2 = targetA2
            }
        }

        fun process(input: Double): Double {
            // Anti-zipper coefficient smoothing (1st order low-pass transition)
            val alpha = 0.01
            b0 += (targetB0 - b0) * alpha
            b1 += (targetB1 - b1) * alpha
            b2 += (targetB2 - b2) * alpha
            a1 += (targetA1 - a1) * alpha
            a2 += (targetA2 - a2) * alpha

            val output = b0 * input + b1 * x1 + b2 * x2 - a1 * y1 - a2 * y2
            x2 = x1; x1 = input
            y2 = y1; y1 = output
            return output
        }

        fun getMagnitudeDbAt(freqHz: Float, sampleRate: Int): Float {
            val w = 2.0 * PI * freqHz / sampleRate
            val cosW = cos(w)
            val sinW = sin(w)
            val cos2W = cos(2.0 * w)
            val sin2W = sin(2.0 * w)

            val numReal = targetB0 + targetB1 * cosW + targetB2 * cos2W
            val numImg = -(targetB1 * sinW + targetB2 * sin2W)
            val denReal = 1.0 + targetA1 * cosW + targetA2 * cos2W
            val denImg = -(targetA1 * sinW + targetA2 * sin2W)

            val numMagSq = numReal * numReal + numImg * numImg
            val denMagSq = denReal * denReal + denImg * denImg

            if (denMagSq == 0.0) return 0f
            return (10.0 * log10(numMagSq / denMagSq)).toFloat()
        }

        fun reset() {
            b0 = 1.0; b1 = 0.0; b2 = 0.0; a1 = 0.0; a2 = 0.0
            targetB0 = 1.0; targetB1 = 0.0; targetB2 = 0.0; targetA1 = 0.0; targetA2 = 0.0
            x1 = 0.0; x2 = 0.0; y1 = 0.0; y2 = 0.0
        }
    }
}
