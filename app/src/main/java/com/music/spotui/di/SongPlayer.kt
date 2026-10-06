package com.music.spotui.di

import android.content.Context
import android.net.ConnectivityManager
import android.net.wifi.WifiManager
import android.os.PowerManager
import android.util.Log
import androidx.media3.common.MediaItem
import androidx.media3.exoplayer.ExoPlayer
import com.metrolist.innertube.YouTube
import com.metrolist.innertube.models.SongItem
import com.metrolist.innertube.utils.YouTubeUrlParser
import com.metrolist.music.constants.AudioQuality
import com.metrolist.music.utils.YTPlayerUtils
import com.music.spotui.data.api.LyricsApi
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.isActive
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.coroutines.delay
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import java.text.Normalizer
import kotlin.math.abs
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.exp
import kotlin.math.ln
import kotlin.math.sin

/**
 * Plays audio resolved from YouTube. The `song` argument is a "title artist"
 * search query (set by the Spotify-backed data layer): it's matched to a
 * YouTube video, whose stream URL is resolved via the ported [YTPlayerUtils]
 * flow (cipher / PoToken / sabr) and handed to ExoPlayer.
 */
object SongPlayer {
    private const val TAG = "SongPlayer"
    private const val SPOTIFY_TRACK_PREFIX = "spotify:track:"
    private var player: ExoPlayer? = null
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    private var wakeLock: PowerManager.WakeLock? = null
    private var wifiLock: WifiManager.WifiLock? = null

