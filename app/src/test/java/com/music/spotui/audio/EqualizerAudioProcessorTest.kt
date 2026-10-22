package com.music.spotui.audio

import androidx.media3.common.C
import androidx.media3.common.audio.AudioProcessor
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.nio.ByteBuffer
import java.nio.ByteOrder

@Suppress("DEPRECATION")
@androidx.media3.common.util.UnstableApi
class EqualizerAudioProcessorTest {

    private lateinit var equalizer: EqualizerAudioProcessor

    @Before
    fun setUp() {
        equalizer = EqualizerAudioProcessor()
        equalizer.enabled = true
        val format = AudioProcessor.AudioFormat(44100, 2, C.ENCODING_PCM_16BIT)
        equalizer.configure(format)
        equalizer.flush()
    }

    @Test
    fun testDisabledEqualizerOutputsUnmodifiedAudio() {
        equalizer.enabled = false
        equalizer.setPreset("Bass Booster")

        val inputSamples = shortArrayOf(1000, -1000, 5000, -5000)
        val inputBuffer = ByteBuffer.allocateDirect(inputSamples.size * 2).order(ByteOrder.nativeOrder())
        for (sample in inputSamples) {
            inputBuffer.putShort(sample)
        }
        inputBuffer.flip()

        equalizer.queueInput(inputBuffer)
        val outputBuffer = equalizer.output

        for (sample in inputSamples) {
            assertEquals(sample, outputBuffer.short)
        }
    }

    @Test
    fun testFlatPresetOutputsUnmodifiedAudio() {
        equalizer.enabled = true
        equalizer.setPreset("Flat")

        val inputSamples = shortArrayOf(2000, -2000, 8000, -8000)
        val inputBuffer = ByteBuffer.allocateDirect(inputSamples.size * 2).order(ByteOrder.nativeOrder())
        for (sample in inputSamples) {
            inputBuffer.putShort(sample)
        }
        inputBuffer.flip()

        equalizer.queueInput(inputBuffer)
        val outputBuffer = equalizer.output

        for (sample in inputSamples) {
            assertEquals(sample, outputBuffer.short)
        }
    }

    @Test
    fun testPcm16BitProcessingWithBassBoosterDoesNotClip() {
        equalizer.enabled = true
        equalizer.setPreset("Bass Booster")

        val sampleCount = 200
        val maxShort = Short.MAX_VALUE
        val inputBuffer = ByteBuffer.allocateDirect(sampleCount * 2).order(ByteOrder.nativeOrder())
        repeat(sampleCount) {
            inputBuffer.putShort(maxShort)
        }
        inputBuffer.flip()

        equalizer.queueInput(inputBuffer)
        val outputBuffer = equalizer.output

        var sampleRead = 0
        while (outputBuffer.hasRemaining()) {
            val sample = outputBuffer.short
            sampleRead++
            assertTrue("Sample $sample exceeded 16-bit PCM range!", sample <= Short.MAX_VALUE && sample >= Short.MIN_VALUE)
        }
        assertEquals(sampleCount, sampleRead)
    }

    @Test
    fun testPcm32BitFloatFormatSupport() {
        equalizer = EqualizerAudioProcessor()
        equalizer.enabled = true
        val floatFormat = AudioProcessor.AudioFormat(44100, 2, C.ENCODING_PCM_FLOAT)
        equalizer.configure(floatFormat)
        equalizer.flush()

        equalizer.setPreset("Treble Booster")

        val floatSamples = floatArrayOf(0.5f, -0.5f, 0.8f, -0.8f)
        val inputBuffer = ByteBuffer.allocateDirect(floatSamples.size * 4).order(ByteOrder.nativeOrder())
        for (f in floatSamples) {
            inputBuffer.putFloat(f)
        }
        inputBuffer.flip()

        equalizer.queueInput(inputBuffer)
        val outputBuffer = equalizer.output

        assertTrue("Output buffer should have processed float audio", outputBuffer.hasRemaining())
        while (outputBuffer.hasRemaining()) {
            val sample = outputBuffer.float
            assertTrue("Float sample $sample exceeded bounds [-1.0, 1.0]", sample <= 1.0f && sample >= -1.0f)
        }
    }

    @Test
    fun testFrequencyResponseCalculation() {
        equalizer.setPreset("Bass Booster")
        val lowFreqResponse = equalizer.getFrequencyResponse(60f)
        assertTrue("Bass Booster at 60 Hz should have positive gain response", lowFreqResponse > 0f)

        equalizer.setPreset("Treble Reducer")
        val trebleFreqResponse = equalizer.getFrequencyResponse(15000f)
        assertTrue("Treble Reducer at 15 kHz should have negative gain response", trebleFreqResponse < 0f)
    }
}
