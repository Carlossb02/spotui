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
    private val lookaheadDelayBytes = 220 * 2 * 2 // 220 frames * 2 channels * 2 bytes/short = 880 bytes

    @Before
    fun setUp() {
        normalizer = VolumeNormalizationAudioProcessor()
        normalizer.enabled = true
        val format = AudioProcessor.AudioFormat(44100, 2, C.ENCODING_PCM_16BIT)
        normalizer.configure(format)
        normalizer.flush()
    }

    @Test
    fun testDownwardLoudnessMetadataConstantGain() {
        // Track is 3.5 dB louder than -14 LUFS target (+3.5 dB loudness)
        // Gain should be 10^(-3.5 / 20) = ~0.6683
        normalizer.setTrackLoudness(3.5)

        val inputSampleCount = 2000 // 1000 frames (sufficient to push through 220-frame lookahead)
        val inputBuffer = ByteBuffer.allocateDirect(inputSampleCount * 2).order(ByteOrder.nativeOrder())
        val testValue: Short = 10000

        repeat(inputSampleCount) {
            inputBuffer.putShort(testValue)
        }
        inputBuffer.flip()

        normalizer.queueInput(inputBuffer)
        val outputBuffer = normalizer.output
        outputBuffer.position(lookaheadDelayBytes)

        val firstSample = outputBuffer.short
        val expectedSample = (testValue * 0.6683439).toInt().toShort()

        assertTrue("Expected sample near $expectedSample but got $firstSample", abs(firstSample - expectedSample) <= 2)
    }

    @Test
    fun testUpwardBoostingEqualization() {
        // Quiet track (-6.0 dB relative to target, requires ~2.0x boost upward)
        // Gain = 10^(6.0 / 20) = 1.99526
        normalizer.setTrackLoudness(-6.0)

        val inputSampleCount = 2000
        val inputBuffer = ByteBuffer.allocateDirect(inputSampleCount * 2).order(ByteOrder.nativeOrder())
        val testValue: Short = 10000
        repeat(inputSampleCount) {
            inputBuffer.putShort(testValue)
        }
        inputBuffer.flip()

        normalizer.queueInput(inputBuffer)
        val outputBuffer = normalizer.output
        outputBuffer.position(lookaheadDelayBytes)

        val outputSample = outputBuffer.short
        val expectedSample = (testValue * 1.9952623).toInt().toShort()

        assertTrue("Upward boost did not match linear gain! Expected ~ $expectedSample, got $outputSample", abs(outputSample - expectedSample) <= 2)
    }

    @Test
    fun testSeekDoesNotResetGain() {
        normalizer.setTrackLoudness(4.0)

        // Simulate seek / buffer flush
        normalizer.flush()

        val inputSampleCount = 2000
        val inputBuffer = ByteBuffer.allocateDirect(inputSampleCount * 2).order(ByteOrder.nativeOrder())
        val testValue: Short = 12000
        repeat(inputSampleCount) {
            inputBuffer.putShort(testValue)
        }
        inputBuffer.flip()

        normalizer.queueInput(inputBuffer)
        val outputBuffer = normalizer.output
        outputBuffer.position(lookaheadDelayBytes)

        val sampleAfterSeek = outputBuffer.short
        val expectedSample = (testValue * 0.630957).toInt().toShort() // 10^(-4/20) = 0.630957

        assertTrue("Seek reset gain unexpectedly!", abs(sampleAfterSeek - expectedSample) <= 2)
    }
}
