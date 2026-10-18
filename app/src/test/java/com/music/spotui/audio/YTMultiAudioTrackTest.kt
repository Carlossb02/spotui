package com.music.spotui.audio

import android.net.ConnectivityManager
import com.metrolist.innertube.models.ResponseContext
import com.metrolist.innertube.models.response.PlayerResponse
import com.metrolist.music.constants.AudioQuality
import com.metrolist.music.utils.YTPlayerUtils
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Test
import java.lang.reflect.Constructor
import java.lang.reflect.Method

class YTMultiAudioTrackTest {

    private fun invokeNormalizeLanguageCode(input: String?): String {
        val method: Method = YTPlayerUtils::class.java.getDeclaredMethod("normalizeLanguageCode", String::class.java)
        method.isAccessible = true
        return method.invoke(YTPlayerUtils, input) as String
    }

    private fun invokeFindFormat(
        playerResponse: PlayerResponse,
        audioQuality: AudioQuality,
        connectivityManager: ConnectivityManager,
        preferredLanguage: String = "es"
    ): PlayerResponse.StreamingData.Format? {
        val method: Method = YTPlayerUtils::class.java.getDeclaredMethod(
            "findFormat",
            PlayerResponse::class.java,
            AudioQuality::class.java,
            ConnectivityManager::class.java,
            String::class.java
        )
        method.isAccessible = true
        return method.invoke(YTPlayerUtils, playerResponse, audioQuality, connectivityManager, preferredLanguage) as? PlayerResponse.StreamingData.Format
    }

    private fun createFakeConnectivityManager(): ConnectivityManager {
        val constructor: Constructor<ConnectivityManager> = ConnectivityManager::class.java.getDeclaredConstructor()
        constructor.isAccessible = true
        return constructor.newInstance()
    }

    @Test
    fun testLanguageCodeNormalization() {
        // Spanish variants
        assertEquals("es", invokeNormalizeLanguageCode("es"))
        assertEquals("es", invokeNormalizeLanguageCode("es-US"))
        assertEquals("es", invokeNormalizeLanguageCode("es_US"))
        assertEquals("es", invokeNormalizeLanguageCode("es-419"))
        assertEquals("es", invokeNormalizeLanguageCode("a.es-US"))
        assertEquals("es", invokeNormalizeLanguageCode("spa"))
        assertEquals("es", invokeNormalizeLanguageCode("spanish"))
        assertEquals("es", invokeNormalizeLanguageCode("español"))
        assertEquals("es", invokeNormalizeLanguageCode("Español (EE. UU.)"))
        assertEquals("es", invokeNormalizeLanguageCode("Spanish (US)"))

        // English variants
        assertEquals("en", invokeNormalizeLanguageCode("en"))
        assertEquals("en", invokeNormalizeLanguageCode("en-US"))
        assertEquals("en", invokeNormalizeLanguageCode("a.en"))
        assertEquals("en", invokeNormalizeLanguageCode("English"))

        // French & German
        assertEquals("fr", invokeNormalizeLanguageCode("fr-FR"))
        assertEquals("de", invokeNormalizeLanguageCode("de-DE"))
    }

