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
            val audioTrack: AudioTrack?,
            // YouTube adaptive MP4 exposes byte ranges for ISO-BMFF/SIDX.
            // Optional for older clients and tests; required to offer genuine
            // seamless DASH quality switches rather than re-preparing a URL.
            val initRange: ByteRange? = null,
            val indexRange: ByteRange? = null,
        ) {
            @Serializable
            data class ByteRange(val start: String, val end: String)
            val isAudio: Boolean
                get() = width == null

            val isAutoDubbedTrack: Boolean
                get() {
                    if (audioTrack?.isAutoDubbed == true) return true
                    val id = audioTrack?.id?.lowercase().orEmpty()
                    val name = audioTrack?.displayName?.lowercase().orEmpty()
                    return id.contains("auto_dubbed") || id.contains("dubbed-auto") ||
                            name.contains("auto-dubbed") || name.contains("traducción automática") ||
                            name.contains("traducido automáticamente") || name.contains("auto-translated")
                }

            val isDubbedTrack: Boolean
                get() {
                    if (isAutoDubbedTrack) return true
                    val name = audioTrack?.displayName?.lowercase().orEmpty()
                    val id = audioTrack?.id?.lowercase().orEmpty()
                    return id.contains("dubbed") || name.contains("doblad") || name.contains("dubbed") || name.contains("traducción")
                }

            val isOriginal: Boolean
                get() {
                    if (audioTrack?.audioIsDefault == true) return true
                    if (isAutoDubbedTrack) return false
                    val name = audioTrack?.displayName?.lowercase().orEmpty()
                    val id = audioTrack?.id?.lowercase().orEmpty()
                    return name.contains("original") || id.contains("original") || !isDubbedTrack
                }

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
