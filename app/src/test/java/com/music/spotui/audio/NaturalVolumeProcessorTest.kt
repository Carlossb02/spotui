package com.music.spotui.audio

import androidx.media3.common.C
import androidx.media3.common.audio.AudioProcessor
import com.music.spotui.data.preferences.NormalizationMode
import org.junit.Assert.*
import org.junit.Test
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.pow
import kotlin.math.roundToInt

@androidx.media3.common.util.UnstableApi
class NaturalVolumeProcessorTest {
    private fun processor() = VolumeNormalizationAudioProcessor().apply {
        mode = NormalizationMode.YOUTUBE_METADATA
        configure(AudioProcessor.AudioFormat(48000, 1, C.ENCODING_PCM_16BIT))
        flush()
    }
    private fun samples(p: VolumeNormalizationAudioProcessor, values: List<Short>): List<Short> {
        val input = ByteBuffer.allocateDirect(values.size * 2).order(ByteOrder.nativeOrder())
        values.forEach { input.putShort(it) }; input.flip(); p.queueInput(input)
        val output = p.output.order(ByteOrder.nativeOrder())
        return List(output.remaining() / 2) { output.short }
    }
    @Test fun attenuationIsOnlyAMultiplierEvenNearFullScale() {
        val p = processor(); p.setYouTubeGainDb(-0.2); p.beginTrack()
        val input = listOf<Short>(-32768, -31000, -10000, 0, 10000, 31000, 32767)
        val expected = input.map { (it * 10.0.pow(-0.2 / 20)).roundToInt().toShort() }
        assertEquals(expected, samples(p, input))
    }
    @Test fun missingPeakNeverCausesUncontrolledBoost() {
        val p = processor(); p.setYouTubeGainDb(6.0); p.beginTrack()
        val input = listOf<Short>(-32768, -30000, 0, 30000, 32767)
        assertEquals(input, samples(p, input))
    }
    @Test fun knownPeakAllowsOnlySafePositiveGain() {
        val p = processor(); p.setYouTubeGainDb(6.0, -5.0); p.beginTrack()
        samples(p, listOf(1000))
        assertEquals(4.0, p.appliedGainDb, 0.0001)
    }
    @Test fun seekDoesNotChangeTheLockedGain() {
        val p = processor(); p.setYouTubeGainDb(-6.0); p.beginTrack()
        val first = samples(p, listOf(10000))
        p.setYouTubeGainDb(-12.0); p.flush()
        assertEquals(first, samples(p, listOf(10000)))
    }
}