    @Synchronized
    fun acquireWakeLock(context: Context, tag: String = "spotui:playback", timeoutMs: Long = 60_000L) {
        runCatching {
            val appContext = context.applicationContext
            if (wakeLock == null) {
                val pm = appContext.getSystemService(Context.POWER_SERVICE) as? PowerManager
                wakeLock = pm?.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "Spotui:AudioWakeLock")?.apply {
                    setReferenceCounted(false)
                }
            }
            wakeLock?.acquire(timeoutMs)

            if (wifiLock == null) {
                val wm = appContext.getSystemService(Context.WIFI_SERVICE) as? WifiManager
                @Suppress("DEPRECATION")
                wifiLock = wm?.createWifiLock(WifiManager.WIFI_MODE_FULL_HIGH_PERF, "Spotui:WifiLock")?.apply {
                    setReferenceCounted(false)
                }
            }
            wifiLock?.acquire()
        }.onFailure { Log.w(TAG, "Failed to acquire wake/wifi lock for $tag", it) }
    }

    @Synchronized
    fun releaseWakeLock(tag: String? = null) {
        runCatching {
            if (wakeLock?.isHeld == true) wakeLock?.release()
            if (wifiLock?.isHeld == true) wifiLock?.release()
        }.onFailure { Log.w(TAG, "Failed to release wake/wifi lock for $tag", it) }
    }

    // Cache of resolved stream URLs keyed by the "title artist" query, so replays
    // and prefetched neighbours start instantly instead of re-hitting the network.
    private val streamCache = java.util.concurrent.ConcurrentHashMap<String, String>()
    // Which engine each cached stream came from ("YouTube", "Lossless • …") so a
    // cache hit can restore the correct source badge.
    private val sourceCache = java.util.concurrent.ConcurrentHashMap<String, String>()
    // Cache of resolved YouTube video candidates keyed by the play query
    private val videoCandidatesCache = java.util.concurrent.ConcurrentHashMap<String, List<String>>()
    // When a cached stream URL was last confirmed valid. Fresh URLs skip the network re-validation.
    private val streamValidatedAt = java.util.concurrent.ConcurrentHashMap<String, Long>()
    private const val STREAM_REVALIDATE_MS = 5 * 60_000L

    @kotlin.OptIn(androidx.media3.common.util.UnstableApi::class)
    @androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)
    fun clearCaches(context: Context) {
        val appContext = context.applicationContext
        streamCache.clear()
        sourceCache.clear()
        qualityCache.clear()
        qualityTierCache.clear()
        videoCandidatesCache.clear()
        streamValidatedAt.clear()
        inFlightResolutions.values.forEach { runCatching { it.cancel() } }
        inFlightResolutions.clear()
        alternativeKeyRegistry.clear()
        providerBlocklist.clear()
        durationVerified.clear()
        com.metrolist.music.utils.YTPlayerUtils.resetSession(appContext)
        com.music.spotui.data.preferences.clearAllCachedStreams(appContext)
        com.music.spotui.data.preferences.clearAllAlternativeStreams(appContext)
        com.music.spotui.data.preferences.clearAllResolvedVideos(appContext)
        runCatching {
            mediaCache?.keys?.forEach { key ->
                runCatching { mediaCache?.removeResource(key) }
            }
        }

        val currentPlayingSong = boundState?.queue?.value?.firstOrNull {
            it.id == boundState?.songId?.value || (it.url == boundState?.songUrl?.value && it.url.isNotBlank())
        }
        val playingUrl = boundState?.songUrl?.value
        if (!playingUrl.isNullOrBlank()) {
            exoPlayer?.stop()
            exoPlayer?.clearMediaItems()
            if (currentPlayingSong != null) {
                playSong(currentPlayingSong.url, appContext, "song/${currentPlayingSong.id}")
            } else {
                val currentId = boundState?.songId?.value
                playSong(playingUrl, appContext, if (currentId != null && currentId != 0) "song/$currentId" else null)
            }
        }
    }

    fun onQualitySettingChanged(context: Context) {
        val appContext = context.applicationContext
        clearCaches(appContext)
        val currentSong = loadedQuery ?: currentRequest
        if (currentSong.isNotBlank()) {
            scope.launch {
                val p = player ?: return@launch
                val positionMs = withContext(Dispatchers.Main) { runCatching { p.currentPosition }.getOrDefault(0L) }
                val wasPlaying = withContext(Dispatchers.Main) { runCatching { p.isPlaying || p.playWhenReady }.getOrDefault(false) }
                val newStreamUrl = resolveStreamUrl(currentSong, appContext, forPlayback = true) ?: return@launch
                if (loadedQuery == currentSong || currentRequest == currentSong) {
                    withContext(Dispatchers.Main) {
                        val activePlayer = player ?: return@withContext
                        activePlayer.setMediaItem(
                            buildMediaItem(newStreamUrl, streamMimeType(newStreamUrl)),
                            positionMs,
                        )
                        activePlayer.prepare()
                        activePlayer.playWhenReady = wasPlaying
                    }
                }
            }
        }
    }

    @Volatile var losslessStreaming = false
    @Volatile var losslessHiRes = true

    val resolutionLogs = java.util.concurrent.ConcurrentLinkedQueue<String>()

    fun logResolution(msg: String) {
        val time = java.text.SimpleDateFormat("HH:mm:ss", java.util.Locale.getDefault()).format(java.util.Date())
        resolutionLogs.add("[$time] $msg")
        while (resolutionLogs.size > 50) resolutionLogs.poll()
    }

    private val trackPrefixRegex = Regex("^(spotify:track:[a-zA-Z0-9]+|yt:[a-zA-Z0-9_-]+)")
    private val youtubeIdRegex = Regex("""[A-Za-z0-9_-]{11}""")
    private val artistSplitRegex = Regex("""[,&]|\band\b""", RegexOption.IGNORE_CASE)
    private val remixSuffixRegex = Regex("(remix|rmx)$", RegexOption.IGNORE_CASE)
    private val radioEditRegex = Regex("\\b(radio (version|edit|mix)|single (version|edit))\\b", RegexOption.IGNORE_CASE)
    private val lyricsUploadRegex = Regex("\\b(lyrics?|lyric video)\\b", RegexOption.IGNORE_CASE)

    private fun cleanTrackTitle(raw: String, artist: String): String {
        var title = raw
        if (title.contains("|")) {
            title = title.substringAfter("|")
        }
        title = title.replace(trackPrefixRegex, "").trim()
        if (artist.isNotBlank()) {
            val artistPattern = Regex("""\s*[-–—]?\s*${Regex.escape(artist)}\s*$""", RegexOption.IGNORE_CASE)
            title = title.replace(artistPattern, "").trim()
            artist.split(',', '&', '/', ';').map { it.trim() }.filter { it.isNotBlank() }.forEach { a ->
                val individualPattern = Regex("""\s*[-–—]?\s*${Regex.escape(a)}\s*$""", RegexOption.IGNORE_CASE)
                title = title.replace(individualPattern, "").trim()
            }
        }
        return title.ifBlank { raw }
    }

    @Volatile var webPlayerEnabled = false
    @Volatile var youtubeEnabled = true
    @Volatile var deezerEnabled = true

    @Volatile var currentSource: String = "YouTube"
        private set
    @Volatile var currentQuality: String = ""
        private set

    @Volatile private var lastYtFailureReason: String? = null

    private fun updateResolveStatus(isResolving: Boolean, status: String = "") {
        boundState?.updateResolveState(isResolving, status)
    }
    private val qualityCache = java.util.concurrent.ConcurrentHashMap<String, String>()
    private val qualityTierCache = java.util.concurrent.ConcurrentHashMap<String, String>()
    private val trackIdRegistry = java.util.concurrent.ConcurrentHashMap<String, String>()
    private val isrcRegistry = java.util.concurrent.ConcurrentHashMap<String, String>()
    private val alternativeKeyRegistry = java.util.concurrent.ConcurrentHashMap<String, String>()

    fun registerLossless(pairs: List<Pair<String, String>>) {
        pairs.forEach { (query, spotifyId) ->
            if (query.isNotBlank() && spotifyId.isNotBlank()) trackIdRegistry[query] = spotifyId
        }
    }

    fun registerIsrc(spotifyTrackId: String, isrc: String) {
        if (spotifyTrackId.isNotBlank() && isrc.isNotBlank()) {
            isrcRegistry[spotifyTrackId] = isrc
        }
    }

    fun registerAlternativeKeys(pairs: List<Pair<String, String>>) {
        pairs.forEach { (query, key) ->
            if (query.isNotBlank() && key.isNotBlank()) alternativeKeyRegistry[query] = key
        }
    }

    private val explicitRegistry = java.util.concurrent.ConcurrentHashMap<String, Boolean>()

    fun registerExplicit(pairs: List<Pair<String, Boolean>>) {
        pairs.forEach { (query, explicit) ->
            if (query.isNotBlank()) explicitRegistry[query] = explicit
        }
    }

    private val durationRegistry = java.util.concurrent.ConcurrentHashMap<String, Int>()

    fun registerDuration(pairs: List<Pair<String, Int>>) {
        pairs.forEach { (query, ms) ->
            if (query.isNotBlank() && ms > 0) {
                durationRegistry[query] = ms
                approxDurationQueries.remove(query)
            }
        }
    }

    data class TrackMatchMetadata(
        val title: String,
        val artist: String,
        val album: String,
    )

    private val metadataRegistry =
        java.util.concurrent.ConcurrentHashMap<String, TrackMatchMetadata>()

    fun registerMetadata(pairs: List<Pair<String, TrackMatchMetadata>>) {
        pairs.forEach { (query, meta) ->
            if (query.isNotBlank() && meta.title.isNotBlank()) metadataRegistry[query] = meta
        }
    }

    @Volatile private var currentRequest: String = ""
    @Volatile private var loadedQuery: String? = null
    @Volatile private var playWhenResolved = true

    @Volatile private var metaTitle: String = ""
    @Volatile private var metaArtist: String = ""
    @Volatile private var metaCover: String = ""

    fun setNowPlayingMeta(title: String, artist: String, coverUri: String) {
        metaTitle = title
        metaArtist = artist
        metaCover = coverUri
        if (currentMediaId == null && boundState != null) {
            val id = boundState?.songId?.value ?: 0
            if (id != 0) currentMediaId = "song/$id"
        }
        refreshArtworkInBackground(currentMediaId, coverUri)
        if (player != null && (title.isNotBlank() || artist.isNotBlank())) {
            scope.launch(Dispatchers.Main) {
                val p = player ?: return@launch
                val item = p.currentMediaItem ?: return@launch
                if (item.mediaMetadata.title != title || item.mediaMetadata.artist != artist) {
                    val updatedMeta = item.mediaMetadata.buildUpon()
                        .setTitle(title)
                        .setArtist(artist)
                        .build()
                    val updatedItem = item.buildUpon().setMediaMetadata(updatedMeta).build()
                    p.replaceMediaItem(p.currentMediaItemIndex.coerceAtLeast(0), updatedItem)
                }
            }
        }
    }

    private fun refreshArtworkInBackground(songIdStr: String?, coverUrl: String) {
        if (coverUrl.isBlank() || coverUrl.startsWith("file:") || coverUrl.startsWith("content:")) return
        val ctx = appCtx ?: com.music.spotui.MyApplication.instance
        val cleanId = songIdStr?.removePrefix("song/")?.removePrefix("playlist/")?.removePrefix("album/")?.trim()
        scope.launch(Dispatchers.IO) {
            val file = runCatching {
                com.bumptech.glide.Glide.with(ctx)
                    .downloadOnly()
                    .load(coverUrl)
                    .submit()
                    .get()
            }.getOrNull()
            if (file != null && file.exists()) {
                val dir = java.io.File(ctx.filesDir, "downloads")
                if (!cleanId.isNullOrBlank() && com.music.spotui.data.preferences.isDownloaded(ctx, cleanId)) {
                    val dest = java.io.File(dir, "${cleanId}_cover.jpg")
                    if (!dest.exists()) runCatching { file.copyTo(dest, overwrite = true) }
                }
                withContext(Dispatchers.Main) {
                    val p = player ?: return@withContext
                    val item = p.currentMediaItem ?: return@withContext
                    val localUri = android.net.Uri.fromFile(file)
                    if (item.mediaMetadata.artworkUri == localUri) return@withContext
                    val updatedMeta = item.mediaMetadata.buildUpon().setArtworkUri(localUri).build()
                    val updatedItem = item.buildUpon().setMediaMetadata(updatedMeta).build()
                    p.replaceMediaItem(p.currentMediaItemIndex.coerceAtLeast(0), updatedItem)
                }
            }
        }
    }

    fun buildSpotifyPlayQuery(spotifyTrackId: String, title: String, artist: String): String {
        val cleanArtist = artist.replace(",", " ").replace("  ", " ").trim()
        val searchText = listOf(cleanSpotifySearchTitle(title), cleanArtist)
            .filter { it.isNotBlank() }
            .joinToString(" ")
        return if (spotifyTrackId.isBlank()) searchText else "$SPOTIFY_TRACK_PREFIX$spotifyTrackId|$searchText"
    }

    private val featSearchPattern = Regex("""\s*[\(\[]\s*(feat|ft)\..*?[\)\]]""", RegexOption.IGNORE_CASE)
    private val remasterTitlePatterns = listOf(
        Regex("""\s*[-–—]\s*(\d{4}\s*)?remaster(ed)?.*$""", RegexOption.IGNORE_CASE),
        Regex("""\s*[\(\[]\s*(\d{4}\s*)?remaster(ed)?.*?[\)\]]""", RegexOption.IGNORE_CASE),
        Regex("""\s*[\(\[]\s*(deluxe|anniversary|special|expanded)\s*(edition|version)?.*?[\)\]]""", RegexOption.IGNORE_CASE),
    )

    private fun cleanSpotifySearchTitle(title: String): String {
        var cleaned = title.replace(featSearchPattern, "")
        for (pattern in remasterTitlePatterns) {
            cleaned = cleaned.replace(pattern, "")
        }
        return cleaned.trim()
    }

    private fun searchTextForPlayback(song: String): String {
        val meta = metadataRegistry[song]
        if (meta != null && meta.artist.isNotBlank() && meta.title.isNotBlank()) {
            val cleanArtist = meta.artist.replace(",", " ").replace("  ", " ").trim()
            return "$cleanArtist ${cleanSpotifySearchTitle(meta.title)}"
        }
        val matchSong = boundState?.queue?.value?.firstOrNull { it.url == song }
        if (matchSong != null && matchSong.singer.isNotBlank() && matchSong.title.isNotBlank()) {
            val cleanArtist = matchSong.singer.replace(",", " ").replace("  ", " ").trim()
            return "$cleanArtist ${cleanSpotifySearchTitle(matchSong.title)}"
        }
        return if (song.startsWith(SPOTIFY_TRACK_PREFIX) && song.contains('|')) {
            song.substringAfter('|').ifBlank { song }
        } else {
            song
        }
    }

    private fun spotifyTrackIdForPlayback(song: String): String? =
        if (song.startsWith(SPOTIFY_TRACK_PREFIX)) {
            song.removePrefix(SPOTIFY_TRACK_PREFIX).substringBefore('|').takeIf { it.isNotBlank() }
        } else {
            null
        }

    fun videoIdFromYouTubeLink(text: String): String? =
        YouTubeUrlParser.extractVideoId(text)
            ?: text.trim().takeIf { it.matches(youtubeIdRegex) }

    fun clearMediaCacheForTrack(context: Context, song: String) {
        runCatching {
            val cache = mediaCache(context)
            val spotifyId = trackIdRegistry[song] ?: spotifyTrackIdForPlayback(song)
            val key = com.music.spotui.audio.LosslessCacheKeyFactory.buildCacheKey(spotifyId, song)
            cache.removeResource(key)
        }
    }

    fun invalidateResolvedStream(song: String) {
        streamCache.remove(song)
        sourceCache.remove(song)
        qualityCache.remove(song)
        qualityTierCache.remove(song)
        streamValidatedAt.remove(song)
        videoCandidatesCache.remove("$song|FILTER_SONG|MATCH_V12")
        videoCandidatesCache.remove("$song|FILTER_VIDEO|MATCH_V12")
        appCtx?.let { ctx ->
            com.music.spotui.data.preferences.clearCachedVideoId(ctx, song)
            com.music.spotui.data.preferences.clearCachedStream(ctx, song)
            clearMediaCacheForTrack(ctx, song)
        }
    }

    fun invalidateSongCache(
        song: com.music.spotui.data.entity.SongsModel,
        context: Context,
        reloadIfPlaying: Boolean = true,
        clearAltStream: Boolean = true,
    ) {
        invalidateSongCacheByUrl(
            songUrl = song.url,
            context = context,
            reloadIfPlaying = reloadIfPlaying,
            clearAltStream = clearAltStream,
            songTitle = song.title,
            songId = song.id
        )
    }

    fun invalidateSongCacheByUrl(
        songUrl: String,
        context: Context,
        reloadIfPlaying: Boolean = true,
        clearAltStream: Boolean = true,
        songTitle: String? = null,
        songId: Int? = null,
    ) {
        if (songUrl.isBlank()) return
        val appContext = context.applicationContext

        invalidateResolvedStream(songUrl)
        inFlightResolutions.remove(songUrl)?.cancel()

        val matchSong = boundState?.queue?.value?.firstOrNull { it.url == songUrl }

        if (clearAltStream) {
            val altKey = alternativeKeyRegistry.remove(songUrl)
            if (altKey != null) {
                com.music.spotui.data.preferences.clearAlternativeStream(appContext, altKey)
            }
            if (matchSong != null) {
                val key = com.music.spotui.data.preferences.alternativeStreamKey(matchSong)
                com.music.spotui.data.preferences.clearAlternativeStream(appContext, key)
            }
            if (songId != null && songId != 0) {
                com.music.spotui.data.preferences.clearAlternativeStream(appContext, "song/$songId")
            }
        }

        val currentPlayingId = boundState?.songId?.value
        val isCurrentlyPlaying = (songId != null && songId != 0 && currentPlayingId == songId) ||
                (boundState?.songUrl?.value == songUrl && songUrl.isNotBlank())

        val title = songTitle ?: matchSong?.title ?: "track"

        if (isCurrentlyPlaying && reloadIfPlaying) {
            exoPlayer?.stop()
            exoPlayer?.clearMediaItems()
            android.widget.Toast.makeText(
                appContext,
                "Cache cleared for '$title' — Reloading...",
                android.widget.Toast.LENGTH_SHORT
            ).show()
            playSong(songUrl, appContext, if (songId != null && songId != 0) "song/$songId" else null)
        } else {
            android.widget.Toast.makeText(
                appContext,
                "Cache cleared for '$title'",
                android.widget.Toast.LENGTH_SHORT
            ).show()
        }
    }

    private fun isNetworkAvailable(context: Context): Boolean {
        val cm = context.getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager
        val net = cm?.activeNetwork ?: return false
        val caps = cm.getNetworkCapabilities(net) ?: return false
        return caps.hasCapability(android.net.NetworkCapabilities.NET_CAPABILITY_INTERNET)
    }

    @Volatile private var currentMediaId: String? = null

    private suspend fun streamStillValid(song: String, url: String): Boolean {
        val t = streamValidatedAt[song]
        if (t != null && System.currentTimeMillis() - t < STREAM_REVALIDATE_MS) return true
        val ok = YTPlayerUtils.validateStatus(url)
        if (ok) streamValidatedAt[song] = System.currentTimeMillis()
        return ok
    }

    // ── Recording verification (safety net for every non-YouTube provider) ────
    //
    // Amazon / Qobuz / TIDAL / Deezer / SoundCloud match on their own. When one of them hands
    // back a live/alternate cut, the only reliable tell is its duration. After prepare() we wait
    // for the real duration; if it disagrees with Spotify's, that provider is blocked for the
    // track, its caches are dropped and resolution runs again with the next provider (finally
    // the strict YouTube matcher).

    /** Turn off if a provider reports unreliable durations. */
    @Volatile var durationGuardEnabled = true

    private const val MATCH_PURGE_KEY = "match_v8_purged"
    private val providerBlocklist = java.util.concurrent.ConcurrentHashMap<String, Set<String>>()
    private val durationVerified: MutableSet<String> = java.util.concurrent.ConcurrentHashMap.newKeySet()
    private var durationGuard: androidx.media3.common.Player.Listener? = null
    private var durationGuardPlayer: ExoPlayer? = null

    private fun isProviderSource(source: String): Boolean =
        source.isNotBlank() && source != "YouTube" && source != "Spotify" && source != "Downloaded" &&
                source != "Local file" && !source.startsWith("Alternative")

    /** "Lossless • Qobuz" -> "Qobuz", "SpotiFLAC (Tidal)" -> "SpotiFLAC". */
    private fun providerKeyFromSource(source: String): String? =
        source.substringAfter("• ", source).trim().substringBefore(' ').substringBefore('(').trim()
            .takeIf { it.isNotBlank() }

    private fun expectedDurationMs(song: String): Long? =
        durationRegistry[song]?.toLong()?.takeIf { it > 0 }
            ?: boundState?.queue?.value?.firstOrNull { it.url == song }?.durationMs?.toLong()?.takeIf { it > 0 }

    /**
     * Main thread, right after prepare(). Returns true when the guard has taken over
     * `playWhenReady` (playback starts as soon as the duration is confirmed).
     */
    private fun armDurationGuard(p: ExoPlayer, song: String, ctx: Context): Boolean {
        durationGuard?.let { old -> runCatching { durationGuardPlayer?.removeListener(old) } }
        durationGuard = null
        if (!durationGuardEnabled || song in durationVerified) return false
        val source = currentSource
        if (!isProviderSource(source)) return false
        val providerKey = providerKeyFromSource(source) ?: return false
        val wantMs = expectedDurationMs(song) ?: return false
        if ((providerBlocklist[song]?.size ?: 0) >= 3) return false

        val listener = object : androidx.media3.common.Player.Listener {
            override fun onPlaybackStateChanged(state: Int) {
                if (state != androidx.media3.common.Player.STATE_READY) return
                p.removeListener(this)
                if (durationGuard === this) durationGuard = null
                if (currentRequest != song || player !== p) return
                val actual = p.duration
                if (actual <= 0L || actual == androidx.media3.common.C.TIME_UNSET) {
                    p.playWhenReady = playWhenResolved
                    return
                }
                val tolerance = if (song in approxDurationQueries) maxOf(8_000L, wantMs / 25) else maxOf(3_000L, wantMs / 100)
                if (abs(actual - wantMs) <= tolerance) {
                    durationVerified.add(song)
                    logResolution("✓ duration verified: ${actual / 1000}s vs ${wantMs / 1000}s ($source)")
                    p.playWhenReady = playWhenResolved
                    return
                }
                logResolution("✗ $source rejected: stream lasts ${actual / 1000}s, track is ${wantMs / 1000}s (different version)")
                Log.w(TAG, "duration guard: $source gave ${actual}ms, expected ${wantMs}ms for $song — switching provider")
                providerBlocklist.merge(song, setOf(providerKey)) { old, new -> old + new }
                invalidateResolvedStream(song)
                playSong(song, ctx, currentMediaId)
            }

            override fun onPlayerError(error: androidx.media3.common.PlaybackException) {
                p.removeListener(this)
                if (durationGuard === this) durationGuard = null
                p.playWhenReady = playWhenResolved
            }
        }
        durationGuard = listener
        durationGuardPlayer = p
        p.addListener(listener)
        return true
    }

    /**
     * The matcher changed: streams cached by the previous version (disk cache, resolved video
     * ids, ExoPlayer media cache) may point at the wrong recording. Purge them once.
     */
    @Volatile private var legacyPurgeChecked = false

    private fun purgeLegacyMatchCachesOnce(ctx: Context) {
        if (legacyPurgeChecked) return
        val prefs = ctx.getSharedPreferences("spotui_matcher", Context.MODE_PRIVATE)
        legacyPurgeChecked = true
        if (prefs.getBoolean(MATCH_PURGE_KEY, false)) return
        prefs.edit().putBoolean(MATCH_PURGE_KEY, true).apply()
        runCatching {
            com.music.spotui.data.preferences.clearAllCachedStreams(ctx)
            com.music.spotui.data.preferences.clearAllResolvedVideos(ctx)
            mediaCache?.keys?.forEach { key -> runCatching { mediaCache?.removeResource(key) } }
        }.onFailure { Log.w(TAG, "legacy match cache purge failed", it) }
    }

    fun playSong(song: String, context: Context, mediaId: String? = null) {
        val appContext = context.applicationContext
        appCtx = appContext
        purgeLegacyMatchCachesOnce(appContext)
        currentRequest = song
        val queue = boundState?.queue?.value.orEmpty()
        // Neighbours are prefetched only AFTER the requested song has been handed to the
        // player, so they never compete with it for network/CPU while it is being resolved.
        val upcomingSongs: List<String> = if (queue.isNotEmpty()) {
            val cur = queue.indexOfFirst { it.url == song || (mediaId != null && it.id.toString() == mediaId.removePrefix("song/")) }
            if (cur >= 0) queue.drop(cur + 1).take(4).map { it.url } else emptyList()
        } else emptyList()
        val startPrefetch: () -> Unit = {
            if (upcomingSongs.isNotEmpty()) prefetchList(upcomingSongs, appContext, 4)
        }
        val resolvedMediaId = mediaId ?: boundState?.queue?.value?.firstOrNull { it.url == song }?.let { "song/${it.id}" }
        ?: boundState?.songId?.value?.let { if (it != 0) "song/$it" else null }
        currentMediaId = resolvedMediaId

        val matchTrack = boundState?.queue?.value?.firstOrNull { it.url == song }
            ?: (resolvedMediaId?.removePrefix("song/")?.toIntOrNull())?.let { id ->
                boundState?.queue?.value?.firstOrNull { it.id == id }
            }
        matchTrack?.let { track ->
            if (track.title.isNotBlank()) metaTitle = track.title
            if (track.singer.isNotBlank()) metaArtist = track.singer
            if (track.coverUri.isNotBlank()) metaCover = track.coverUri
        }

        playWhenResolved = true
        boundState?.setSongUrl(song)
        cancelCrossfade()
        runCatching {
            ensurePlayer(appContext)
            player?.pause()
        }

        val immediateDownloadedPath = com.music.spotui.data.preferences.downloadedPathForQuery(appContext, song)
        if (immediateDownloadedPath != null) {
            runCatching {
                ensurePlayer(appContext)
                player?.pause()
                currentSource = "Downloaded"
                currentQuality = immediateDownloadedPath.substringAfterLast('.', "").uppercase()
                val localUri = android.net.Uri.fromFile(java.io.File(immediateDownloadedPath))
                player?.setMediaItem(MediaItem.fromUri(localUri))
                player?.prepare()
                player?.play()
            }
            boundState?.updateResolveDetailNote("Source: Downloaded Local File • Format: $currentQuality")
            updateResolveStatus(false)
            startPrefetch()
            return
        }

        if (song.startsWith("episode:") && webPlayerEnabled && SpotifyWebPlayer.canPlay &&
            com.music.spotui.data.preferences.isWebPlaybackEnabled(appContext)
        ) {
            runCatching { player?.pause() }
            currentSource = "Spotify"
            currentQuality = ""
            SpotifyWebPlayer.playEpisode(song.removePrefix("episode:"))
            return
        }

        if (webPlayerEnabled &&
            com.music.spotui.data.preferences.isWebPlaybackEnabled(appContext) &&
            SpotifyWebPlayer.canPlay
        ) {
            val spotifyId = trackIdRegistry[song] ?: spotifyTrackIdForPlayback(song)
            if (spotifyId != null) {
                runCatching { player?.pause() }
                currentSource = "Spotify"
                currentQuality = ""
                updateResolveStatus(true, "Loading Spotify track...")
                SpotifyWebPlayer.play(spotifyId)
                scope.launch {
                    delay(1500)
                    updateResolveStatus(false)
                }
                return
            }
            Log.w(TAG, "web playback on but no Spotify id for query: $song — using fallback engine")
        }
        acquireWakeLock(appContext, "spotui:playSong", 60_000L)
        scope.launch {
            try {
                val streamUrl = resolveStreamUrl(song, appContext, forPlayback = true) ?: run {
                    releaseWakeLock("spotui:playSong")
                    val existingError = boundState?.resolveError?.value
                    if (currentRequest == song && existingError.isNullOrBlank()) {
                        withContext(Dispatchers.Main) {
                            android.widget.Toast.makeText(
                                appContext, "No stream found",
                                android.widget.Toast.LENGTH_SHORT,
                            ).show()
                        }
                    }
                    if (existingError == null) {
                        boundState?.updateResolveError("No stream found")
                    }
                    updateResolveStatus(false)
                    return@launch
                }
                if (currentRequest != song) {
                    releaseWakeLock("spotui:playSong")
                    updateResolveStatus(false)
                    return@launch
                }
                // Built off the main thread (artwork lookup / cache key) so the UI thread only swaps items.
                val mediaItem = buildMediaItem(streamUrl, streamMimeType(streamUrl), song)
                withContext(Dispatchers.Main) {
                    if (currentRequest != song) {
                        releaseWakeLock("spotui:playSong")
                        updateResolveStatus(false)
                        return@withContext
                    }
                    ensurePlayer(appContext)
                    player!!.setMediaItem(mediaItem)
                    player!!.prepare()
                    if (song == restoreQuery && restorePositionMs > 0) {
                        player!!.seekTo(restorePositionMs)
                    }
                    restoreQuery = null
                    // Lossless providers do their own (unseen) matching: hold playback until the
                    // stream's real duration confirms it is the recording we asked for.
                    val guarded = armDurationGuard(player!!, song, appContext)
                    player!!.playWhenReady = playWhenResolved && !guarded
                    loadedQuery = song
                    updateResolveStatus(false)
                }
                startPrefetch()
                startPositionWatch()
            } catch (e: Exception) {
                releaseWakeLock("spotui:playSong")
                Log.e(TAG, "playSong failed for query: $song", e)
                boundState?.updateResolveError(e.message ?: "Playback failed")
                updateResolveStatus(false)
            }
        }
    }

    private fun buildMediaItem(streamUrl: String, mimeType: String? = null, songQuery: String = ""): MediaItem {
        val metadataBuilder = androidx.media3.common.MediaMetadata.Builder()
            .setTitle(metaTitle)
            .setArtist(metaArtist)
        val ctx = appCtx ?: com.music.spotui.MyApplication.instance
        com.music.spotui.util.ArtworkHelper.attachArtwork(
            metadataBuilder, ctx, metaCover, currentMediaId, streamUrl
        )
        val metadata = metadataBuilder.build()
        val query = songQuery.ifBlank { currentRequest }
        val spotifyId = trackIdRegistry[query] ?: spotifyTrackIdForPlayback(query)
        val stableKey = com.music.spotui.audio.LosslessCacheKeyFactory.buildCacheKey(spotifyId, streamUrl)

        return MediaItem.Builder()
            .apply { currentMediaId?.let { setMediaId(it) } }
            .setUri(streamUrl)
            .setCustomCacheKey(stableKey)
            .apply { if (mimeType != null) setMimeType(mimeType) }
            .setMediaMetadata(metadata)
            .build()
    }

    private fun streamMimeType(streamUrl: String): String? {
        val bare = streamUrl.substringBefore('?').lowercase()
        val query = streamUrl.substringAfter('?', "")
        val mimeParam = query.split('&')
            .firstOrNull { it.startsWith("mime=", ignoreCase = true) }
            ?.substringAfter('=')
            ?.lowercase()
            ?.let { runCatching { java.net.URLDecoder.decode(it, "UTF-8") }.getOrNull() }

        return when {
            streamUrl.startsWith("deezer://") ->
                if (streamUrl.contains("fmt=flac")) androidx.media3.common.MimeTypes.AUDIO_FLAC
                else androidx.media3.common.MimeTypes.AUDIO_MPEG
            mimeParam != null -> when {
                mimeParam.contains("audio/webm") || mimeParam.contains("webm") -> androidx.media3.common.MimeTypes.AUDIO_WEBM
                mimeParam.contains("audio/mp4") || mimeParam.contains("mp4") || mimeParam.contains("m4a") -> androidx.media3.common.MimeTypes.AUDIO_MP4
                mimeParam.contains("audio/mpeg") || mimeParam.contains("mp3") -> androidx.media3.common.MimeTypes.AUDIO_MPEG
                mimeParam.contains("audio/flac") || mimeParam.contains("flac") -> androidx.media3.common.MimeTypes.AUDIO_FLAC
                else -> null
            }
            streamUrl.startsWith("data:application/dash+xml") ||
                    bare.endsWith(".mpd") || streamUrl.contains("manifest.tidal.com") || streamUrl.contains("/manifests/") ->
                androidx.media3.common.MimeTypes.APPLICATION_MPD
            bare.endsWith(".flac") || currentSource.startsWith("Lossless") ->
                androidx.media3.common.MimeTypes.AUDIO_FLAC
            bare.endsWith(".webm") -> androidx.media3.common.MimeTypes.AUDIO_WEBM
            bare.endsWith(".mp4") || bare.endsWith(".m4a") -> androidx.media3.common.MimeTypes.AUDIO_MP4
            bare.endsWith(".mp3") -> androidx.media3.common.MimeTypes.AUDIO_MPEG
            else -> null
        }
    }

    private val prefetchSemaphore = Semaphore(2)
    private val prefetchInFlight: MutableSet<String> = java.util.concurrent.ConcurrentHashMap.newKeySet()
    @Volatile private var prefetchTargets: Set<String> = emptySet()

    fun prefetch(song: String, context: Context) = prefetchInternal(song, context, listed = false)

    private fun prefetchInternal(song: String, context: Context, listed: Boolean) {
        if (song.isBlank() || streamCache.containsKey(song)) return
        val appContext = context.applicationContext
        if (webPlaybackActive()) return
        if (!prefetchInFlight.add(song)) return
        scope.launch {
            try {
                prefetchSemaphore.withPermit {
                    // The queue may have moved on while we waited for a slot.
                    if (listed && song !in prefetchTargets) return@withPermit
                    if (streamCache.containsKey(song)) return@withPermit
                    acquireWakeLock(appContext, "spotui:prefetch", 30_000L)
                    try {
                        val url = withTimeoutOrNull(45_000L) {
                            runCatching { resolveStreamUrl(song, appContext, forPlayback = false) }.getOrNull()
                        }
                        if (url != null && !(listed && song !in prefetchTargets)) {
                            awaitCurrentPlaybackReady()
                            cacheIntro(url, appContext, song)
                        }
                    } finally {
                        releaseWakeLock("spotui:prefetch")
                    }
                }
            } finally {
                prefetchInFlight.remove(song)
            }
        }
    }

    /** Don't start heavy preloading while the current song is still buffering its first bytes. */
    private suspend fun awaitCurrentPlaybackReady(timeoutMs: Long = 8_000L) {
        withTimeoutOrNull(timeoutMs) {
            while (true) {
                val state = withContext(Dispatchers.Main) { player?.playbackState }
                if (state != androidx.media3.common.Player.STATE_BUFFERING) break
                delay(200L)
            }
        }
    }

    fun prefetchList(songs: List<String>, context: Context, count: Int = 4) {
        val targetSongs = songs.take(count)
        prefetchTargets = targetSongs.toSet()
        if (targetSongs.isNotEmpty()) {
            Log.d(TAG, "Preloading next ${targetSongs.size} songs in queue: $targetSongs")
            targetSongs.forEach { songUrl ->
                prefetchInternal(songUrl, context, listed = true)
            }
        }
    }

    private const val PRELOAD_BYTES = 15L * 1024 * 1024

    @androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)
    @Volatile private var mediaCache: androidx.media3.datasource.cache.SimpleCache? = null

    private fun mediaCache(context: Context): androidx.media3.datasource.cache.SimpleCache =
        mediaCache ?: synchronized(this) {
            mediaCache ?: androidx.media3.datasource.cache.SimpleCache(
                java.io.File(context.cacheDir, "media"),
                androidx.media3.datasource.cache.LeastRecentlyUsedCacheEvictor(256L * 1024 * 1024),
                androidx.media3.database.StandaloneDatabaseProvider(context),
            ).also { mediaCache = it }
        }

    private fun cacheDataSourceFactory(context: Context): androidx.media3.datasource.cache.CacheDataSource.Factory {
        val http = androidx.media3.datasource.DefaultHttpDataSource.Factory()
            .setUserAgent(
                "com.google.ios.youtube/21.03.1 (iPhone16,2; U; CPU iOS 18_2 like Mac OS X;)",
            )
            .setAllowCrossProtocolRedirects(true)
        val upstream = androidx.media3.datasource.DefaultDataSource.Factory(context, http)
        return androidx.media3.datasource.cache.CacheDataSource.Factory()
            .setCache(mediaCache(context))
            .setUpstreamDataSourceFactory(upstream)
            .setFlags(androidx.media3.datasource.cache.CacheDataSource.FLAG_IGNORE_CACHE_ON_ERROR)
            .setCacheKeyFactory(com.music.spotui.audio.LosslessCacheKeyFactory)
    }

    private fun createResilientDataSourceFactory(context: Context): androidx.media3.datasource.DataSource.Factory {
        val cacheFactory = cacheDataSourceFactory(context)

        val resolvingFactory = androidx.media3.datasource.ResolvingDataSource.Factory(cacheFactory) { dataSpec ->
            if (!dataSpec.key.isNullOrBlank()) {
                return@Factory dataSpec
            }
            val uriStr = dataSpec.uri.toString()
            val cleanId = trackIdRegistry.entries.firstOrNull { (_, spotifyId) ->
                spotifyId.isNotBlank() && uriStr.contains(spotifyId)
            }?.value

            val stableKey = com.music.spotui.audio.LosslessCacheKeyFactory.buildCacheKey(cleanId, uriStr)
            dataSpec.buildUpon()
                .setKey(stableKey)
                .build()
        }

        val resilientFactory = com.music.spotui.audio.ResilientPlaybackDataSourceFactory(resolvingFactory)

        return com.music.spotui.audio.LiveFlacBitrateDataSourceFactory(
            upstreamFactory = resilientFactory,
            isEnabled = { currentSource.startsWith("Lossless") },
            mediaIdResolver = { dataSpec ->
                dataSpec.key ?: dataSpec.uri.toString()
            },
            isParserCandidate = { dataSpec, mediaId ->
                dataSpec.uri.toString().contains(".flac", ignoreCase = true) ||
                        currentSource.startsWith("Lossless")
            },
            sampleRateProvider = { null },
            onBitrate = { mediaId, bitrate, _, _ ->
                if (currentSource.startsWith("Lossless")) {
                    val kbps = bitrate / 1000
                    val baseQuality = currentQuality.substringBefore(" •")
                    val newQuality = if (baseQuality.isNotBlank()) "$baseQuality • $kbps kbps" else "$kbps kbps"
                    currentQuality = newQuality
                }
            }
        )
    }

    private fun cacheIntro(url: String, appContext: Context, song: String = "") {
        if (!url.startsWith("http")) return
        if (!com.music.spotui.data.preferences.isPreloadEnabled(appContext)) return
        runCatching {
            val ds = cacheDataSourceFactory(appContext).createDataSource()
            val query = song.ifBlank { currentRequest }
            val spotifyId = trackIdRegistry[query] ?: spotifyTrackIdForPlayback(query)
            val stableKey = com.music.spotui.audio.LosslessCacheKeyFactory.buildCacheKey(spotifyId, url)
            val spec = androidx.media3.datasource.DataSpec.Builder()
                .setUri(android.net.Uri.parse(url))
                .setKey(stableKey)
                .setLength(PRELOAD_BYTES)
                .build()
            androidx.media3.datasource.cache.CacheWriter(ds, spec, null, null).cache()
        }.onFailure { Log.d(TAG, "intro preload skipped: ${it.message}") }
    }

    private val inFlightResolutions = java.util.concurrent.ConcurrentHashMap<String, kotlinx.coroutines.Deferred<String?>>()

    internal suspend fun resolveStreamUrl(song: String, appContext: Context, forPlayback: Boolean = false): String? {
        if (song.startsWith("content://") || song.startsWith("file://")) {
            if (forPlayback) {
                currentSource = "Local file"
                currentQuality = song.substringBefore('?').substringAfterLast('.', "")
                    .uppercase().takeIf { it.length in 2..5 }.orEmpty()
            }
            return song
        }

        val existingDeferred = inFlightResolutions[song]
        if (existingDeferred != null && existingDeferred.isActive) {
            val trackMeta = metadataRegistry[song]
            val fallbackSong = boundState?.queue?.value?.firstOrNull { it.url == song }
            val logArtist = trackMeta?.artist ?: fallbackSong?.singer ?: metaArtist
            val logTitle = trackMeta?.title ?: fallbackSong?.title ?: cleanTrackTitle(song, logArtist)
            if (forPlayback) {
                logResolution("Awaiting in-flight background resolution for '$logTitle'...")
                updateResolveStatus(true, "Resolving stream...")
            }
            val result = runCatching { existingDeferred.await() }.getOrNull()
            if (result != null) {
                if (forPlayback) {
                    val source = sourceCache[song] ?: "Lossless"
                    val quality = qualityCache[song] ?: ""
                    currentSource = source
                    currentQuality = quality
                    val note = if (quality.isNotBlank()) "Source: $source • Format: $quality" else "Source: $source"
                    boundState?.updateResolveDetailNote(note)
                    updateResolveStatus(false)
                }
                return result
            }
        }

        val deferred = scope.async {
            doResolveStreamUrl(song, appContext, forPlayback)
        }
        inFlightResolutions[song] = deferred
        try {
            return deferred.await()
        } finally {
            inFlightResolutions.remove(song, deferred)
        }
    }

    private suspend fun doResolveStreamUrl(song: String, appContext: Context, forPlayback: Boolean): String? {

        val quality = com.music.spotui.data.preferences.currentStreamingQuality(appContext)
        val expectedTier = quality.name

        val meta = metadataRegistry[song]
        val matchSong = boundState?.queue?.value?.firstOrNull { it.url == song }
        val songArtist = meta?.artist?.ifBlank { null } ?: matchSong?.singer?.ifBlank { null } ?: if (forPlayback) metaArtist else ""
        val rawTitle = meta?.title?.ifBlank { null } ?: matchSong?.title?.ifBlank { null } ?: cleanTrackTitle(song, songArtist)
        val cleanTitle = cleanTrackTitle(rawTitle, songArtist)
        val songAlbum = meta?.album ?: matchSong?.album
        val songDurationMs = durationRegistry[song]?.toLong() ?: matchSong?.durationMs?.takeIf { it > 0 }?.toLong()
        val flacSpotifyId = trackIdRegistry[song] ?: matchSong?.spotifyTrackId?.ifBlank { null } ?: spotifyTrackIdForPlayback(song)

        if (forPlayback) {
            resolutionLogs.clear()
            logResolution("Resolving stream for '$cleanTitle' by '$songArtist'")
            boundState?.updateResolveError(null)
            updateResolveStatus(true, "Checking alternative source...")
        }
        alternativeStreamForPlayback(song, appContext)?.let { alt ->
            return when {
                alt.isLocal -> {
                    if (forPlayback) {
                        currentSource = "Alternative file"
                        currentQuality = alt.label.substringAfterLast('.', "").uppercase().takeIf { it.length in 2..5 }.orEmpty()
                        val note = "Source: Alternative File • Format: ${currentQuality.ifBlank { "Local" }}"
                        logResolution("✓ Playing user alternative local file")
                        boundState?.updateResolveDetailNote(note)
                        updateResolveStatus(false)
                    }
                    alt.value
                }
                alt.isYouTube -> {
                    if (forPlayback) {
                        currentSource = "Alternative YouTube"
                        currentQuality = ""
                        logResolution("Resolving user alternative YouTube link...")
                    }
                    val playback = resolveYtPlayback(alt.value, quality.audioQuality, appContext, forPlayback = forPlayback) ?: run {
                        if (forPlayback) updateResolveStatus(false)
                        return null
                    }
                    val codec = playback.format.mimeType
                        .substringAfter("codecs=\"", "").substringBefore('"').substringBefore('.')
                        .uppercase()
                    val ytQuality = listOf(codec, "${playback.format.bitrate / 1000} kbps")
                        .filter { it.isNotBlank() }.joinToString(" ")
                    if (forPlayback) {
                        currentQuality = ytQuality
                        val note = "Source: Alternative YouTube • Format: $ytQuality"
                        logResolution("✓ Alternative YouTube stream resolved ($ytQuality)")
                        boundState?.updateResolveDetailNote(note)
                        updateResolveStatus(false)
                    }

                    streamCache[song] = playback.streamUrl
                    streamValidatedAt[song] = System.currentTimeMillis()
                    sourceCache[song] = if (forPlayback) currentSource else "Alternative YouTube"
                    qualityCache[song] = if (forPlayback) currentQuality else ytQuality

                    playback.streamUrl
                }
                else -> {
                    if (forPlayback) updateResolveStatus(false)
                    null
                }
            }
        }

        if (forPlayback) {
            updateResolveStatus(true, "Checking cache...")
        }
        val networkAvailable = isNetworkAvailable(appContext)
        streamCache[song]?.let { url ->
            if (qualityTierCache[song] == expectedTier || qualityTierCache[song] == null) {
                if (!networkAvailable || streamStillValid(song, url)) {
                    if (forPlayback) {
                        currentSource = sourceCache[song] ?: "YouTube"
                        currentQuality = qualityCache[song] ?: ""
                        val src = currentSource
                        val q = currentQuality
                        val note = if (q.isNotBlank()) "Source: $src • Format: $q (Loaded from memory cache)" else "Source: $src (Loaded from memory cache)"
                        logResolution("✓ Stream served directly from session memory cache ($src, $q)")
                        boundState?.updateResolveDetailNote(note)
                        updateResolveStatus(false)
                    }
                    return url
                } else {
                    streamCache.remove(song)
                    sourceCache.remove(song)
                    qualityCache.remove(song)
                    qualityTierCache.remove(song)
                }
            } else {
                streamCache.remove(song)
                sourceCache.remove(song)
                qualityCache.remove(song)
                qualityTierCache.remove(song)
            }
        }
        if (forPlayback) {
            updateResolveStatus(true, "Checking saved cache...")
        }
        com.music.spotui.data.preferences.getCachedStream(appContext, song, expectedTier = expectedTier)?.let { (url, source, cachedQuality) ->
            if (!networkAvailable || streamStillValid(song, url)) {
                streamCache[song] = url
                sourceCache[song] = source
                qualityCache[song] = cachedQuality
                qualityTierCache[song] = expectedTier
                if (forPlayback) {
                    currentSource = source
                    currentQuality = cachedQuality
                    val note = if (cachedQuality.isNotBlank()) "Source: $source • Format: $cachedQuality (Loaded from disk cache)" else "Source: $source (Loaded from disk cache)"
                    logResolution("✓ Stream served from persistent disk cache ($source, $cachedQuality)")
                    boundState?.updateResolveDetailNote(note)
                    updateResolveStatus(false)
                }
                return url
            } else {
                com.music.spotui.data.preferences.clearCachedStream(appContext, song)
            }
        }
        if (forPlayback) {
            updateResolveStatus(true, "Locating local file...")
        }
        com.music.spotui.data.preferences.downloadedPathForQuery(appContext, song)?.let { path ->
            if (forPlayback) {
                currentSource = "Downloaded"
                currentQuality = path.substringAfterLast('.', "").uppercase()
                val note = "Source: Downloaded Local File • Format: $currentQuality"
                logResolution("✓ Playing downloaded offline file ($currentQuality)")
                boundState?.updateResolveDetailNote(note)
                updateResolveStatus(false)
            }
            return android.net.Uri.fromFile(java.io.File(path)).toString()
        }

        val shouldTryFlac = losslessStreaming && quality.lossless
        val shouldTryYoutube = youtubeEnabled

        if (!shouldTryFlac && !shouldTryYoutube) {
            Log.w(TAG, "no stream source available for: $song")
            if (forPlayback) updateResolveStatus(false)
            return null
        }

        if (forPlayback) {
            if (shouldTryFlac) {
                currentSource = "Lossless"
                currentQuality = ""
                updateResolveStatus(true, "Searching High-Res Lossless Audio Providers...")
                logResolution("Target Quality: ${if (losslessHiRes) "24-bit Hi-Res / Ultra HD" else "16-bit FLAC"}")
            } else if (shouldTryYoutube) {
                currentSource = "YouTube"
                currentQuality = ""
                updateResolveStatus(true, "Locating YouTube source...")
                logResolution("High-res FLAC disabled or cellular quality cap in effect. Using YouTube Music.")
            }
        }

        val flacDeferred = if (shouldTryFlac) {
            scope.async {
                withTimeoutOrNull(6000L) { runCatching { ensureSpotifyMatchMetadata(song) } }
                val isrc = (flacSpotifyId?.let { isrcRegistry[it] }) ?: runCatching {
                    withTimeoutOrNull(4000L) {
                        flacSpotifyId?.let { id ->
                            com.metrolist.spotify.Spotify.track(id).getOrNull()?.isrc?.also { code ->
                                isrcRegistry[id] = code
                            }
                        }
                    }
                }.getOrNull()
                val durationMs = songDurationMs ?: durationRegistry[song]?.toLong()
                val providerOrder = com.music.spotui.data.preferences.getEnabledAudioProviderOrder(appContext)

                if (forPlayback) {
                    logResolution("Priority Order: ${providerOrder.joinToString(" → ") { it.displayName }}")
                    if (isrc != null) logResolution("Spotify ISRC: $isrc")
                    logResolution("Query: mediaId=${flacSpotifyId ?: "(none)"}, title='$cleanTitle', artist='$songArtist', isrc=${isrc ?: "(none)"}, duration=${durationMs ?: "(none)"}ms")
                }

                var dzLossyFallback: Triple<String, String, String>? = null

                val providerDeferreds = providerOrder.map { item ->
                    val blockedKeys = providerBlocklist[song].orEmpty()
                    if (blockedKeys.any { key -> item.displayName.contains(key, ignoreCase = true) }) {
                        if (forPlayback) logResolution("⊘ ${item.displayName}: skipped (previously returned a different version)")
                        return@map item to null
                    }
                    item to scope.async(Dispatchers.IO) {
                        when (item) {
                            com.music.spotui.data.preferences.AudioProviderOrderItem.AMAZON -> {
                                if (forPlayback) logResolution("Attempting Amazon Music...")
                                runCatching {
                                    val res = com.music.spotui.providers.AmazonAudioProvider.resolve(
                                        appContext,
                                        com.music.spotui.providers.AmazonAudioProvider.Query(
                                            mediaId = flacSpotifyId ?: song,
                                            title = cleanTitle,
                                            artists = listOfNotNull(songArtist.takeIf { it.isNotBlank() }),
                                            album = songAlbum,
                                            durationMs = durationMs,
                                            quality = if (losslessHiRes) "HI_RES" else "LOSSLESS",
                                        )
                                    )
                                    val q = if (losslessHiRes && (res.sampleRate ?: 0) > 44100) "24-bit Ultra HD" else "16-bit FLAC"
                                    if (forPlayback) logResolution("✓ Amazon Music SUCCESS ($q, ASIN: ${res.trackId})")
                                    Triple(res.mediaUri, "Amazon Music", q)
                                }.onFailure { err ->
                                    if (forPlayback) logResolution("✗ Amazon Music: ${err.javaClass.simpleName}: ${err.message?.take(200)}")
                                }.getOrNull()
                            }
                            com.music.spotui.data.preferences.AudioProviderOrderItem.QOBUZ -> {
                                if (forPlayback) logResolution("Attempting Qobuz...")
                                runCatching {
                                    val res = com.music.spotui.providers.QobuzAudioProvider.resolve(
                                        com.music.spotui.providers.QobuzAudioProvider.Query(
                                            mediaId = flacSpotifyId ?: song,
                                            title = cleanTitle,
                                            artists = listOfNotNull(songArtist.takeIf { it.isNotBlank() }),
                                            album = songAlbum,
                                            isrc = isrc,
                                            durationMs = durationMs,
                                        ),
                                        preferHiRes = losslessHiRes,
                                    )
                                    val q = if (losslessHiRes && (res.sampleRate ?: 0) > 44100) "24-bit Hi-Res" else "16-bit FLAC"
                                    if (forPlayback) logResolution("✓ Qobuz SUCCESS ($q)")
                                    Triple(res.mediaUri, "Qobuz", q)
                                }.onFailure { err ->
                                    if (forPlayback) logResolution("✗ Qobuz: ${err.javaClass.simpleName}: ${err.message?.take(200)}")
                                }.getOrNull()
                            }
                            com.music.spotui.data.preferences.AudioProviderOrderItem.TIDAL -> {
                                if (forPlayback) logResolution("Attempting TIDAL...")
                                runCatching {
                                    val tQuality = if (losslessHiRes) com.music.spotui.providers.TidalAudioQuality.HI_RES_LOSSLESS else com.music.spotui.providers.TidalAudioQuality.FLAC
                                    val res = com.music.spotui.providers.TidalAudioProvider.resolve(
                                        com.music.spotui.providers.TidalAudioProvider.Query(
                                            mediaId = flacSpotifyId ?: song,
                                            title = cleanTitle,
                                            artists = listOfNotNull(songArtist.takeIf { it.isNotBlank() }),
                                            album = songAlbum,
                                            isrc = isrc,
                                            durationMs = durationMs,
                                        ),
                                        audioQuality = tQuality,
                                    )
                                    val q = if (losslessHiRes) "24-bit Hi-Res" else "16-bit FLAC"
                                    if (forPlayback) logResolution("✓ TIDAL SUCCESS ($q)")
                                    Triple(res.mediaUri, "TIDAL", q)
                                }.onFailure { err ->
                                    if (forPlayback) logResolution("✗ TIDAL: ${err.javaClass.simpleName}: ${err.message?.take(200)}")
                                }.getOrNull()
                            }
                            com.music.spotui.data.preferences.AudioProviderOrderItem.DEEZER -> {
                                if (forPlayback) logResolution("Attempting Deezer...")
                                if (deezerEnabled && com.music.spotui.data.preferences.isDeezerEnabled(appContext)) {
                                    val dzRes = runCatching {
                                        com.music.spotui.deezer.DeezerSource.resolve(
                                            appContext,
                                            spotifyId = flacSpotifyId,
                                            isrc = isrc,
                                            searchQuery = "$cleanTitle $songArtist".trim(),
                                        )
                                    }.getOrNull()
                                    if (dzRes is com.music.spotui.deezer.DeezerSource.Result.Success) {
                                        if (dzRes.mimeFlac || !quality.lossless) {
                                            if (forPlayback) logResolution("✓ Deezer Direct SUCCESS (${dzRes.qualityLabel})")
                                            return@async Triple(dzRes.uri, "Deezer", dzRes.qualityLabel)
                                        } else {
                                            if (forPlayback) logResolution("ℹ Deezer Direct is ${dzRes.qualityLabel} (lossy); searching lossless providers first.")
                                            dzLossyFallback = Triple(dzRes.uri, "Deezer", dzRes.qualityLabel)
                                        }
                                    }
                                }
                                runCatching {
                                    val res = com.music.spotui.providers.DeezerAudioProvider.resolve(
                                        com.music.spotui.providers.DeezerAudioProvider.Query(
                                            mediaId = flacSpotifyId ?: song,
                                            title = cleanTitle,
                                            artists = listOfNotNull(songArtist.takeIf { it.isNotBlank() }),
                                            album = songAlbum,
                                            isrc = isrc,
                                            durationMs = durationMs,
                                        )
                                    )
                                    if (forPlayback) logResolution("✓ Deezer Mirror SUCCESS (16-bit FLAC)")
                                    Triple(res.mediaUri, "Deezer", "16-bit FLAC")
                                }.onFailure { err ->
                                    if (forPlayback) logResolution("✗ Deezer: ${err.javaClass.simpleName}: ${err.message?.take(200)}")
                                }.getOrNull()
                            }
                            com.music.spotui.data.preferences.AudioProviderOrderItem.SPOTIFLAC -> {
                                if (flacSpotifyId != null) {
                                    if (forPlayback) logResolution("Attempting SpotiFLAC (Community)...")
                                    runCatching {
                                        when (val res = com.metrolist.spotify.SpotiFlac.resolve(flacSpotifyId, isrc = isrc, preferHiRes = losslessHiRes)) {
                                            is com.metrolist.spotify.SpotiFlac.Result.Success -> {
                                                val prov = res.track.provider.replaceFirstChar { it.uppercase() }
                                                val q = "FLAC ${res.track.quality}-bit"
                                                if (forPlayback) logResolution("✓ SpotiFLAC SUCCESS ($prov, $q)")
                                                Triple(res.track.url, "SpotiFLAC ($prov)", q)
                                            }
                                            else -> null
                                        }
                                    }.onFailure { err ->
                                        if (forPlayback) logResolution("✗ SpotiFLAC: ${err.javaClass.simpleName}: ${err.message?.take(200)}")
                                    }.getOrNull()
                                } else {
                                    if (forPlayback) logResolution("⊘ SpotiFLAC: skipped (no Spotify track ID)")
                                    null
                                }
                            }
                            com.music.spotui.data.preferences.AudioProviderOrderItem.SOUNDCLOUD -> {
                                if (forPlayback) logResolution("Attempting SoundCloud...")
                                runCatching {
                                    val res = com.music.spotui.providers.SoundCloudAudioProvider.resolve(
                                        com.music.spotui.providers.SoundCloudAudioProvider.Query(
                                            mediaId = flacSpotifyId ?: song,
                                            title = cleanTitle,
                                            artists = listOfNotNull(songArtist.takeIf { it.isNotBlank() }),
                                            album = songAlbum,
                                            isrc = isrc,
                                            durationMs = durationMs,
                                        )
                                    )
                                    if (forPlayback) logResolution("✓ SoundCloud SUCCESS (HQ Audio)")
                                    Triple(res.mediaUri, "SoundCloud", "HQ Audio")
                                }.onFailure { err ->
                                    if (forPlayback) logResolution("✗ SoundCloud: ${err.javaClass.simpleName}: ${err.message?.take(200)}")
                                }.getOrNull()
                            }
                            com.music.spotui.data.preferences.AudioProviderOrderItem.YOUTUBE_MUSIC -> null
                        }
                    }
                }

                val resultsMap = withTimeoutOrNull(8000L) {
                    providerDeferreds.mapNotNull { (item, deferred) ->
                        deferred?.await()?.let { item to it }
                    }.toMap()
                } ?: emptyMap()

                var result: Triple<String, String, String>? = null
                for (item in providerOrder) {
                    if (resultsMap.containsKey(item)) {
                        result = resultsMap[item]
                        break
                    }
                }
                if (result == null && dzLossyFallback != null) {
                    if (forPlayback) logResolution("Falling back to Deezer Direct (${dzLossyFallback.third}).")
                    result = dzLossyFallback
                }
                if (result == null && forPlayback) {
                    logResolution("All lossless providers exhausted. Proceeding to YouTube Music fallback.")
                }
                result
            }
        } else null

        val ytDeferred = if (shouldTryYoutube) {
            scope.async {
                val runForPlayback = forPlayback && !shouldTryFlac
                if (runForPlayback) {
                    currentSource = "YouTube"
                    currentQuality = ""
                    updateResolveStatus(true, "Locating YouTube source...")
                }
                resolveYtPlayback(song, quality.audioQuality, appContext, forPlayback = runForPlayback)
            }
        } else null

        var flacFailReason: String? = null

        if (flacDeferred != null) {
            val flacResult = if (ytDeferred != null && forPlayback) {
                kotlinx.coroutines.withTimeoutOrNull(3000L) {
                    flacDeferred.await()
                }
            } else {
                flacDeferred.await()
            }
            if (flacResult != null) {
                val (url, providerName, flacQuality) = flacResult
                val isLossless = flacQuality.contains("FLAC", ignoreCase = true) ||
                        flacQuality.contains("Hi-Res", ignoreCase = true) ||
                        flacQuality.contains("Ultra HD", ignoreCase = true)
                val sourceLabel = if (isLossless) "Lossless • $providerName" else providerName
                Log.d(TAG, "Resolved via $providerName ($flacQuality) [lossless=$isLossless] for: $song")
                if (forPlayback) {
                    currentSource = sourceLabel
                    currentQuality = flacQuality
                    val note = "Source: $providerName • Format: $flacQuality"
                    boundState?.updateResolveDetailNote(note)
                    updateResolveStatus(false)
                }
                streamCache[song] = url
                streamValidatedAt.remove(song)
                sourceCache[song] = sourceLabel
                qualityCache[song] = flacQuality
                qualityTierCache[song] = expectedTier
                com.music.spotui.data.preferences.setCachedStream(
                    appContext, song, url, sourceLabel, flacQuality,
                    21600, qualityTier = expectedTier,
                )
                ytDeferred?.cancelAndJoin()
                return url
            } else {
                flacFailReason = "Lossless providers timed out or failed"
                flacDeferred.cancel()
                Log.w(TAG, "Lossless providers timed out or failed, using YouTube fallback for: $song")
            }
        }

        if (!shouldTryYoutube) {
            Log.w(TAG, "YouTube fallback disabled — no stream for: $song")
            if (forPlayback) updateResolveStatus(false)
            return null
        }

        if (forPlayback) {
            currentSource = "YouTube"
            currentQuality = ""
            val note = if (flacFailReason != null) {
                "FLAC unavailable ($flacFailReason) → Switched to YouTube fallback"
            } else {
                "Source: YouTube • Quality: ${quality.audioQuality}"
            }
            boundState?.updateResolveDetailNote(note)
            if (ytDeferred != null && !ytDeferred.isCompleted) {
                updateResolveStatus(true, "Locating YouTube source...")
            }
        }

        val playback = ytDeferred!!.await()
        if (playback == null) {
            if (forPlayback) {
                val cacheKey = "$song|${com.metrolist.innertube.YouTube.SearchFilter.FILTER_SONG.value}|MATCH_V12"
                val cachedCandidates = videoCandidatesCache[cacheKey]
                val reason = when {
                    cachedCandidates == null -> "YouTube search failed"
                    cachedCandidates.isEmpty() -> "No matching song found on YouTube"
                    else -> lastYtFailureReason ?: "All candidates failed to resolve"
                }
                boundState?.updateResolveError(reason)
                updateResolveStatus(false)
            }
            return null
        }
        val codec = playback.format.mimeType
            .substringAfter("codecs=\"", "").substringBefore('"').substringBefore('.')
            .uppercase()
        val ytQuality = listOf(codec, "${playback.format.bitrate / 1000} kbps")
            .filter { it.isNotBlank() }.joinToString(" ")
        if (forPlayback) {
            currentQuality = ytQuality
            updateResolveStatus(false)
        }
        streamCache[song] = playback.streamUrl
        streamValidatedAt[song] = System.currentTimeMillis()
        sourceCache[song] = "YouTube"
        qualityCache[song] = ytQuality
        qualityTierCache[song] = expectedTier
        com.music.spotui.data.preferences.setCachedStream(
            appContext, song, playback.streamUrl, "YouTube", ytQuality,
            playback.streamExpiresInSeconds, qualityTier = expectedTier,
        )
        return playback.streamUrl
    }

    private fun alternativeStreamForPlayback(
        song: String,
        appContext: Context,
    ): com.music.spotui.data.preferences.AlternativeStream? {
        val key = alternativeKeyRegistry[song]
            ?: spotifyTrackIdForPlayback(song)?.let {
                com.music.spotui.data.preferences.alternativeStreamKeyForSpotifyId(it)
            }
        return key?.let { com.music.spotui.data.preferences.getAlternativeStream(appContext, it) }
    }

    private val downloading = java.util.Collections.newSetFromMap(
        java.util.concurrent.ConcurrentHashMap<String, Boolean>()
    )
    @Volatile var onDownloadsChanged: (() -> Unit)? = null

    private val downloadProgress = java.util.concurrent.ConcurrentHashMap<String, Int>()
    private val downloadingSongs =
        java.util.concurrent.ConcurrentHashMap<String, com.music.spotui.data.entity.SongsModel>()

    fun isDownloading(query: String): Boolean = downloading.contains(query)

    fun downloadProgress(query: String): Int = downloadProgress[query] ?: -1

    fun downloadingSnapshot(): List<Pair<com.music.spotui.data.entity.SongsModel, Int>> =
        downloadingSongs.entries.map { (q, song) -> song to (downloadProgress[q] ?: 0) }

    @Volatile var lastDownloadError: String? = null

    private fun openDownloadConn(url: String): java.net.HttpURLConnection =
        (java.net.URL(url).openConnection() as java.net.HttpURLConnection).apply {
            connectTimeout = 15000
            readTimeout = 30000
            instanceFollowRedirects = true
            setRequestProperty(
                "User-Agent",
                "Mozilla/5.0 (Linux; Android 14; Pixel) AppleWebKit/537.36 " +
                        "(KHTML, like Gecko) Chrome/120.0.0.0 Mobile Safari/537.36",
            )
        }

    private fun httpDownloadRanged(url: String, tmpFile: java.io.File, query: String): Boolean {
        val chunk = 8L * 1024 * 1024 // 8 MB
        var total = -1L
        var position = 0L
        downloadProgress[query] = 0
        try {
            java.io.BufferedOutputStream(tmpFile.outputStream()).use { output ->
                outer@ while (true) {
                    val end = if (total > 0) minOf(position + chunk - 1, total - 1) else position + chunk - 1
                    var attempt = 0
                    var fullBody = false
                    while (true) {
                        attempt++
                        val conn = openDownloadConn(url)
                        conn.setRequestProperty("Range", "bytes=$position-$end")
                        try {
                            val code = conn.responseCode
                            if (code !in 200..299) {
                                lastDownloadError = "Stream returned HTTP $code"
                                return false
                            }
                            if (total < 0) {
                                total = conn.getHeaderField("Content-Range")
                                    ?.substringAfter('/')?.toLongOrNull()
                                    ?: conn.contentLengthLong
                            }
                            fullBody = code == 200
                            conn.inputStream.use { input ->
                                val buf = ByteArray(64 * 1024)
                                while (true) {
                                    val r = input.read(buf)
                                    if (r < 0) break
                                    output.write(buf, 0, r)
                                    position += r
                                    if (total > 0) {
                                        val pct = ((position * 100) / total).toInt().coerceIn(0, 100)
                                        if (downloadProgress[query] != pct) {
                                            downloadProgress[query] = pct
                                            onDownloadsChanged?.invoke()
                                        }
                                    }
                                }
                            }
                            break
                        } catch (e: Exception) {
                            Log.w(TAG, "chunk @${position} failed (attempt $attempt): ${e.message}")
                            if (attempt >= 4) {
                                lastDownloadError = e.message ?: "Connection reset"
                                return false
                            }
                        } finally {
                            conn.disconnect()
                        }
                    }
                    if (fullBody) { total = position; break@outer }
                    if (total in 1..position) break@outer
                    if (total < 0) break@outer
                }
            }
            downloadProgress[query] = 100
            return total <= 0 || position >= total
        } catch (e: Exception) {
            lastDownloadError = e.message ?: "Download error"
            return false
        }
    }

    private val downloadSemaphore = Semaphore(2)

    fun downloadAll(songs: List<com.music.spotui.data.entity.SongsModel>, context: Context) {
        val appContext = context.applicationContext
        scope.launch {
            songs.forEach { song ->
                launch {
                    downloadSemaphore.withPermit {
                        downloadSongSuspend(song, appContext)
                    }
                }
            }
        }
    }

    fun allDownloaded(
        songs: List<com.music.spotui.data.entity.SongsModel>,
        context: Context,
    ): Boolean {
        if (songs.isEmpty()) return false
        val appContext = context.applicationContext
        return songs.all {
            com.music.spotui.data.preferences.isDownloaded(appContext, it.id.toString())
        }
    }

    private suspend fun downloadSongSuspend(
        song: com.music.spotui.data.entity.SongsModel,
        appContext: Context,
    ): Boolean {
        val query = song.url
        if (query.isBlank() ||
            com.music.spotui.data.preferences.isDownloaded(appContext, song.id.toString()) ||
            !downloading.add(query)
        ) return true
        downloadingSongs[query] = song
        downloadProgress[query] = 0
        onDownloadsChanged?.invoke()
        lastDownloadError = null

        var ok = false
        var attempt = 0
        while (attempt < 3 && !ok) {
            attempt++
            ok = runCatching { downloadToFile(song, appContext) }
                .onFailure { lastDownloadError = it.message ?: "Unexpected error" }
                .getOrDefault(false)
            if (!ok && attempt < 3) {
                delay(1000L)
            }
        }

        downloading.remove(query)
        downloadProgress.remove(query)
        downloadingSongs.remove(query)
        withContext(Dispatchers.Main) {
            if (!ok) {
                android.widget.Toast.makeText(
                    appContext,
                    "Download failed: ${lastDownloadError ?: "unknown reason"}",
                    android.widget.Toast.LENGTH_LONG,
                ).show()
            }
            onDownloadsChanged?.invoke()
        }
        return ok
    }

    fun downloadSong(
        song: com.music.spotui.data.entity.SongsModel,
        context: Context,
        onComplete: (Boolean) -> Unit = {},
    ) {
        val appContext = context.applicationContext
        scope.launch {
            val ok = downloadSemaphore.withPermit {
                downloadSongSuspend(song, appContext)
            }
            onComplete(ok)
        }
    }

    private suspend fun downloadToFile(
        song: com.music.spotui.data.entity.SongsModel,
        appContext: Context,
    ): Boolean {
        val dlQuality = com.music.spotui.data.preferences.getDownloadQuality(appContext)
        val losslessDownloading = losslessStreaming

        if (dlQuality.lossless || losslessDownloading) {
            val flacOk = kotlinx.coroutines.withTimeoutOrNull(45_000) {
                runCatching { downloadLosslessTrackToFile(song, appContext) }.getOrDefault(false)
            } ?: false
            if (flacOk) return true
        }

        if (!youtubeEnabled) {
            lastDownloadError = "Track not available on configured audio providers"
            return false
        }

        val query = song.url
        val alt = alternativeStreamForPlayback(query, appContext)

        val dir = java.io.File(appContext.filesDir, "downloads").apply { mkdirs() }
        val outFile = java.io.File(dir, "${song.id}.m4a")
        val tmpFile = java.io.File(dir, "${song.id}.part")

        if (alt != null && alt.isLocal) {
            val copyOk = runCatching {
                val uri = android.net.Uri.parse(alt.value)
                appContext.contentResolver.openInputStream(uri)?.use { input ->
                    java.io.FileOutputStream(tmpFile).use { output ->
                        input.copyTo(output)
                    }
                }
                true
            }.getOrDefault(false)

            if (!copyOk || !tmpFile.exists() || tmpFile.length() == 0L) {
                lastDownloadError = "Could not read alternative local file"
                runCatching { tmpFile.delete() }
                return false
            }

            if (!tmpFile.renameTo(outFile)) {
                lastDownloadError = "Couldn't save local alternative file"
                runCatching { tmpFile.delete() }
                return false
            }

            com.music.spotui.data.preferences.addDownload(appContext, song, outFile.absolutePath)
            downloadCoverImage(song.coverUri, java.io.File(dir, "${song.id}_cover.jpg"))
            return true
        }

        val targetQuery = if (alt != null && alt.isYouTube) alt.value else query
        val playback = resolveYtPlayback(targetQuery, dlQuality.audioQuality, appContext) ?: run {
            lastDownloadError = "Couldn't resolve a stream"
            return false
        }

        if (!httpDownloadRanged(playback.streamUrl, tmpFile, song.url)) {
            runCatching { tmpFile.delete() }
            return false
        }
        if (!tmpFile.renameTo(outFile)) {
            lastDownloadError = "Couldn't save file"
            runCatching { tmpFile.delete() }
            return false
        }
        com.music.spotui.data.preferences.addDownload(appContext, song, outFile.absolutePath)
        downloadCoverImage(song.coverUri, java.io.File(dir, "${song.id}_cover.jpg"))
        LyricsApi.removeFromCache(song.title, song.singer)
        val lyricsOk = runCatching {
            LyricsApi.fetch(song.title, song.singer, song.album, song.durationMs / 1000)
        }.getOrNull() != null
        if (!lyricsOk) LyricsApi.removeFromCache(song.title, song.singer)
        return true
    }

    private fun downloadCoverImage(coverUrl: String, destFile: java.io.File) {
        if (coverUrl.isBlank() || destFile.exists()) return
        runCatching {
            val conn = (java.net.URL(coverUrl).openConnection() as java.net.HttpURLConnection).apply {
                connectTimeout = 10000
                readTimeout = 10000
                instanceFollowRedirects = true
            }
            if (conn.responseCode in 200..299) {
                conn.inputStream.use { input ->
                    destFile.outputStream().use { output ->
                        input.copyTo(output)
                    }
                }
            }
        }
    }

    private suspend fun downloadLosslessTrackToFile(
        song: com.music.spotui.data.entity.SongsModel,
        appContext: Context,
    ): Boolean {
        val streamUrl = resolveStreamUrl(song.url, appContext, forPlayback = false)
        if (streamUrl.isNullOrBlank()) return false

        val source = sourceCache[song.url] ?: "Lossless"
        val quality = qualityCache[song.url] ?: "FLAC"

        if (source.contains("YouTube", ignoreCase = true) || streamUrl.contains("googlevideo.com") || streamUrl.contains("youtube.com")) {
            return false
        }

        val dir = java.io.File(appContext.filesDir, "downloads").apply { mkdirs() }
        val ext = when {
            streamUrl.contains(".flac", ignoreCase = true) || quality.contains("FLAC", ignoreCase = true) || source.contains("FLAC", ignoreCase = true) -> "flac"
            streamUrl.contains(".mp3", ignoreCase = true) || quality.contains("MP3", ignoreCase = true) || source.contains("MP3", ignoreCase = true) -> "mp3"
            else -> "flac"
        }
        val outFile = java.io.File(dir, "${song.id}.$ext")
        val tmpFile = java.io.File(dir, "${song.id}.${ext}part")

        val ok = if (streamUrl.contains("manifest") || streamUrl.contains(".mpd")) {
            com.metrolist.spotify.SpotiFlac.downloadDashFlacToFile(streamUrl, tmpFile)
        } else {
            httpDownloadRanged(streamUrl, tmpFile, song.url)
        }
        if (!ok) {
            Log.e(TAG, "Lossless download failed for ${song.title}: $lastDownloadError")
            runCatching { tmpFile.delete() }
            return false
        }
        if (!tmpFile.renameTo(outFile)) {
            runCatching { tmpFile.delete() }
            return false
        }
        com.music.spotui.data.preferences.addDownload(appContext, song, outFile.absolutePath)
        downloadCoverImage(song.coverUri, java.io.File(dir, "${song.id}_cover.jpg"))
        LyricsApi.removeFromCache(song.title, song.singer)
        val lyricsOk = runCatching {
            LyricsApi.fetch(song.title, song.singer, song.album, song.durationMs / 1000)
        }.getOrNull() != null
        if (!lyricsOk) LyricsApi.removeFromCache(song.title, song.singer)
        Log.d(TAG, "Lossless downloaded ($source $quality): ${song.title}")
        return true
    }

    // ── Track-matching helpers ───────────────────────────────────────────────

    private val diacriticsRegex = Regex("""\p{Mn}+""")
    private val bracketRegex = Regex("""\([^)]*\)|\[[^\]]*\]""")
    private val featTailRegex = Regex("""\b(feat|ft|featuring)\b\.?.*""")
    private val nonAlnumRegex = Regex("""[^\p{L}\p{N}]""")
    private val dashSplitRegex = Regex("""\s+[-–—]\s+""")
    private val artistNoiseRegex = Regex("""(?i)\s*-\s*topic$|vevo$|\s+official$""")
    private val albumNoiseRegex = Regex(
        """(?i)\b(deluxe|expanded|anniversary|edition|remaster(ed)?|special|super|bonus|platinum|international|standard|explicit)\b"""
    )
    private val whitespaceRegex = Regex("""\s+""")
    private val coverArtistRegex = Regex(
        """\b(tribute|karaoke|covers?|orchestra|quartet|players|instrumental|lullab\w*|kids|nursery|ensemble|singers|studio band)\b"""
    )
    private val compilationRegex = Regex(
        """\b(greatest hits|best of|the best|hits|collection|essential|anthology|complete|number ones|now that|ultimate|very best|playlist|top \d+|bravo)\b"""
    )

    private val cleanTextCache = java.util.concurrent.ConcurrentHashMap<String, String>()

    private fun cleanTextForMatch(text: String): String {
        cleanTextCache[text]?.let { return it }
        val lower = Normalizer.normalize(text, Normalizer.Form.NFD).replace(diacriticsRegex, "").lowercase()
        val noBrackets = lower.replace(bracketRegex, " ")
        val noFeat = noBrackets.replace(featTailRegex, " ")
        val clean = noFeat.replace(nonAlnumRegex, "")
        val result = clean.ifEmpty { lower.replace(nonAlnumRegex, "") }
        if (cleanTextCache.size > 4096) cleanTextCache.clear()
        cleanTextCache[text] = result
        return result
    }

    private fun artistKey(name: String): String =
        cleanTextForMatch(name.trim().replace(artistNoiseRegex, ""))

    private fun artistComparisonKey(name: String): String =
        artistKey(name).removePrefix("the").ifBlank { artistKey(name) }

    private fun sameArtist(a: String, b: String): Boolean =
        a.isNotBlank() && b.isNotBlank() &&
                (a == b || artistComparisonKey(a) == artistComparisonKey(b))

    private fun albumKey(name: String?): String {
        if (name.isNullOrBlank()) return ""
        val head = name.split(dashSplitRegex, limit = 2)[0].replace(albumNoiseRegex, " ")
        return cleanTextForMatch(head)
    }

    private fun albumSearchText(name: String): String =
        name.replace(bracketRegex, " ")
            .split(dashSplitRegex, limit = 2)[0]
            .replace(albumNoiseRegex, " ")
            .replace(whitespaceRegex, " ")
            .trim()

    private class TitleParts(val baseKey: String, val decorations: String)

    private fun parseTitle(title: String, artistKeys: Collection<String>): TitleParts {
        var current = title
        val first = current.split(dashSplitRegex, limit = 2)
        if (first.size == 2 && artistKeys.any { sameArtist(cleanTextForMatch(first[0]), it) }) {
            current = first[1]
        }

        val decorations = StringBuilder()
        bracketRegex.findAll(current).forEach { decorations.append(' ').append(it.value) }
        val parts = current.replace(bracketRegex, " ").split(dashSplitRegex, limit = 2)
        if (parts.size == 2) decorations.append(' ').append(parts[1])

        val key = cleanTextForMatch(parts[0]).ifEmpty { cleanTextForMatch(title) }
        return TitleParts(key, decorations.toString())
    }

    private val versionPatterns: List<Pair<String, Regex>> = listOf(
        "remix" to Regex("""\b(remix(es|ed)?|rmx|rework(ed)?|bootleg|flip|mashup|refix|vip|club mix|dub mix|extended (mix|version)|megamix|dance mix|re-?edit)\b"""),
        "live" to Regex("""\b(live|en vivo|ao vivo|dal vivo|en directo|in concert|concert|unplugged)\b"""),
        "acoustic" to Regex("""\b(acoustic|acustico|acustica|unplugged|stripped|piano( version)?|orchestral|orchestra|symphonic|string quartet|strings|reimagined|re-imagined|sessions?|reprise)\b"""),
        "sped" to Regex("""\b(sped ?up|speed(ed)? ?up|nightcore|fast version|hyperspeed)\b"""),
        "slowed" to Regex("""\b(slowed( down)?|slow version|reverb|chopped|screwed|daycore)\b"""),
        "fx" to Regex("""\b(8d|16d|bass boost(ed)?|lo-?fi)\b"""),
        "instrumental" to Regex("""\b(instrumental|backing track|no vocals|without vocals|minus one|off vocal|karaoke)\b"""),
        "cover" to Regex("""\b(cover(ed)?|tribute|originally (performed )?by|made famous by|as made famous|in the style of|as performed by|performed by|sing ?along)\b"""),
        "acapella" to Regex("""\b(a ?cappella|acapella|vocals? only)\b"""),
        "demo" to Regex("""\b(demo|rough (mix|cut)|outtake|alternat(e|ive) (version|take|mix|recording)|alt\.? (version|take|mix)|early version|unreleased|rehearsal|work tape|take \d+|first version|home (recording|demo))\b"""),
        "edit" to Regex("""\b(radio edit|single edit|single version|radio version|radio mix|short version|edit version|album edit|tv edit|video edit|video version|edit)\b"""),
        "language" to Regex("""\b((spanish|espanol|portuguese|french|german|italian|japanese|korean|chinese|mandarin|hindi|arabic|russian|turkish|english) (version|ver)|version (en|in) \w+|en espanol|en ingles|in spanish|in english|latin version|spanglish( version)?)\b"""),
        "mono" to Regex("""\bmono\b"""),
    )
    private val albumRelevantFlags = setOf(
        "live", "cover", "acoustic", "remix", "instrumental", "sped", "slowed", "fx", "demo", "acapella",
    )

    private val versionFlagsCache = java.util.concurrent.ConcurrentHashMap<String, Set<String>>()

    private fun versionFlags(text: String): Set<String> {
        if (text.isBlank()) return emptySet()
        versionFlagsCache[text]?.let { return it }
        val t = Normalizer.normalize(text.lowercase(), Normalizer.Form.NFD).replace(diacriticsRegex, "")
        val flags = versionPatterns.filter { it.second.containsMatchIn(t) }.map { it.first }.toSet()
        if (versionFlagsCache.size > 4096) versionFlagsCache.clear()
        versionFlagsCache[text] = flags
        return flags
    }

    private fun albumFlags(album: String?): Set<String> =
        if (album.isNullOrBlank()) emptySet() else versionFlags(album).intersect(albumRelevantFlags)

    private val remasterIdentityRegex = Regex(
        """\b(\d{4}\s*)?remaster(ed)?\b|\b(remaster(ed)?\s*(version|mix))\b""",
        RegexOption.IGNORE_CASE,
    )

    private fun recordingIdentityFlags(text: String): Set<String> {
        if (text.isBlank()) return emptySet()
        val flags = versionFlags(text).toMutableSet()
        if (remasterIdentityRegex.containsMatchIn(text)) flags += "remaster"
        return flags
    }

    private val strongFlags = setOf("live", "cover", "remix", "instrumental", "sped", "slowed", "fx", "acapella")

    private fun isPlainSingleAlbum(album: String?, baseKey: String): Boolean =
        !album.isNullOrBlank() && albumKey(album) == baseKey &&
                !bracketRegex.containsMatchIn(album) && !dashSplitRegex.containsMatchIn(album)

    private val neutralDecorationRegex = Regex(
        """\b(remaster(ed)?|digitally|explicit|clean|official|audio|video|lyrics?|visualizer|hd|hq|version|album|original|deluxe|edition|feat|ft|featuring|with|\d{4})\b"""
    )

    private fun decorationKey(decor: String): String =
        Normalizer.normalize(decor.lowercase(), Normalizer.Form.NFD).replace(diacriticsRegex, "").replace(nonAlnumRegex, "")

    private fun hasUnknownDecoration(decor: String): Boolean {
        if (decor.isBlank()) return false
        val t = Normalizer.normalize(decor.lowercase(), Normalizer.Form.NFD).replace(diacriticsRegex, "")
        return t.replace(neutralDecorationRegex, " ").replace(nonAlnumRegex, "").isNotEmpty()
    }

    private val featMarkerRegex = Regex("""\b(feat|ft|featuring|with)\b""")

    private class MatchCandidate(val item: SongItem, val stage: Int, val score: Int, val index: Int)

    private class VideoSearchPlan(
        val album: String?,
        val artist: String?,
        val title: String,
        val queries: List<String>,
    )

    private const val DURATION_PREFS = "spotui_duration_cache"
    private const val HTTP_UA =
        "Mozilla/5.0 (Linux; Android 14; Pixel 8) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/124.0 Mobile Safari/537.36"

    private val approxDurationQueries: MutableSet<String> = java.util.concurrent.ConcurrentHashMap.newKeySet()

    private class DurationRoute(val name: String, val approx: Boolean, val job: kotlinx.coroutines.Deferred<Int?>)

    private fun plausibleDurationMs(ms: Int): Boolean = ms in 1_000..10_800_000

    private fun cachedDurationMs(spotifyId: String?): Int? {
        if (spotifyId.isNullOrBlank()) return null
        val ctx = appCtx ?: return null
        return runCatching {
            ctx.getSharedPreferences(DURATION_PREFS, Context.MODE_PRIVATE).getInt(spotifyId, 0)
        }.getOrNull()?.takeIf { it > 0 }
    }

    private fun persistDurationMs(spotifyId: String?, ms: Int) {
        if (spotifyId.isNullOrBlank() || !plausibleDurationMs(ms)) return
        val ctx = appCtx ?: return
        runCatching {
            ctx.getSharedPreferences(DURATION_PREFS, Context.MODE_PRIVATE).edit().putInt(spotifyId, ms).apply()
        }
    }

    private fun httpGetText(url: String, timeoutMs: Int = 3000, headers: Map<String, String> = emptyMap()): String? {
        var conn: java.net.HttpURLConnection? = null
        return try {
            conn = (java.net.URL(url).openConnection() as java.net.HttpURLConnection).apply {
                connectTimeout = timeoutMs
                readTimeout = timeoutMs
                instanceFollowRedirects = true
                setRequestProperty("User-Agent", HTTP_UA)
                setRequestProperty("Accept-Language", "en")
                headers.forEach { (k, v) -> setRequestProperty(k, v) }
            }
            if (conn.responseCode !in 200..299) null
            else conn.inputStream.bufferedReader().use { it.readText() }
        } catch (e: Exception) {
            Log.w(TAG, "duration route: GET failed for $url (${e.javaClass.simpleName}: ${e.message})")
            null
        } finally {
            runCatching { conn?.disconnect() }
        }
    }

    private fun durationFromSpotifyEmbed(id: String): Int? {
        val html = httpGetText("https://open.spotify.com/embed/track/$id") ?: return null
        val m = Regex("\"duration\"\\s*:\\s*(\\d{4,8})").find(html) ?: return null
        return m.groupValues[1].toIntOrNull()?.takeIf { plausibleDurationMs(it) }
    }

    private fun durationFromSpotifyPage(id: String): Int? {
        val html = httpGetText("https://open.spotify.com/track/$id") ?: return null
        val a = Regex("<meta[^>]+property=[\"']music:duration[\"'][^>]*content=[\"'](\\d+)[\"']", RegexOption.IGNORE_CASE)
        val b = Regex("<meta[^>]+content=[\"'](\\d+)[\"'][^>]*property=[\"']music:duration[\"']", RegexOption.IGNORE_CASE)
        val sec = (a.find(html) ?: b.find(html))?.groupValues?.get(1)?.toIntOrNull() ?: return null
        return (sec * 1000).takeIf { plausibleDurationMs(it) }
    }

    private fun durationFromDeezerIsrc(isrc: String): Int? {
        val body = httpGetText("https://api.deezer.com/track/isrc:$isrc") ?: return null
        val json = org.json.JSONObject(body)
        if (json.has("error")) return null
        return (json.optInt("duration", 0) * 1000).takeIf { plausibleDurationMs(it) }
    }

    private fun durationFromMusicBrainz(isrc: String): Int? {
        val body = httpGetText(
            "https://musicbrainz.org/ws/2/recording?query=isrc:$isrc&fmt=json&limit=15",
            headers = mapOf("User-Agent" to "SpotUI/1.0 (android)", "Accept" to "application/json"),
        ) ?: return null
        val arr = org.json.JSONObject(body).optJSONArray("recordings") ?: return null
        val lengths = ArrayList<Int>()
        for (i in 0 until arr.length()) {
            val len = arr.optJSONObject(i)?.optInt("length", 0) ?: 0
            if (plausibleDurationMs(len)) lengths += len
        }
        if (lengths.isEmpty()) return null
        val bySecond = lengths.groupBy { it / 1000 }
        val bestCount = bySecond.values.maxOf { it.size }
        val top = bySecond.values.filter { it.size == bestCount }.map { it.sorted()[it.size / 2] }.sorted()
        return top[top.size / 2]
    }

    private fun durationFromDeezerSearch(title: String, artist: String, album: String?): Int? {
        val primaryArtist = artist.split(",", "&").first().trim()
        if (primaryArtist.isEmpty()) return null
        val q = "artist:\"$primaryArtist\" track:\"${cleanSpotifySearchTitle(title)}\""
        val body = httpGetText(
            "https://api.deezer.com/search?limit=15&q=" + java.net.URLEncoder.encode(q, "UTF-8")
        ) ?: return null
        val arr = org.json.JSONObject(body).optJSONArray("data") ?: return null
        val wantArtistKey = artistKey(primaryArtist)
        val artistKeys = listOf(wantArtistKey)
        val wantTitle = parseTitle(title, artistKeys)
        val wantFlags = recordingIdentityFlags(wantTitle.decorations)
        val wantAlbumKey = albumKey(album)
        val matches = ArrayList<Pair<Int, Boolean>>()
        for (i in 0 until arr.length()) {
            val o = arr.optJSONObject(i) ?: continue
            val candArtist = o.optJSONObject("artist")?.optString("name").orEmpty()
            if (!sameArtist(artistKey(candArtist), wantArtistKey)) continue
            val candTitle = parseTitle(o.optString("title"), artistKeys)
            if (candTitle.baseKey != wantTitle.baseKey) continue
            if (recordingIdentityFlags(candTitle.decorations) != wantFlags) continue
            val ms = o.optInt("duration", 0) * 1000
            if (!plausibleDurationMs(ms)) continue
            val candAlbumKey = albumKey(o.optJSONObject("album")?.optString("title"))
            matches += ms to (wantAlbumKey.isNotEmpty() && candAlbumKey == wantAlbumKey)
        }
        matches.firstOrNull { it.second }?.let { return it.first }
        val ds = matches.map { it.first }
        val lo = ds.minOrNull() ?: return null
        val hi = ds.maxOrNull() ?: return null
        return if (hi - lo <= 2000) ds.first() else null
    }

    private suspend fun resolveDurationFallbacks(
        query: String,
        spotifyId: String?,
        isrcHint: String?,
        title: String?,
        artist: String?,
        album: String?,
    ): Int? {
        durationRegistry[query]?.takeIf { it > 0 }?.let { return it }

        boundState?.queue?.value?.firstOrNull { it.url == query }?.durationMs?.toLong()?.takeIf { it > 0 }
            ?.let { ms ->
                durationRegistry[query] = ms.toInt()
                Log.w(TAG, "duration for '$query': ${ms}ms via queue item")
                return ms.toInt()
            }

        cachedDurationMs(spotifyId)?.let { ms ->
            durationRegistry[query] = ms
            Log.w(TAG, "duration for '$query': ${ms}ms via persistent cache")
            return ms
        }

        val isrc = isrcHint?.takeIf { it.isNotBlank() } ?: spotifyId?.let { isrcRegistry[it] }
        fun route(name: String, approx: Boolean, block: () -> Int?) =
            DurationRoute(name, approx, scope.async(Dispatchers.IO) { runCatching { block() }.getOrNull() })

        val routes = ArrayList<DurationRoute>()
        if (!spotifyId.isNullOrBlank()) {
            routes += route("Spotify embed", false) { durationFromSpotifyEmbed(spotifyId) }
            routes += route("Spotify page", false) { durationFromSpotifyPage(spotifyId) }
        }
        if (!isrc.isNullOrBlank()) {
            routes += route("Deezer ISRC", false) { durationFromDeezerIsrc(isrc) }
            routes += route("MusicBrainz ISRC", true) { durationFromMusicBrainz(isrc) }
        }
        if (!title.isNullOrBlank() && !artist.isNullOrBlank()) {
            routes += route("Deezer search", true) { durationFromDeezerSearch(title, artist, album) }
        }
        if (routes.isEmpty()) {
            Log.w(TAG, "duration for '$query': no fallback route available")
            return null
        }

        val deadline = System.currentTimeMillis() + 6000L
        var found: Pair<DurationRoute, Int>? = null
        for (r in routes) {
            val remaining = (deadline - System.currentTimeMillis()).coerceAtLeast(1L)
            val ms = withTimeoutOrNull(remaining) { r.job.await() }
            Log.d(TAG, "duration route '${r.name}' -> ${ms ?: "none"}")
            if (ms != null && plausibleDurationMs(ms)) { found = r to ms; break }
        }
        routes.forEach { it.job.cancel() }

        val (chosen, ms) = found ?: run {
            Log.w(TAG, "duration for '$query': all fallback routes failed")
            return null
        }
        durationRegistry[query] = ms
        if (chosen.approx) approxDurationQueries.add(query) else approxDurationQueries.remove(query)
        if (!chosen.approx) persistDurationMs(spotifyId, ms)
        Log.w(TAG, "duration for '$query': ${ms}ms via ${chosen.name}${if (chosen.approx) " (approximate)" else ""}")
        return ms
    }

    private suspend fun ensureSpotifyMatchMetadata(query: String): TrackMatchMetadata? {
        val currentMeta = metadataRegistry[query]
        val hasUsefulMeta = currentMeta?.let {
            it.title.isNotBlank() && it.artist.isNotBlank() && it.album.isNotBlank()
        } ?: false
        val currentStrongFlags = currentMeta?.let {
            (versionFlags(it.title) + versionFlags(it.album)).intersect(strongFlags)
        }.orEmpty()
        if (hasUsefulMeta && durationRegistry[query] != null &&
            explicitRegistry.containsKey(query) && currentStrongFlags.isEmpty()) {
            return currentMeta
        }

        val spotifyId = trackIdRegistry[query] ?: spotifyTrackIdForPlayback(query)
        if (spotifyId == null) {
            resolveDurationFallbacks(query, null, null, currentMeta?.title, currentMeta?.artist, currentMeta?.album)
            return currentMeta
        }
        val track = (0 until 3).firstNotNullOfOrNull { attempt ->
            if (attempt > 0) delay(250L * attempt)
            runCatching {
                withTimeoutOrNull(5000L) { com.metrolist.spotify.Spotify.track(spotifyId).getOrNull() }
            }
                .onFailure { Log.w(TAG, "Spotify metadata repair failed for $spotifyId (attempt ${attempt + 1})", it) }
                .getOrNull()
        }
        if (track == null) {
            Log.w(TAG, "Spotify metadata unavailable for $spotifyId: trying duration fallbacks")
            resolveDurationFallbacks(query, spotifyId, null, currentMeta?.title, currentMeta?.artist, currentMeta?.album)
            return currentMeta
        }

        val repaired = TrackMatchMetadata(
            title = track.name,
            artist = track.artists.joinToString(", ") { it.name },
            album = track.album?.name ?: currentMeta?.album.orEmpty(),
        )
        metadataRegistry[query] = repaired
        trackIdRegistry[query] = spotifyId
        explicitRegistry[query] = track.explicit
        if (track.durationMs > 0) {
            durationRegistry[query] = track.durationMs
            approxDurationQueries.remove(query)
            persistDurationMs(spotifyId, track.durationMs)
        }
        track.isrc?.takeIf { it.isNotBlank() }?.let { isrcRegistry[spotifyId] = it }
        if (durationRegistry[query] == null) {
            Log.w(TAG, "Spotify returned no duration for $spotifyId: trying duration fallbacks")
            resolveDurationFallbacks(query, spotifyId, track.isrc, track.name, repaired.artist, repaired.album)
        }
        return repaired
    }

    private suspend fun resolveVideoCandidates(
        query: String,
        filter: YouTube.SearchFilter = YouTube.SearchFilter.FILTER_SONG,
        forPlayback: Boolean = false,
    ): List<String> {
        val cacheKey = "$query|${filter.value}|MATCH_V12"
        videoCandidatesCache[cacheKey]?.let { return it }
        appCtx?.let { ctx ->
            com.music.spotui.data.preferences.getCachedVideoIds(ctx, cacheKey)?.let { cached ->
                videoCandidatesCache[cacheKey] = cached
                return cached
            }
        }
        if (forPlayback) {
            updateResolveStatus(true, "Searching YouTube videos...")
        }

        val wantExplicit = explicitRegistry[query]
        val baseSearchText = searchTextForPlayback(query)
        val searchText = if (wantExplicit == true) "$baseSearchText explicit" else baseSearchText

        if (searchText.length == 11 && !searchText.contains(' ')) return listOf(searchText)

        // Same targets / queries as before, derived from whatever metadata we have at this point.
        fun buildPlan(exactMeta: TrackMatchMetadata?): VideoSearchPlan {
            val registeredMeta = metadataRegistry[query]
            val queuedSong = boundState?.queue?.value?.firstOrNull { it.url == query }
            val album: String? = exactMeta?.album?.ifBlank { null }
                ?: registeredMeta?.album?.ifBlank { null }
                ?: queuedSong?.album?.ifBlank { null }
            val artist: String? = exactMeta?.artist?.ifBlank { null }
                ?: registeredMeta?.artist?.ifBlank { null }
                ?: queuedSong?.singer?.ifBlank { null }
                ?: if (forPlayback) metaArtist.ifBlank { null } else baseSearchText
            val title: String = exactMeta?.title?.ifBlank { null }
                ?: registeredMeta?.title?.ifBlank { null }
                ?: queuedSong?.title?.ifBlank { null }
                ?: cleanTrackTitle(query, artist ?: "")

            val artistForSearch = artist.orEmpty().trim()
            val titleForSearch = cleanSpotifySearchTitle(title).trim()
            val albumForSearch = album?.let { albumSearchText(it) }.orEmpty()
            val queries = linkedSetOf<String>().apply {
                add(searchText)
                if (titleForSearch.isNotBlank() && artistForSearch.isNotBlank()) {
                    add("$titleForSearch $artistForSearch")
                    if (albumForSearch.isNotBlank() && !albumForSearch.equals(titleForSearch, ignoreCase = true)) {
                        add("$titleForSearch $artistForSearch $albumForSearch")
                    }
                    add("$artistForSearch $titleForSearch")
                    add("$titleForSearch $artistForSearch official audio")
                }
            }.filter { it.isNotBlank() && !(it.length == 11 && !it.contains(' ')) }
            return VideoSearchPlan(album, artist, title, queries)
        }

        val (_, plan, hits) = kotlinx.coroutines.supervisorScope {
            fun launchSearch(q: String) = async {
                YouTube.search(q, filter)
                    .onFailure {
                        Log.w(TAG, "resolveVideoId: YouTube search failed for: $q", it)
                    }
                    .getOrNull()
                    ?.items
                    ?.filterIsInstance<SongItem>()
                    ?: emptyList()
            }

            val searches = HashMap<String, kotlinx.coroutines.Deferred<List<SongItem>>>()
            val metadataDeferred = async {
                runCatching { ensureSpotifyMatchMetadata(query) }.getOrNull()
            }
            searches[searchText] = launchSearch(searchText)
            // Speculative start: fire the secondary searches from the metadata we already have
            // while Spotify metadata is still being fetched. They are only reused if the final
            // plan asks for the very same query strings; otherwise they are cancelled.
            buildPlan(null).queries.forEach { q -> if (q !in searches) searches[q] = launchSearch(q) }

            val exactMeta = metadataDeferred.await()
            val finalPlan = buildPlan(exactMeta)
            finalPlan.queries.forEach { q -> if (q !in searches) searches[q] = launchSearch(q) }
            searches.entries.filter { it.key !in finalPlan.queries }.forEach { it.value.cancel() }

            val allHits = finalPlan.queries
                .flatMap { q -> searches.getValue(q).await() }
                .distinctBy { it.id }
            Triple(exactMeta, finalPlan, allHits)
        }
        val searchQueries = plan.queries
        val targetAlbum: String? = plan.album
        val targetArtist: String? = plan.artist
        val targetTitle: String = plan.title
        val queuedSong = boundState?.queue?.value?.firstOrNull { it.url == query }
        val wantSec: Int? = durationRegistry[query]?.let { it / 1000 }
            ?: queuedSong?.durationMs?.toLong()?.takeIf { it > 0 }?.let { (it / 1000).toInt() }
        Log.w(TAG, "MATCH '$query': ${hits.size} raw hits from ${searchQueries.size} parallel queries")

        if (hits.isEmpty()) {
            Log.w(TAG, "resolveVideoId: no YouTube song results for queries: $searchQueries")
            return emptyList()
        }

        val expectedArtistsList = (targetArtist ?: "").split(artistSplitRegex)
            .map { artistKey(it) }
            .filter { it.isNotEmpty() }
        val fullArtistKey = artistKey(targetArtist ?: "")
        val primaryArtistKey = expectedArtistsList.firstOrNull() ?: artistKey(baseSearchText)
        val targetArtistKeys = (listOf(primaryArtistKey, fullArtistKey) + expectedArtistsList)
            .filter { it.isNotEmpty() }
            .toSet()

        val target = parseTitle(targetTitle, targetArtistKeys)
        val targetAlbumKey = albumKey(targetAlbum)
        val targetTitleFlags = versionFlags(target.decorations)
        val targetRecordingFlags = recordingIdentityFlags(target.decorations)
        val targetAlbumFlags = if (isPlainSingleAlbum(targetAlbum, target.baseKey)) emptySet() else albumFlags(targetAlbum)
        val expectedFlags = targetTitleFlags
        val targetIsCompilation = targetAlbum?.let { compilationRegex.containsMatchIn(it.lowercase()) } ?: false

        val isSongFilter = filter == YouTube.SearchFilter.FILTER_SONG
        val tightTol = if (isSongFilter) maxOf(5, (wantSec ?: 0) / 40) else maxOf(7, (wantSec ?: 0) / 35)
        val wideTol = maxOf(8, (wantSec ?: 0) / 20)

        if (forPlayback && wantSec == null) logResolution("⚠ Spotify duration unavailable: matching by album/title only")
        Log.d(TAG, "==================================================")
        Log.d(TAG, "▶ MATCHING '$query' [filter=${filter.value}]")
        Log.d(TAG, "▶ TARGET: title='${target.baseKey}' flags=$expectedFlags | artist='$primaryArtistKey' | album='$targetAlbumKey'")
        Log.d(TAG, "▶ TARGET: duration=${wantSec}s (tight ±${tightTol}s, wide ±${wideTol}s) | explicit=$wantExplicit")
        Log.d(TAG, "--------------------------------------------------")

        val scored = ArrayList<MatchCandidate>()
        hits.forEachIndexed { index, item ->
            val cand = parseTitle(item.title, targetArtistKeys)
            val candArtistKeys = item.artists.map { artistKey(it.name) }.filter { it.isNotEmpty() }
            val titleArtistKey = artistKey(item.title)
            val artistsCreditedInTitle = expectedArtistsList.isNotEmpty() &&
                    expectedArtistsList.all { expected ->
                        expected.length >= 4 && titleArtistKey.contains(expected)
                    }
            val allExpectedArtistsPresent = expectedArtistsList.size <= 1 || artistsCreditedInTitle ||
                    expectedArtistsList.all { expected ->
                        candArtistKeys.any { candidate ->
                            sameArtist(candidate, expected)
                        }
                    }

            val artistExact = allExpectedArtistsPresent && (
                    candArtistKeys.any { sameArtist(it, primaryArtistKey) || sameArtist(it, fullArtistKey) } ||
                            (fullArtistKey.isNotEmpty() && sameArtist(candArtistKeys.joinToString(""), fullArtistKey)) ||
                            artistsCreditedInTitle
                    )
            val artistPartial = !artistExact && allExpectedArtistsPresent && item.artists.any { a ->
                val k = artistKey(a.name)
                primaryArtistKey.length >= 4 && k.length >= 4 &&
                        (k.contains(primaryArtistKey) || primaryArtistKey.contains(k)) &&
                        !coverArtistRegex.containsMatchIn(a.name.lowercase())
            }

            val candExtraArtists = if (artistsCreditedInTitle) emptyList() else candArtistKeys.filter { k ->
                k !in targetArtistKeys && !targetArtistKeys.any { t ->
                    t.length >= 4 && k.length >= 4 && (k.contains(t) || t.contains(k))
                }
            }
            val candHasFeat = featMarkerRegex.containsMatchIn(cand.decorations.lowercase())
            val targetHasFeat = featMarkerRegex.containsMatchIn(target.decorations.lowercase())
            val featEq = candExtraArtists.isEmpty() &&
                    (!candHasFeat || targetHasFeat || expectedArtistsList.size >= 2)
            val artistOk = artistExact && featEq
            val targetCoreKey = target.baseKey.replace(remixSuffixRegex, "")
            val candidateCoreKey = cand.baseKey.replace(remixSuffixRegex, "")
            val titleEq = cand.baseKey.isNotEmpty() && (
                    cand.baseKey == target.baseKey ||
                            (candidateCoreKey.length >= 10 && targetCoreKey.length >= 10 &&
                                    (targetCoreKey.startsWith(candidateCoreKey) || candidateCoreKey.startsWith(targetCoreKey)))
                    )
            val candTitleFlags = versionFlags(cand.decorations)
            val candRecordingFlags = recordingIdentityFlags(cand.decorations)
            val candAlbumName = item.album?.name
            val candAlbumFlags = if (isPlainSingleAlbum(candAlbumName, cand.baseKey)) emptySet() else albumFlags(candAlbumName)
            val candFlags = candTitleFlags
            val targetIsRadioEdit = radioEditRegex.containsMatchIn(target.decorations)
            val candidateIsRadioEdit = radioEditRegex.containsMatchIn(cand.decorations)
            val editSubtypeEq = !targetIsRadioEdit || candidateIsRadioEdit
            val titleFlagsEq = candTitleFlags - "language" == targetTitleFlags - "language" && editSubtypeEq
            val recordingFlagsEq =
                candRecordingFlags - "language" == targetRecordingFlags - "language" && editSubtypeEq
            val allFlagsEq = candFlags - "language" == expectedFlags - "language" && editSubtypeEq
            val strongAlbumEq = (candAlbumFlags intersect strongFlags) == (targetAlbumFlags intersect strongFlags)

            val itemDur = item.duration
            val durDiff: Int? = if (wantSec != null && itemDur != null) abs(wantSec - itemDur) else null
            val tightOk = durDiff == null || durDiff <= tightTol
            val wideOk = durDiff == null || durDiff <= wideTol
            val explicitOk = true
            val explicitMatch = wantExplicit == null || item.explicit == wantExplicit
            val lastResortOk = durDiff == null || durDiff <= maxOf(15, (wantSec ?: 0) / 10)

            val candAlbumKey = albumKey(candAlbumName)
            val albumEq = targetAlbumKey.isNotEmpty() && candAlbumKey == targetAlbumKey

            val candR = candRecordingFlags - "language"
            val targetR = targetRecordingFlags - "language"
            val onlyEditDiff = "edit" !in targetR && (candR - targetR) == setOf("edit") && (targetR - candR).isEmpty()
            val editBase = artistOk && titleEq && !recordingFlagsEq && onlyEditDiff && explicitOk
            val albumAnchored = editBase && albumEq && (durDiff == null || durDiff <= tightTol)
            val durationAnchored = editBase && durDiff != null && durDiff <= 3
            val editAnchored = albumAnchored || durationAnchored

            val stage = when {
                albumAnchored -> 1
                artistOk && titleEq && recordingFlagsEq && explicitOk && tightOk -> 1
                durationAnchored -> 2
                artistOk && titleEq && recordingFlagsEq && strongAlbumEq && explicitOk && tightOk -> 2
                artistOk && titleEq && recordingFlagsEq && explicitOk && albumEq && wideOk -> 3
                artistPartial && titleEq && recordingFlagsEq && explicitOk &&
                        wantSec != null && durDiff != null && durDiff <= 3 -> 4
                artistOk && titleEq && recordingFlagsEq && strongAlbumEq && explicitOk && lastResortOk -> 5
                artistOk && titleEq && recordingFlagsEq && strongAlbumEq && lastResortOk -> 6
                else -> 99
            }

            var score = if (artistExact) 30 else 10
            if (durDiff != null) {
                score += when {
                    durDiff <= 1 -> 30
                    durDiff <= 2 -> 26
                    durDiff <= 3 -> 20
                    durDiff <= 5 -> 10
                    else -> 2
                }
            }
            if (durDiff != null) {
                if (albumEq) {
                    score += when {
                        durDiff <= 1 -> 6
                        durDiff <= 3 -> 6
                        durDiff <= 5 -> 4
                        else -> 0
                    }
                } else if (targetAlbumKey.length >= 4 && candAlbumKey.length >= 4 &&
                    (candAlbumKey.contains(targetAlbumKey) || targetAlbumKey.contains(candAlbumKey))
                ) {
                    score += 3
                } else if (candAlbumKey.isNotEmpty() && !targetIsCompilation &&
                    compilationRegex.containsMatchIn((candAlbumName ?: "").lowercase())
                ) {
                    score -= 8
                }
            } else if (candAlbumKey.isNotEmpty() && !targetIsCompilation &&
                compilationRegex.containsMatchIn((candAlbumName ?: "").lowercase())
            ) {
                score -= 8
            }
            if (durDiff == null && albumEq) {
                if (albumAnchored) score += 35
                else if (recordingFlagsEq) score += 15
            }
            if (targetTitleFlags.isEmpty() && cand.decorations.isBlank()) score += 3
            val isLyricsUpload = lyricsUploadRegex.containsMatchIn(item.title)
            if (isLyricsUpload) score -= 15
            if (!editAnchored && hasUnknownDecoration(cand.decorations) && decorationKey(cand.decorations) != decorationKey(target.decorations)) score -= 40
            score += if (explicitMatch) 8 else -8

            Log.d(TAG, "  -> '${item.title}' by ${item.artists.joinToString { it.name }} | album='${candAlbumName ?: ""}' | ${itemDur}s | explicit=${item.explicit}")
            Log.d(TAG, "     artist=${if (artistExact) "exact" else if (artistPartial) "partial" else "no"} extraArtists=$candExtraArtists feat=$candHasFeat titleEq=$titleEq flags=$candFlags recordingFlags=$candRecordingFlags recordingEq=$recordingFlagsEq durDiff=$durDiff albumEq=$albumEq -> stage=$stage score=$score")
            if (stage < 99) scored.add(MatchCandidate(item, stage, score, index))
        }

        Log.d(TAG, "--------------------------------------------------")

        val bestStage = scored.minOfOrNull { it.stage }
        if (bestStage != null) {
            val chosen = scored.filter { it.stage == bestStage }
                .sortedWith(compareByDescending<MatchCandidate> { it.score }.thenBy { it.index })
            val best = chosen.first()
            if (forPlayback) logResolution("YouTube match: '${best.item.title}' | ${best.item.artists.joinToString { it.name }} | album='${best.item.album?.name ?: ""}' | ${best.item.duration}s vs ${wantSec}s | stage $bestStage")
            Log.w(TAG, "✅ WINNER: '${best.item.title}' (stage $bestStage, score ${best.score}, ID: ${best.item.id})")
            Log.d(TAG, "==================================================")

            val resolvedIds = chosen.map { it.item.id }.distinct()
            videoCandidatesCache[cacheKey] = resolvedIds
            appCtx?.let { ctx ->
                com.music.spotui.data.preferences.setCachedVideoIds(ctx, cacheKey, resolvedIds)
            }
            return resolvedIds
        }

        if (hits.isNotEmpty()) {
            val fallbackBest = hits.first()
            if (forPlayback) logResolution("YouTube last-resort match: '${fallbackBest.title}' | ${fallbackBest.artists.joinToString { it.name }}")
            Log.w(TAG, "⚠️ LAST RESORT WINNER: '${fallbackBest.title}' (ID: ${fallbackBest.id})")
            val resolvedIds = listOf(fallbackBest.id)
            videoCandidatesCache[cacheKey] = resolvedIds
            appCtx?.let { ctx ->
                com.music.spotui.data.preferences.setCachedVideoIds(ctx, cacheKey, resolvedIds)
            }
            return resolvedIds
        }

        if (forPlayback) logResolution("YouTube: no candidate matched (${hits.size} hits, want ${wantSec}s, album '${targetAlbum ?: ""}')")
        Log.w(TAG, "❌ NO SUITABLE CANDIDATES for '$query' (see per-candidate lines above)")
        Log.d(TAG, "==================================================")
        return emptyList()
    }

    private suspend fun resolveYtPlayback(
        query: String,
        audioQuality: com.metrolist.music.constants.AudioQuality,
        appContext: Context,
        forPlayback: Boolean = false,
    ): YTPlayerUtils.PlaybackData? {
        lastYtFailureReason = null
        val connectivityManager =
            appContext.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
        val cacheKey = "$query|${com.metrolist.innertube.YouTube.SearchFilter.FILTER_SONG.value}|MATCH_V12"
        val candidatesCached = videoCandidatesCache.containsKey(cacheKey)
        val tried = mutableSetOf<String>()
        val streamResolutionSemaphore = Semaphore(3)
        suspend fun tryIds(ids: List<String>, skipValidation: Boolean = false): YTPlayerUtils.PlaybackData? {
            val uniqueIds = ids.filter { it.isNotBlank() }.distinct().filter { tried.add(it) }
            if (uniqueIds.isEmpty()) return null
            val attempts = uniqueIds.mapIndexed { index, videoId ->
                scope.async {
                    streamResolutionSemaphore.withPermit {
                        if (forPlayback) {
                            updateResolveStatus(true, "Resolving YouTube stream ${index + 1}/${uniqueIds.size}...")
                        }
                        try {
                            YTPlayerUtils.playerResponseForPlayback(
                                videoId = videoId,
                                audioQuality = audioQuality,
                                connectivityManager = connectivityManager,
                                skipValidation = skipValidation,
                            ).onFailure { error ->
                                lastYtFailureReason = error.message ?: "Stream failed"
                                Log.w(TAG, "stream failed for $videoId (${error.message}) — trying next candidate for: ${searchTextForPlayback(query)}")
                            }.getOrNull()
                        } catch (cancelled: kotlinx.coroutines.CancellationException) {
                            throw cancelled
                        } catch (error: Exception) {
                            lastYtFailureReason = error.message ?: "Stream failed"
                            Log.w(TAG, "stream failed for $videoId (${error.message}) — trying next candidate for: ${searchTextForPlayback(query)}", error)
                            null
                        }
                    }
                }
            }
            // All candidates resolve in parallel, but the winner is still the first one (in match
            // order) that succeeds; as soon as it does, the slower/lower-ranked ones are dropped.
            try {
                for (attempt in attempts) {
                    attempt.await()?.let { return it }
                }
                return null
            } finally {
                attempts.forEach { it.cancel() }
            }
        }
        tryIds(resolveVideoCandidates(query, forPlayback = forPlayback).take(3), skipValidation = false)?.let { return it }
        if (!com.music.spotui.data.preferences.isVideoFallbackEnabled(appContext)) {
            Log.w(TAG, "song candidates exhausted and video fallback disabled for: ${searchTextForPlayback(query)}")
            return null
        }
        Log.w(TAG, "song candidates exhausted, trying video search for: ${searchTextForPlayback(query)}")
        tryIds(resolveVideoCandidates(query, YouTube.SearchFilter.FILTER_VIDEO, forPlayback = forPlayback).take(3), skipValidation = false)?.let { return it }
        Log.e(TAG, "All YouTube candidates failed for: ${searchTextForPlayback(query)}")
        return null
    }

    private fun buildAudioAttributes() =
        androidx.media3.common.AudioAttributes.Builder()
            .setContentType(androidx.media3.common.C.AUDIO_CONTENT_TYPE_MUSIC)
            .setUsage(androidx.media3.common.C.USAGE_MEDIA)
            .build()

    @androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)
    private data class PlayerAudioComponents(
        val player: ExoPlayer,
        val filter: com.music.spotui.audio.CrossfadeFilterAudioProcessor,
        val equalizer: com.music.spotui.audio.EqualizerAudioProcessor,
        val normalizer: com.music.spotui.audio.VolumeNormalizationAudioProcessor,
    )

    @androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)
    private fun createPlayerWithFilter(
        context: Context,
        handleAudioFocus: Boolean,
    ): PlayerAudioComponents {
        val filter = com.music.spotui.audio.CrossfadeFilterAudioProcessor()
        val equalizer = com.music.spotui.audio.EqualizerAudioProcessor().apply {
            enabled = com.music.spotui.data.preferences.isEqualizerEnabled(context)
            setBandGains(com.music.spotui.data.preferences.getEqualizerBandGains(context))
        }
        val normalizer = com.music.spotui.audio.VolumeNormalizationAudioProcessor().apply {
            enabled = com.music.spotui.data.preferences.isAudioNormalizationEnabled(context)
        }
        val renderers = object : androidx.media3.exoplayer.DefaultRenderersFactory(context) {
            override fun buildAudioSink(
                context: Context,
                enableFloatOutput: Boolean,
                enableAudioTrackPlaybackParams: Boolean,
            ): androidx.media3.exoplayer.audio.AudioSink =
                androidx.media3.exoplayer.audio.DefaultAudioSink.Builder(context)
                    .setEnableFloatOutput(enableFloatOutput)
                    .setEnableAudioTrackPlaybackParams(enableAudioTrackPlaybackParams)
                    .setAudioProcessorChain(
                        androidx.media3.exoplayer.audio.DefaultAudioSink.DefaultAudioProcessorChain(
                            filter, equalizer, normalizer,
                        ),
                    ).build()
        }
        val loadControl = androidx.media3.exoplayer.DefaultLoadControl.Builder()
            .setBufferDurationsMs(
                15_000,
                50_000,
                1_500,
                3_000
            )
            .build()

        val p = ExoPlayer.Builder(context)
            .setMediaSourceFactory(
                androidx.media3.exoplayer.source.DefaultMediaSourceFactory(
                    com.music.spotui.deezer.DeezerAwareDataSourceFactory(createResilientDataSourceFactory(context)),
                    androidx.media3.extractor.DefaultExtractorsFactory().setConstantBitrateSeekingEnabled(true)
                ),
            )
            .setLoadControl(loadControl)
            .setRenderersFactory(renderers)
            .setAudioAttributes(buildAudioAttributes(), handleAudioFocus)
            .setHandleAudioBecomingNoisy(handleAudioFocus)
            .setWakeMode(androidx.media3.common.C.WAKE_MODE_NETWORK)
            .build()
        return PlayerAudioComponents(p, filter, equalizer, normalizer)
    }

    private fun ensurePlayer(context: Context) {
        appCtx = context.applicationContext
        if (player == null) {
            val comp = createPlayerWithFilter(context, handleAudioFocus = true)
            player = comp.player
            currentPlayerFilter = comp.filter
            currentPlayerEqualizer = comp.equalizer
            currentPlayerNormalizer = comp.normalizer
            onPlayerCreated?.invoke(comp.player)
        }
    }

    val exoPlayer: ExoPlayer? get() = player

    fun setPreferredAudioDevice(deviceInfo: android.media.AudioDeviceInfo?) {
        try {
            player?.setPreferredAudioDevice(deviceInfo)
            secondaryPlayer?.setPreferredAudioDevice(deviceInfo)
        } catch (e: Exception) {
        }
    }

    fun ensureCreated(context: Context) = ensurePlayer(context.applicationContext)

    /**
     * Optional: call once at app start. Opens the disk media cache (SQLite + folder scan) and
     * builds the ExoPlayer ahead of the first play so that work is not on the first song's path.
     */
    fun warmUp(context: Context) {
        val appContext = context.applicationContext
        appCtx = appContext
        scope.launch {
            runCatching { mediaCache(appContext) }
            runCatching { purgeLegacyMatchCachesOnce(appContext) }
            withContext(Dispatchers.Main) { runCatching { ensurePlayer(appContext) } }
        }
    }

    @Volatile var onPlayerCreated: ((ExoPlayer) -> Unit)? = null

    fun isPlaying(): Boolean {
        if (webPlaybackActive()) return SpotifyWebPlayer.isPlaying
        return player?.isPlaying ?: false
    }

    fun webPlaybackActive(): Boolean {
        if (!webPlayerEnabled) return false
        val ctx = appCtx ?: return false
        return com.music.spotui.data.preferences.isWebPlaybackEnabled(ctx) &&
                SpotifyWebPlayer.canPlay &&
                com.music.spotui.data.api.SpotifySession.spDc(ctx).isNotBlank()
    }

    @Volatile private var restoreQuery: String? = null
    @Volatile private var restorePositionMs: Long = 0L

    fun setRestorePoint(query: String, positionMs: Long) {
        if (query.isBlank()) return
        restoreQuery = query
        restorePositionMs = positionMs.coerceAtLeast(0L)
        loadedQuery = query
    }

    fun play() {
        if (webPlaybackActive()) { SpotifyWebPlayer.resume(); return }
        playWhenResolved = true
        if (currentRequest.isNotBlank() && currentRequest != loadedQuery) {
            return
        }
        if ((player?.mediaItemCount ?: 0) == 0) {
            val q = restoreQuery
            val ctx = appCtx
            if (q != null && ctx != null) { playSong(q, ctx); return }
        }
        player?.play()
    }

    fun pause() {
        cancelCrossfade()
        playWhenResolved = false
        releaseWakeLock("spotui:pause")
        if (webPlaybackActive()) { SpotifyWebPlayer.pause(); return }
        player?.let {
            it.playWhenReady = false
            appCtx?.let { ctx ->
                val pos = it.currentPosition
                if (pos > 0) com.music.spotui.data.preferences.saveLastPosition(ctx, pos)
            }
        }
    }

    fun stop() {
        cancelCrossfade()
        releaseWakeLock("spotui:stop")
        player?.stop()
        loadedQuery = null
        currentRequest = ""
    }

    fun togglePlay() {
        if (webPlaybackActive()) {
            if (SpotifyWebPlayer.isPlaying) pause() else play()
            return
        }
        player?.let {
            if (it.playWhenReady) pause() else play()
        }
    }

    fun next(context: Context) {
        acquireWakeLock(context, "spotui:next", 60_000L)
        val state = boundState
        if (state == null) {
            android.util.Log.w(TAG, "next: boundState is null")
            releaseWakeLock("spotui:next")
            return
        }
        val q = state.queue.value
        if (q.isEmpty()) {
            android.util.Log.w(TAG, "next: queue is empty")
            releaseWakeLock("spotui:next")
            return
        }
        val curId = state.songId.value
        val cur = q.indexOfFirst { it.id == curId }
            .takeIf { it >= 0 }
            ?: q.indexOfFirst { it.url == state.songUrl.value }.takeIf { it >= 0 }
            ?: state.songIndex.value.coerceIn(0, q.size - 1)
        android.util.Log.d(TAG, "next: cur=$cur q.size=${q.size} repeat=${state.repeat.value} curId=$curId")
        val nextIdx: Int
        if (cur < q.size - 1) {
            nextIdx = cur + 1
        } else {
            if (state.repeat.value == RepeatMode.ALL) {
                nextIdx = 0
            } else {
                android.util.Log.d(TAG, "next: at end, repeat off - not advancing")
                releaseWakeLock("spotui:next")
                return
            }
        }
        val song = q[nextIdx]
        android.util.Log.d(TAG, "next: advancing to idx=$nextIdx song=${song.title}")
        state.updateSongState(
            song.coverUri, song.title, song.singer, true,
            song.id, nextIdx, song.album
        )
        playSong(song.url, context, "song/${song.id}")
    }

    fun previous(context: Context) {
        acquireWakeLock(context, "spotui:previous", 60_000L)
        val state = boundState
        if (state == null) {
            android.util.Log.w(TAG, "previous: boundState is null")
            releaseWakeLock("spotui:previous")
            return
        }
        val q = state.queue.value
        if (q.isEmpty()) {
            android.util.Log.w(TAG, "previous: queue is empty")
            releaseWakeLock("spotui:previous")
            return
        }
        val curId = state.songId.value
        val cur = q.indexOfFirst { it.id == curId }
            .takeIf { it >= 0 }
            ?: q.indexOfFirst { it.url == state.songUrl.value }.takeIf { it >= 0 }
            ?: state.songIndex.value.coerceIn(0, q.size - 1)
        android.util.Log.d(TAG, "previous: cur=$cur q.size=${q.size} repeat=${state.repeat.value}")
        val nextIdx: Int
        if (cur > 0) {
            nextIdx = cur - 1
        } else {
            if (state.repeat.value == RepeatMode.ALL) {
                nextIdx = q.size - 1
            } else {
                android.util.Log.d(TAG, "previous: at start, repeat off - not going back")
                releaseWakeLock("spotui:previous")
                return
            }
        }
        val song = q[nextIdx]
        android.util.Log.d(TAG, "previous: going to idx=$nextIdx song=${song.title}")
        state.updateSongState(
            song.coverUri, song.title, song.singer, true,
            song.id, nextIdx, song.album
        )
        playSong(song.url, context, "song/${song.id}")
    }

    fun seekTo(position: Long) {
        cancelCrossfade()
        if (webPlaybackActive()) { SpotifyWebPlayer.seekTo(position); return }
        player?.seekTo(position)
    }

    fun release() {
        positionWatchJob?.cancel()
        cancelCrossfade()
        releaseWakeLock("spotui:release")
        player?.release()
        player = null
        loadedQuery = null
        currentRequest = ""
    }

    fun getDuration(): Long {
        if (webPlaybackActive()) return SpotifyWebPlayer.durationMs
        return player?.duration ?: 0L
    }

    fun getCurrentPosition(): Long {
        if (webPlaybackActive()) return SpotifyWebPlayer.positionMs
        return player?.currentPosition ?: 0L
    }

    fun isPrepared(): Boolean {
        val playerState = player?.playbackState
        return playerState != null && playerState != ExoPlayer.STATE_IDLE && playerState != ExoPlayer.STATE_ENDED
    }

    private const val CF_LPF_START_HZ = 20000f
    private const val CF_LPF_END_HZ = 200f
    private const val CF_HPF_START_HZ = 2000f
    private const val CF_HPF_END_HZ = 20f
    private const val CF_SIGMOID_K = 6f

    @Volatile private var appCtx: Context? = null
    @Volatile private var boundState: CurrentSongState? = null
    @Volatile private var currentPlayerFilter: com.music.spotui.audio.CrossfadeFilterAudioProcessor? = null
    @Volatile private var currentPlayerEqualizer: com.music.spotui.audio.EqualizerAudioProcessor? = null
    @Volatile private var currentPlayerNormalizer: com.music.spotui.audio.VolumeNormalizationAudioProcessor? = null
    @Volatile private var secondaryPlayer: ExoPlayer? = null
    @Volatile private var secondaryPlayerFilter: com.music.spotui.audio.CrossfadeFilterAudioProcessor? = null
    @Volatile private var secondaryPlayerEqualizer: com.music.spotui.audio.EqualizerAudioProcessor? = null
    @Volatile private var secondaryPlayerNormalizer: com.music.spotui.audio.VolumeNormalizationAudioProcessor? = null
    @Volatile private var isCrossfading = false
    @Volatile private var crossfadeJob: kotlinx.coroutines.Job? = null
    @Volatile private var positionWatchJob: kotlinx.coroutines.Job? = null

    @Volatile var onPlayerSwapped: ((ExoPlayer) -> Unit)? = null

    fun bindState(state: CurrentSongState) { boundState = state }

    fun onNormalizationSettingChanged(context: Context) {
        val enabled = com.music.spotui.data.preferences.isAudioNormalizationEnabled(context)
        currentPlayerNormalizer?.enabled = enabled
        secondaryPlayerNormalizer?.enabled = enabled
    }

    fun onEqualizerSettingChanged(context: Context) {
        val enabled = com.music.spotui.data.preferences.isEqualizerEnabled(context)
        val gains = com.music.spotui.data.preferences.getEqualizerBandGains(context)
        currentPlayerEqualizer?.apply {
            this.enabled = enabled
            setBandGains(gains)
        }
        secondaryPlayerEqualizer?.apply {
            this.enabled = enabled
            setBandGains(gains)
        }
    }

    fun isCrossfadeActive(): Boolean = isCrossfading

    private fun sigmoid(t: Float): Float = 1.0f / (1.0f + exp(-CF_SIGMOID_K * (t - 0.5f)))

    private fun expInterpolate(start: Float, end: Float, t: Float): Float {
        if (start <= 0f || end <= 0f) return end
        return exp(ln(start) + (ln(end) - ln(start)) * t).toFloat()
    }

    private fun cancelCrossfade() {
        if (!isCrossfading && secondaryPlayer == null) return
        crossfadeJob?.cancel()
        crossfadeJob = null
        currentPlayerFilter?.enabled = false
        secondaryPlayerFilter?.enabled = false
        currentPlayerEqualizer?.apply {
            enabled = com.music.spotui.data.preferences.isEqualizerEnabled(appCtx ?: return)
            setBandGains(com.music.spotui.data.preferences.getEqualizerBandGains(appCtx ?: return))
        }
        secondaryPlayerEqualizer?.enabled = false
        currentPlayerNormalizer?.enabled = com.music.spotui.data.preferences.isAudioNormalizationEnabled(appCtx ?: return)
        secondaryPlayerNormalizer?.enabled = false
        runCatching { secondaryPlayer?.release() }
        secondaryPlayer = null
        secondaryPlayerFilter = null
        secondaryPlayerEqualizer = null
        secondaryPlayerNormalizer = null
        player?.volume = 1f
        isCrossfading = false
        releaseWakeLock("spotui:crossfade")
    }

    private var posSaveTick = 0

    private fun startPositionWatch() {
        positionWatchJob?.cancel()
        positionWatchJob = scope.launch {
            while (isActive) {
                kotlinx.coroutines.delay(250)
                val ctx = appCtx ?: continue
                if (++posSaveTick % 12 == 0 && !webPlaybackActive()) {
                    player?.let { p ->
                        val pos = withContext(Dispatchers.Main) {
                            if (p.isPlaying) p.currentPosition else -1L
                        }
                        if (pos > 0) com.music.spotui.data.preferences.saveLastPosition(ctx, pos)
                    }
                }
                if (isCrossfading) continue
                val crossfadeMs = com.music.spotui.data.preferences.getCrossfadeMs(ctx)
                if (crossfadeMs <= 0) continue
                val state = boundState ?: continue
                if (state.repeat.value == RepeatMode.ONE) continue
                val p = player ?: continue
                val playing = withContext(Dispatchers.Main) { p.isPlaying }
                if (!playing) continue
                val dur = withContext(Dispatchers.Main) { p.duration }
                val pos = withContext(Dispatchers.Main) { p.currentPosition }
                if (dur <= 0 || pos < 0) continue
                if (pos >= dur - crossfadeMs) {
                    triggerCrossfade(ctx, crossfadeMs)
                }
            }
        }
    }

    private fun triggerCrossfade(ctx: Context, configuredMs: Int) {
        if (isCrossfading) return
        val state = boundState ?: return
        val q = state.queue.value
        if (q.isEmpty()) return
        val cur = q.indexOfFirst { it.id == state.songId.value }
            .takeIf { it >= 0 }
            ?: q.indexOfFirst { it.url == state.songUrl.value }.takeIf { it >= 0 }
            ?: state.songIndex.value.coerceIn(0, q.size - 1)
        if (cur < 0 || cur >= q.size - 1) return
        val nextSong = q[cur + 1]
        isCrossfading = true
        acquireWakeLock(ctx, "spotui:crossfade", 60_000L)
        scope.launch {
            try {
                val nextUrl = resolveStreamUrl(nextSong.url, ctx, forPlayback = true) ?: run {
                    isCrossfading = false
                    releaseWakeLock("spotui:crossfade")
                    return@launch
                }
                val remaining = withContext(Dispatchers.Main) {
                    val p = player ?: return@withContext configuredMs.toLong()
                    val d = p.duration; val ps = p.currentPosition
                    if (d > 0 && ps >= 0) (d - ps) else configuredMs.toLong()
                }
                val effectiveMs = minOf(configuredMs.toLong(), remaining).coerceAtLeast(1000L).toInt()
                val djMode = com.music.spotui.data.preferences.isCrossfadeDjMode(ctx)

                withContext(Dispatchers.Main) {
                    val comp = createPlayerWithFilter(ctx, handleAudioFocus = false)
                    secondaryPlayer = comp.player
                    secondaryPlayerFilter = comp.filter
                    secondaryPlayerEqualizer = comp.equalizer
                    secondaryPlayerNormalizer = comp.normalizer
                    val metadataBuilder = androidx.media3.common.MediaMetadata.Builder()
                        .setTitle(nextSong.title)
                        .setArtist(nextSong.singer)
                    com.music.spotui.util.ArtworkHelper.attachArtwork(
                        metadataBuilder, ctx, nextSong.coverUri, "song/${nextSong.id}", nextUrl
                    )
                    val stableKey = com.music.spotui.audio.LosslessCacheKeyFactory.buildCacheKey(
                        nextSong.spotifyTrackId.ifBlank { null }, nextUrl
                    )
                    val item = MediaItem.Builder()
                        .setMediaId("song/${nextSong.id}")
                        .setUri(nextUrl)
                        .setCustomCacheKey(stableKey)
                        .apply { streamMimeType(nextUrl)?.let { setMimeType(it) } }
                        .setMediaMetadata(metadataBuilder.build())
                        .build()
                    comp.player.setMediaItem(item)
                    comp.player.prepare()
                    comp.player.volume = 0f
                    comp.player.playWhenReady = true

                    metaTitle = nextSong.title
                    metaArtist = nextSong.singer
                    metaCover = nextSong.coverUri
                    currentMediaId = "song/${nextSong.id}"
                    boundState?.setSongUrl(nextSong.url)
                    boundState?.updateSongState(
                        nextSong.coverUri, nextSong.title, nextSong.singer, true,
                        nextSong.id, cur + 1, nextSong.album,
                    )
                }
                performCrossfade(effectiveMs, djMode, nextSong, cur + 1)
            } catch (e: Exception) {
                Log.e(TAG, "crossfade failed", e)
                cancelCrossfade()
            }
        }
    }

    private suspend fun performCrossfade(
        effectiveMs: Int,
        djMode: Boolean,
        nextSong: com.music.spotui.data.entity.SongsModel,
        nextIdx: Int,
    ) {
        val steps = 50
        val delayPerStep = (effectiveMs / steps).coerceAtLeast(20)
        if (djMode) {
            currentPlayerFilter?.apply {
                filterType = com.music.spotui.audio.BiquadFilter.FilterType.LOW_PASS
                cutoffFrequencyHz = CF_LPF_START_HZ; enabled = true
            }
            secondaryPlayerFilter?.apply {
                filterType = com.music.spotui.audio.BiquadFilter.FilterType.HIGH_PASS
                cutoffFrequencyHz = CF_HPF_START_HZ; enabled = true
            }
        }
        crossfadeJob?.cancel()
        val job = scope.launch {
            try {
                for (step in 0..steps) {
                    if (!isActive) break
                    val progress = step.toFloat() / steps
                    val angle = (progress * PI / 2).toFloat()
                    withContext(Dispatchers.Main) {
                        player?.volume = cos(angle)
                        secondaryPlayer?.volume = sin(angle)
                        if (djMode) {
                            val fp = sigmoid(progress)
                            currentPlayerFilter?.cutoffFrequencyHz = expInterpolate(CF_LPF_START_HZ, CF_LPF_END_HZ, fp)
                            secondaryPlayerFilter?.cutoffFrequencyHz = expInterpolate(CF_HPF_START_HZ, CF_HPF_END_HZ, fp)
                        }
                    }
                    kotlinx.coroutines.delay(delayPerStep.toLong())
                }
                finalizeCrossfade(nextSong, nextIdx)
            } catch (e: kotlinx.coroutines.CancellationException) {
                throw e
            }
        }
        crossfadeJob = job
        job.join()
    }

    private suspend fun finalizeCrossfade(
        nextSong: com.music.spotui.data.entity.SongsModel,
        nextIdx: Int,
    ) {
        withContext(Dispatchers.Main) {
            val incoming = secondaryPlayer ?: run {
                isCrossfading = false
                releaseWakeLock("spotui:crossfade")
                return@withContext
            }
            val old = player
            currentPlayerFilter?.enabled = false
            secondaryPlayerFilter?.enabled = false
            currentPlayerEqualizer?.enabled = false
            secondaryPlayerEqualizer?.enabled = false
            currentPlayerNormalizer?.enabled = false
            secondaryPlayerNormalizer?.enabled = false
            player = incoming
            currentPlayerFilter = secondaryPlayerFilter
            currentPlayerEqualizer = secondaryPlayerEqualizer
            currentPlayerNormalizer = secondaryPlayerNormalizer
            secondaryPlayer = null
            secondaryPlayerFilter = null
            secondaryPlayerEqualizer = null
            secondaryPlayerNormalizer = null
            incoming.volume = 1f

            metaTitle = nextSong.title
            metaArtist = nextSong.singer
            metaCover = nextSong.coverUri
            currentMediaId = "song/${nextSong.id}"
            boundState?.setSongUrl(nextSong.url)
            boundState?.updateSongState(
                nextSong.coverUri, nextSong.title, nextSong.singer, true,
                nextSong.id, nextIdx, nextSong.album,
            )

            incoming.setAudioAttributes(buildAudioAttributes(), /* handleAudioFocus = */ true)
            incoming.setHandleAudioBecomingNoisy(true)
            runCatching { old?.stop(); old?.release() }
            isCrossfading = false
            releaseWakeLock("spotui:crossfade")
            onPlayerSwapped?.invoke(incoming)
        }
        startPositionWatch()
    }

    @Volatile private var sleepJob: kotlinx.coroutines.Job? = null
    @Volatile var sleepTimerEndAt: Long = 0L
        private set

    fun setSleepTimer(durationMillis: Long) {
        sleepJob?.cancel()
        if (durationMillis <= 0L) {
            sleepTimerEndAt = 0L
            return
        }
        sleepTimerEndAt = System.currentTimeMillis() + durationMillis
        sleepJob = scope.launch {
            kotlinx.coroutines.delay(durationMillis)
            withContext(Dispatchers.Main) { pause() }
            sleepTimerEndAt = 0L
        }
    }

    fun cancelSleepTimer() {
        sleepJob?.cancel()
        sleepJob = null
        sleepTimerEndAt = 0L
    }
}