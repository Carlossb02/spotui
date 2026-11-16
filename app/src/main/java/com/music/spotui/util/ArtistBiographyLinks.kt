package com.music.spotui.util

internal fun artistBiographyLink(raw: String): String {
    val value = raw.trim().removeSurrounding("\"").removeSurrounding("'")
    return when {
        value.startsWith("//") -> "https:" + value
        value.startsWith("/") -> "https://open.spotify.com" + value
        else -> value
    }
}
internal fun isSpotifyBiographyLink(url: String): Boolean = SpotifyDeepLink.parseUrlString(url) != null ||
    Regex("^https?://(?:spotify\\.link|spotify\\.app\\.link)/", RegexOption.IGNORE_CASE).containsMatchIn(url)
