package com.music.spotui.audio

import androidx.media3.common.C
import androidx.media3.common.audio.AudioProcessor
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
        val format = AudioProcessor.AudioFormat(44100, 2, C.ENCODING_PCM_16BIT)
        normalizer.configure(format)
        normalizer.flush()
        normalizer.enabled = true
    }

    @Test
    fun testDownwardLoudnessMetadataConstantGain() {
        // Track is 3.5 dB louder than -14 LUFS target (+3.5 dB loudness)
        // Gain should be 10^(-3.5 / 20) = ~0.6683
        normalizer.setTrackLoudness(3.5)

        val inputSampleCount = 1000
        val inputBuffer = ByteBuffer.allocateDirect(inputSampleCount * 2).order(ByteOrder.nativeOrder())
        val testValue: Short = 10000

        repeat(inputSampleCount) {
            inputBuffer.putShort(testValue)
        }
        inputBuffer.flip()

        normalizer.queueInput(inputBuffer)
        val outputBuffer = normalizer.output

        val firstSample = outputBuffer.short
        val expectedSample = (testValue * 0.6683439).toInt().toShort()

        assertTrue("Expected sample near $expectedSample but got $firstSample", abs(firstSample - expectedSample) <= 2)
    }

    @Test
    fun testUpwardBoostingEqualization() {
        // Quiet track (-6.0 dB relative to target, requires ~2.0x boost upward)
        // Gain = 10^(6.0 / 20) = 1.99526
        normalizer.setTrackLoudness(-6.0)

        val inputBuffer = ByteBuffer.allocateDirect(100 * 2).order(ByteOrder.nativeOrder())
        val testValue: Short = 10000
        repeat(50) {
            inputBuffer.putShort(testValue)
        }
        inputBuffer.flip()

        normalizer.queueInput(inputBuffer)
        val outputBuffer = normalizer.output

        val outputSample = outputBuffer.short
        val expectedSample = (testValue * 1.9952623).toInt().toShort()

        assertTrue("Upward boost did not match linear gain! Expected ~ $expectedSample, got $outputSample", abs(outputSample - expectedSample) <= 2)
    }

    @Test
    fun testSeekDoesNotResetGain() {
        normalizer.setTrackLoudness(4.0)

        // Simulate seek / buffer flush
        normalizer.flush()

        val inputBuffer = ByteBuffer.allocateDirect(100 * 2).order(ByteOrder.nativeOrder())
        val testValue: Short = 12000
        repeat(50) {
            inputBuffer.putShort(testValue)
        }
        inputBuffer.flip()

        normalizer.queueInput(inputBuffer)
        val outputBuffer = normalizer.output

        val sampleAfterSeek = outputBuffer.short
        val expectedSample = (testValue * 0.630957).toInt().toShort() // 10^(-4/20) = 0.630957

        assertTrue("Seek reset gain unexpectedly!", abs(sampleAfterSeek - expectedSample) <= 2)
    }
}
