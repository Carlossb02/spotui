package com.music.spotui.audio

import androidx.media3.common.C
import androidx.media3.common.audio.AudioProcessor
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
        val format = AudioProcessor.AudioFormat(44100, 2, C.ENCODING_PCM_16BIT)
        normalizer.configure(format)
        normalizer.flush()
        normalizer.enabled = true
    }

    @Test
    fun testLoudnessMetadataConstantGain() {
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

        // Verify that gain is constant and applied immediately
        val firstSample = outputBuffer.short
        val expectedSample = (testValue * 0.6683439).toInt().toShort()

        assertTrue("Expected sample near $expectedSample but got $firstSample", abs(firstSample - expectedSample) <= 2)

        // Ensure every subsequent sample maintains the exact same gain
        while (outputBuffer.hasRemaining()) {
            val s = outputBuffer.short
            assertEquals("Gain fluctuated during track!", firstSample, s)
        }
    }

    @Test
    fun testSoftKneeLimiterPreventsClippingWithoutHardCut() {
        // Boost quiet track (+6 dB gain = x2.0)
        normalizer.setTrackLoudness(-6.0)

        val inputBuffer = ByteBuffer.allocateDirect(20 * 2).order(ByteOrder.nativeOrder())
        // Large input sample that would exceed full scale if multiplied by 2.0
        val highSample: Short = 25000 // 25000 * 2 = 50000 > 32767
        repeat(10) {
            inputBuffer.putShort(highSample)
        }
        inputBuffer.flip()

        normalizer.queueInput(inputBuffer)
        val outputBuffer = normalizer.output

        while (outputBuffer.hasRemaining()) {
            val outputSample = outputBuffer.short
            assertTrue("Soft knee limiter did not limit high amplitude sample!", outputSample in 27000..32767)
        }
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
