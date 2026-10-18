package com.music.spotui.audio

import androidx.media3.common.C
import androidx.media3.common.audio.AudioProcessor
import com.music.spotui.data.preferences.NormalizationMode
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.abs

@Suppress("DEPRECATION")
@androidx.media3.common.util.UnstableApi
class VolumeNormalizationAudioProcessorTest {

    private lateinit var normalizer: VolumeNormalizationAudioProcessor

    @Before
    fun setUp() {
        normalizer = VolumeNormalizationAudioProcessor()
        normalizer.enabled = true
        normalizer.mode = NormalizationMode.SUAVE
        val format = AudioProcessor.AudioFormat(44100, 2, C.ENCODING_PCM_16BIT)
        normalizer.configure(format)
        normalizer.flush()
    }

    @Test
    fun testDownwardLoudnessInSuaveMode() {
        // Track is 3.5 dB louder than -14 LUFS target (+3.5 dB loudness)
        // Gain should be 10^(-3.5 / 20) = ~0.6683439
        normalizer.mode = NormalizationMode.SUAVE
        normalizer.setTrackLoudness(3.5)

        val inputSampleCount = 100
        val inputBuffer = ByteBuffer.allocateDirect(inputSampleCount * 2).order(ByteOrder.nativeOrder())
        val testValue: Short = 10000

        repeat(inputSampleCount) { inputBuffer.putShort(testValue) }
        inputBuffer.flip()

        normalizer.queueInput(inputBuffer)
        val outputBuffer = normalizer.output

        val firstSample = outputBuffer.short
        val expectedSample = (testValue * 0.6683439).toInt().toShort()

        assertTrue("Expected sample near $expectedSample but got $firstSample", abs(firstSample - expectedSample) <= 2)
    }

    @Test
    fun testSuaveModeCapsPositiveGainWhenTruePeakIsMissing() {
        // Quiet track (-6.0 dB relative to target) without true peak in SUAVE mode should cap gain at 0.0 dB
        normalizer.mode = NormalizationMode.SUAVE
        normalizer.setTrackLoudness(-6.0)

        val inputSampleCount = 100
        val inputBuffer = ByteBuffer.allocateDirect(inputSampleCount * 2).order(ByteOrder.nativeOrder())
        val testValue: Short = 10000

        repeat(inputSampleCount) { inputBuffer.putShort(testValue) }
        inputBuffer.flip()

        normalizer.queueInput(inputBuffer)
        val outputBuffer = normalizer.output

        val firstSample = outputBuffer.short
        assertEquals("SUAVE mode should cap positive gain at 0 dB when true peak is absent", testValue, firstSample)
    }

    @Test
    fun testBalancedBetaModeAppliesPositiveGainWithoutTruePeak() {
        // Quiet track (-6.0 dB relative to target) in BALANCED_BETA mode applies full linear gain (~1.99526)
        normalizer.mode = NormalizationMode.BALANCED_BETA
        normalizer.setTrackLoudness(-6.0)

        val inputSampleCount = 100
        val inputBuffer = ByteBuffer.allocateDirect(inputSampleCount * 2).order(ByteOrder.nativeOrder())
        val testValue: Short = 10000

        repeat(inputSampleCount) { inputBuffer.putShort(testValue) }
        inputBuffer.flip()

        normalizer.queueInput(inputBuffer)
        val outputBuffer = normalizer.output

        val outputSample = outputBuffer.short
        val expectedSample = (testValue * 1.9952623).toInt().toShort()

        assertTrue("Balanced Beta mode did not boost quiet track! Expected ~$expectedSample, got $outputSample", abs(outputSample - expectedSample) <= 2)
    }

    @Test
    fun testDisabledModeOutputsOriginalAudio() {
        normalizer.enabled = false
        normalizer.mode = NormalizationMode.OFF
        normalizer.setTrackLoudness(4.0)

        val inputSampleCount = 100
        val inputBuffer = ByteBuffer.allocateDirect(inputSampleCount * 2).order(ByteOrder.nativeOrder())
        val testValue: Short = 12000

        repeat(inputSampleCount) { inputBuffer.putShort(testValue) }
        inputBuffer.flip()

        normalizer.queueInput(inputBuffer)
        val outputBuffer = normalizer.output

        val sample = outputBuffer.short
        assertEquals("Disabled mode must not modify samples", testValue, sample)
    }
}
