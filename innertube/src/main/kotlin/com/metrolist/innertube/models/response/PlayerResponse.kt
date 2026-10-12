package com.metrolist.innertube.models.response

import com.metrolist.innertube.models.ResponseContext
import com.metrolist.innertube.models.Thumbnails
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/**
 * PlayerResponse with [com.metrolist.innertube.models.YouTubeClient.WEB_REMIX] client
 */
@Serializable
data class PlayerResponse(
    val responseContext: ResponseContext,
    val playabilityStatus: PlayabilityStatus,
    val playerConfig: PlayerConfig?,
    val streamingData: StreamingData?,
    val videoDetails: VideoDetails?,
    @SerialName("playbackTracking")
    val playbackTracking: PlaybackTracking?,
) {
    @Serializable
    data class PlayabilityStatus(
        val status: String,
        val reason: String?,
    )

    @Serializable
    data class PlayerConfig(
        val audioConfig: AudioConfig,
    ) {
        @Serializable
        data class AudioConfig(
            val loudnessDb: Double?,
            val perceptualLoudnessDb: Double?,
        )
    }

    @Serializable
    data class StreamingData(
        val formats: List<Format>?,
        val adaptiveFormats: List<Format>,
        val expiresInSeconds: Int,
    ) {
        @Serializable
        data class Format(
            val itag: Int,
            val url: String?,
            val mimeType: String,
            val bitrate: Int,
            val width: Int?,
            val height: Int?,
            val contentLength: Long?,
            val quality: String,
            val fps: Int?,
            val qualityLabel: String?,
            val averageBitrate: Int?,
            val audioQuality: String?,
            val approxDurationMs: String?,
            val audioSampleRate: Int?,
            val audioChannels: Int?,
            val loudnessDb: Double?,
            val lastModified: Long?,
            val signatureCipher: String?,
            val cipher: String?,
            val audioTrack: AudioTrack?
        ) {
            val isAudio: Boolean
                get() = width == null

            val isAutoDubbedTrack: Boolean
                get() {
                    if (audioTrack?.isAutoDubbed == true) return true
                    val id = audioTrack?.id?.lowercase().orEmpty()
                    val name = audioTrack?.displayName?.lowercase().orEmpty()
                    if (id.contains("auto_dubbed") || id.contains("dubbed") || id.contains("translated") ||
                        name.contains("auto-dubbed") || name.contains("doblado") || name.contains("traducido") ||
                        name.contains("dubbed") || name.contains("translated") || name.contains("auto-translated")
                    ) {
                        return true
                    }
                    if (id in listOf("ar", "ar-sa", "ar-eg", "fr", "de", "hi", "zh", "ja", "ko", "it", "pt", "ru")) {
                        return true
                    }
                    if (audioTrack?.audioIsDefault == false) return true
                    return false
                }

            val isOriginal: Boolean
                get() = !isAutoDubbedTrack

            @Serializable
            data class AudioTrack(
                val displayName: String? = null,
                val id: String? = null,
                val isAutoDubbed: Boolean? = null,
                val audioIsDefault: Boolean? = null,
            )
        }
    }

    @Serializable
    data class VideoDetails(
        val videoId: String,
        val title: String?,
        val author: String?,
        val channelId: String,
        val lengthSeconds: String,
        val musicVideoType: String?,
        val viewCount: String?,
        val thumbnail: Thumbnails,
    )

    @Serializable
    data class PlaybackTracking(
        @SerialName("videostatsPlaybackUrl")
        val videostatsPlaybackUrl: VideostatsPlaybackUrl?,
        @SerialName("videostatsWatchtimeUrl")
        val videostatsWatchtimeUrl: VideostatsWatchtimeUrl?,
        @SerialName("atrUrl")
        val atrUrl: AtrUrl?,
    ) {
        @Serializable
        data class VideostatsPlaybackUrl(
            @SerialName("baseUrl")
            val baseUrl: String?,
        )
        @Serializable
        data class VideostatsWatchtimeUrl(
            @SerialName("baseUrl")
            val baseUrl: String?,
        )
        @Serializable
        data class AtrUrl(
            @SerialName("baseUrl")
            val baseUrl: String?,
        )
    }
}