    @Test
    fun testMultiAudioPodcastSpanishSelection() {
        // Simulates a podcast like "La noche de Cuesta" with multi-language audio
        val spanishTrack = PlayerResponse.StreamingData.Format.AudioTrack(
            displayName = "Spanish (US) - Original",
            id = "es-US",
            isAutoDubbed = false,
            audioIsDefault = true
        )
        val englishTrack = PlayerResponse.StreamingData.Format.AudioTrack(
            displayName = "English (auto-dubbed)",
            id = "en-US",
            isAutoDubbed = true,
            audioIsDefault = false
        )
        val arabicTrack = PlayerResponse.StreamingData.Format.AudioTrack(
            displayName = "Arabic (auto-dubbed)",
            id = "ar-EG",
            isAutoDubbed = true,
            audioIsDefault = false
        )

        val spanishFormat = PlayerResponse.StreamingData.Format(
            itag = 251, url = "https://googlevideo.com/videoplayback?itag=251&xtags=lang%3Des-US",
            mimeType = "audio/webm; codecs=\"opus\"", bitrate = 160000,
            width = null, height = null, contentLength = 100000L, quality = "medium",
            fps = null, qualityLabel = null, averageBitrate = 160000, audioQuality = "AUDIO_QUALITY_MEDIUM",
            approxDurationMs = "10000", audioSampleRate = 48000, audioChannels = 2, loudnessDb = 0.0,
            lastModified = 0L, signatureCipher = null, cipher = null, audioTrack = spanishTrack
        )
        val englishFormat = PlayerResponse.StreamingData.Format(
            itag = 251, url = "https://googlevideo.com/videoplayback?itag=251&xtags=lang%3Den-US",
            mimeType = "audio/webm; codecs=\"opus\"", bitrate = 160000,
            width = null, height = null, contentLength = 100000L, quality = "medium",
            fps = null, qualityLabel = null, averageBitrate = 160000, audioQuality = "AUDIO_QUALITY_MEDIUM",
            approxDurationMs = "10000", audioSampleRate = 48000, audioChannels = 2, loudnessDb = 0.0,
            lastModified = 0L, signatureCipher = null, cipher = null, audioTrack = englishTrack
        )
        val arabicFormat = PlayerResponse.StreamingData.Format(
            itag = 251, url = "https://googlevideo.com/videoplayback?itag=251&xtags=lang%3Dar-EG",
            mimeType = "audio/webm; codecs=\"opus\"", bitrate = 160000,
            width = null, height = null, contentLength = 100000L, quality = "medium",
            fps = null, qualityLabel = null, averageBitrate = 160000, audioQuality = "AUDIO_QUALITY_MEDIUM",
            approxDurationMs = "10000", audioSampleRate = 48000, audioChannels = 2, loudnessDb = 0.0,
            lastModified = 0L, signatureCipher = null, cipher = null, audioTrack = arabicTrack
        )

        val playerResponse = PlayerResponse(
            responseContext = ResponseContext(null, null),
            playabilityStatus = PlayerResponse.PlayabilityStatus("OK", null),
            videoDetails = null,
            playerConfig = null,
            playbackTracking = null,
            streamingData = PlayerResponse.StreamingData(
                formats = null,
                adaptiveFormats = listOf(arabicFormat, englishFormat, spanishFormat), // Arabic is listed first!
                expiresInSeconds = 21600
            )
        )

        val connManager = createFakeConnectivityManager()
        val selected = invokeFindFormat(playerResponse, AudioQuality.HIGH, connManager, preferredLanguage = "es")

        assertNotNull("Format should be selected", selected)
        assertEquals("Should select Spanish format even if listed after Arabic/English", "es-US", selected?.audioTrack?.id)
    }

    @Test
    fun testSingleAudioMusicTrackSelectionUnchanged() {
        // Standard music track (single audio track, multiple bitrates)
        val lowQualityFormat = PlayerResponse.StreamingData.Format(
            itag = 140, url = "https://googlevideo.com/music?itag=140",
            mimeType = "audio/mp4; codecs=\"mp4a.40.2\"", bitrate = 128000,
            width = null, height = null, contentLength = 50000L, quality = "low",
            fps = null, qualityLabel = null, averageBitrate = 128000, audioQuality = "AUDIO_QUALITY_LOW",
            approxDurationMs = "10000", audioSampleRate = 44100, audioChannels = 2, loudnessDb = 0.0,
            lastModified = 0L, signatureCipher = null, cipher = null, audioTrack = null
        )
        val highQualityFormat = PlayerResponse.StreamingData.Format(
            itag = 251, url = "https://googlevideo.com/music?itag=251",
            mimeType = "audio/webm; codecs=\"opus\"", bitrate = 160000,
            width = null, height = null, contentLength = 80000L, quality = "medium",
            fps = null, qualityLabel = null, averageBitrate = 160000, audioQuality = "AUDIO_QUALITY_MEDIUM",
            approxDurationMs = "10000", audioSampleRate = 48000, audioChannels = 2, loudnessDb = 0.0,
            lastModified = 0L, signatureCipher = null, cipher = null, audioTrack = null
        )

        val playerResponse = PlayerResponse(
            responseContext = ResponseContext(null, null),
            playabilityStatus = PlayerResponse.PlayabilityStatus("OK", null),
            videoDetails = null,
            playerConfig = null,
            playbackTracking = null,
            streamingData = PlayerResponse.StreamingData(
                formats = null,
                adaptiveFormats = listOf(lowQualityFormat, highQualityFormat),
                expiresInSeconds = 21600
            )
        )

        val connManager = createFakeConnectivityManager()
        val selected = invokeFindFormat(playerResponse, AudioQuality.HIGH, connManager, preferredLanguage = "es")

        assertNotNull("Format should be selected", selected)
        assertEquals("Should pick highest quality bitrate for music", 251, selected?.itag)
    }
}
