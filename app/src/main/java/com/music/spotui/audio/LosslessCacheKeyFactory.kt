package com.music.spotui.audio

import android.net.Uri
import androidx.media3.datasource.DataSpec
import androidx.media3.datasource.cache.CacheKeyFactory

object LosslessCacheKeyFactory : CacheKeyFactory {
    private const val SPOTIFY_PREFIX = "spotify:track:"

    fun buildCacheKey(spotifyTrackId: String?, url: String): String {
        val cleanId = spotifyTrackId?.removePrefix(SPOTIFY_PREFIX)?.substringBefore('|')?.trim()
        val uri = Uri.parse(url)
        val host = uri.host.orEmpty().lowercase()
        val path = uri.path.orEmpty()
        // YouTube audio: docid + itag identifies the immutable encoded resource.
        // Expiry, signatures, CDN host and PoToken change between resolutions.
        if (host.contains("googlevideo.com") || host.contains("youtube.com")) {
            val videoId = uri.getQueryParameter("docid") ?: uri.getQueryParameter("id")
                ?: uri.getQueryParameter("v")
            val itag = uri.getQueryParameter("itag") ?: "unknown"
            // Multilingual audio tracks can share itag! Distinguish original
            // Spanish tracks from auto-dubs by stable language/content hints.
            val variant = listOf(
                uri.getQueryParameter("xtags").orEmpty(),
                uri.getQueryParameter("lang").orEmpty(),
                uri.getQueryParameter("acont").orEmpty(),
            ).joinToString("|")
            val variantHash = java.security.MessageDigest.getInstance("SHA-256")
                .digest(variant.toByteArray(Charsets.UTF_8)).take(5)
                .joinToString("") { "%02x".format(it) }
            if (!videoId.isNullOrBlank()) return "spotui-yt-v3:$videoId:$itag:$variantHash"
        }
        // Lossless and lossy providers must not share cached bytes with one another.
        // Keep an exact URL fingerprint for providers without immutable asset ids.
        // A new signed URL can miss the cache but can never return the wrong codec.
        val fingerprint = java.security.MessageDigest.getInstance("SHA-256")
            .digest(url.toByteArray(Charsets.UTF_8))
            .take(12).joinToString("") { "%02x".format(it) }
        return if (!cleanId.isNullOrBlank()) "spotui-track-v2:$cleanId:$fingerprint"
            else "spotui-media-v2:$fingerprint"
    }

    override fun buildCacheKey(dataSpec: DataSpec): String {
        dataSpec.key?.takeIf { it.isNotBlank() }?.let { return it }
        return buildCacheKey(null, dataSpec.uri.toString())
    }
}
