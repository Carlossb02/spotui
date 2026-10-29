package com.music.spotui.ui.screens

import com.music.spotui.util.uiLocalized

import android.content.Context
import android.content.ContextWrapper
import android.view.LayoutInflater
import android.util.Log
import androidx.annotation.OptIn
import androidx.compose.foundation.background
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.tween
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Fullscreen
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Replay
import androidx.compose.material.icons.filled.Forward
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.PictureInPictureAlt
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.Slider
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import com.music.spotui.MainActivity
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.media3.common.MediaItem
import androidx.media3.common.MimeTypes
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.ui.AspectRatioFrameLayout
import androidx.media3.ui.PlayerView
import com.music.spotui.R
import com.metrolist.innertube.NewPipeExtractor
import com.metrolist.innertube.YouTube
import com.metrolist.innertube.models.YouTubeClient
import com.metrolist.innertube.models.response.PlayerResponse
import com.music.spotui.di.SongPlayer
import kotlinx.coroutines.*
import kotlin.math.abs
import java.util.Locale

/** Activity presentation state. PiP and the app share SongPlayer's MediaSession. */
object PodcastVideoPipState {
    var isInPictureInPicture by mutableStateOf(false)
        internal set
}

/** The only video surface rendered while the activity is in system PiP. */
@Composable
fun PodcastPipContent(modifier: Modifier = Modifier) {
    Box(modifier.background(Color.Black)) {
        VideoSurface(Modifier.fillMaxSize(), priority = 10)
    }
}

private fun Context.spotuiActivity(): MainActivity? {
    var context: Context = this
    while (context is ContextWrapper) {
        if (context is MainActivity) return context
        val base = context.baseContext
        if (base === context) break
        context = base
    }
    return context as? MainActivity
}

/** A concrete quality choice for one video-only stream of the same podcast. */
data class PodcastVideoQuality(
    val height: Int,
    val url: String,
    val mimeType: String,
    val muxed: Boolean = false,
    val frameRate: Int = 30,
    val verifiedForStartup: Boolean = false,
) {
    val label: String get() = "${height}p"
}

internal object PodcastVideoStreams {
    private val previewCache = java.util.concurrent.ConcurrentHashMap<String, Pair<Long, PodcastVideoQuality>>()
    private val videoOptionsCache = java.util.concurrent.ConcurrentHashMap<String, Pair<Long, List<PodcastVideoQuality>>>()
    // Signed format URLs can expire earlier than YouTube's advertised expiry;
    // keep this cache short and prefer the response already verified by audio.
    private const val VIDEO_OPTIONS_TTL_MS = 90_000L
    private val resolvingLock = Any()
    private val resolvingScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val inFlight = HashMap<String, Deferred<List<PodcastVideoQuality>>>()
    private val priming = HashMap<String, Deferred<List<PodcastVideoQuality>>>()
    private val adaptiveManifestCache = java.util.concurrent.ConcurrentHashMap<String, Pair<Long, MediaItem>>()
    private val adaptiveLowestHeight = java.util.concurrent.ConcurrentHashMap<String, Int>()
    private val alternativeVideoResponses = java.util.concurrent.ConcurrentHashMap<String, Pair<Long, PlayerResponse>>()
    private fun keepMatchingResponse(videoId: String, response: PlayerResponse) {
        if (response.playabilityStatus.status == "OK" &&
            response.videoDetails?.videoId == videoId) {
            if (alternativeVideoResponses.size > 20) {
                val now = System.currentTimeMillis()
                alternativeVideoResponses.entries.removeIf {
                    now - it.value.first !in 0L..VIDEO_OPTIONS_TTL_MS
                }
            }
            alternativeVideoResponses[videoId] = System.currentTimeMillis() to response
        }
    }
    fun lowestAdaptiveHeight(videoId: String): Int? = adaptiveLowestHeight[videoId]

    // Shared connections avoid a fresh TLS setup and thread pool for each quality probe.
    private val videoProbeClient by lazy {
        okhttp3.OkHttpClient.Builder()
            .connectTimeout(3, java.util.concurrent.TimeUnit.SECONDS)
            .readTimeout(3, java.util.concurrent.TimeUnit.SECONDS)
            .callTimeout(4, java.util.concurrent.TimeUnit.SECONDS)
            .build()
    }

    suspend fun isReadable(url: String): Boolean = withContext(Dispatchers.IO) {
        runCatching {
            val request = okhttp3.Request.Builder().url(url)
                .header("User-Agent", "com.google.ios.youtube/21.03.1 (iPhone16,2; U; CPU iOS 18_2 like Mac OS X;)")
                .header("Range", "bytes=0-1023").build()
            videoProbeClient.newCall(request).execute().use { result ->
                val valid = result.code == 200 || result.code == 206
                val readable = valid && result.body?.source()?.request(1L) == true
                Log.d("SpotuiPodcastVideo", "video stream probe status=${result.code} readable=$readable")
                readable
            }
        }.getOrElse { false }
    }

    /** A raw Innertube HTTPS URL may still be throttled or rejected (403).
     * Probe at most two small startup renditions with HTTP Range on IO,
     * sequentially: do not leave a second HTTP request holding up a successful
     * first source. Never read the entire video.
     * Keep the rest of the quality catalogue and its manual selection intact. */
    private suspend fun playableAtStartup(options: List<PodcastVideoQuality>): List<PodcastVideoQuality> = coroutineScope {
        // A muxed MP4 (itag 18/22) is also usable when audio selection is
        // disabled in the companion player. It must not be rejected if YouTube
        // offers no separate video-only tracks.
        val byStartup = compareBy<PodcastVideoQuality> {
            if (it.height in 144..360) 0 else if (it.height in 361..480) 1 else 2
        }.thenBy { it.height }
        val videoOnly = options.filterNot { it.muxed }.sortedWith(byStartup).take(2)
        // Verify a muxed MP4 as an emergency fallback as well: the companion
        // player's AUDIO track is disabled, so no second soundtrack is heard.
        val muxedFallback = options.filter { it.muxed }.sortedWith(byStartup).take(1)
        val candidates = (videoOnly + muxedFallback).ifEmpty { options.sortedWith(byStartup).take(2) }
        if (candidates.isEmpty()) return@coroutineScope emptyList()
        // Each probe is just a 1 KiB Range. Parallel failure checks save a whole
        // network roundtrip without skipping CDN verification (the v4 black screen).
        val pending = candidates.mapIndexed { i, choice ->
            i to async { isReadable(choice.url) }
        }.toMutableList()
        var firstGood: Int? = null
        val failedUrls = HashSet<String>()
        while (pending.isNotEmpty()) {
            // A playable rendition may arrive before the preferred one. Use
            // it immediately, never wait for a slow/blocked range request.
            val result = kotlinx.coroutines.selects.select<Pair<Int, Boolean>> {
                pending.forEach { (index, task) -> task.onAwait { index to it } }
            }
            pending.removeAll { it.first == result.first }
            if (result.second) { firstGood = result.first; break }
            failedUrls.add(candidates[result.first].url)
        }
        pending.forEach { it.second.cancel() }
        val selected = firstGood?.let { candidates[it] } ?: return@coroutineScope emptyList()
        options.filterNot { it.url in failedUrls }.map {
            if (it.url == selected.url) it.copy(verifiedForStartup = true) else it
        }
    }

    /** No-network hot path: immediately reuse direct unthrottled video URLs from
     * the response already fetched for the SAME podcast's audio. Media3 itself
     * validates the stream and the existing fallback handles any 403. A signed
     * URL with an undeciphered `n` parameter is NOT used here: those must go
     * through the normal decipher/fallback path to avoid a black frame. */
    fun immediateFromAudio(videoId: String): List<PodcastVideoQuality> {
        val now = System.currentTimeMillis()
        videoOptionsCache[videoId]?.takeIf { now - it.first in 0L..VIDEO_OPTIONS_TTL_MS }
            ?.second?.takeIf { it.isNotEmpty() }?.let { return it }
        val response = com.metrolist.music.utils.YTPlayerUtils.recentVideoResponse(videoId)
            ?: return emptyList()
        if (response.playabilityStatus.status != "OK" ||
            response.videoDetails?.videoId != videoId) return emptyList()
        val result = response.streamingData?.adaptiveFormats.orEmpty()
            .asSequence()
            .filter { it.mimeType.startsWith("video/") &&
                (it.height ?: 0) in 144..720 && (it.fps ?: 30) <= 30 }
            .sortedWith(compareByDescending<PlayerResponse.StreamingData.Format> {
                it.mimeType.startsWith("video/mp4") && it.mimeType.contains("avc1")
            }.thenBy { kotlin.math.abs((it.height ?: 360) - 360) })
            .mapNotNull { f ->
                val direct = f.url?.takeIf { url -> url.startsWith("https://") &&
                    !Regex("[?&]n=").containsMatchIn(url) }
                    ?: return@mapNotNull null
                PodcastVideoQuality(
                    height = f.height ?: 360, url = direct,
                    mimeType = if (f.mimeType.startsWith("video/webm"))
                        MimeTypes.VIDEO_WEBM else MimeTypes.VIDEO_MP4,
                    frameRate = f.fps ?: 30,
                )
            }.distinctBy { it.height }.take(4).toList()
        if (result.isNotEmpty()) {
            videoOptionsCache[videoId] = now to result
            Log.i("SpotuiStartup", "video source reused from audio metadata without extra HTTP requests")
        }
        return result
    }

    /** Construct an on-demand DASH adaptation set from video-only MP4 URLs.
     * Works only if the already-fetched response includes *real* init/index
     * ranges (SIDX); inventing byte ranges causes black frames. A single
     * Media3 DASH source then switches representations according to measured
     * bandwidth without resetting the podcast audio or video timeline.
     * If any prerequisite is missing, progressive playback remains available.
     */
    fun seamlessAdaptiveItem(videoId: String): MediaItem? {
        val now = System.currentTimeMillis()
        adaptiveManifestCache[videoId]?.takeIf { now - it.first in 0L..VIDEO_OPTIONS_TTL_MS }
            ?.second?.let { return it }
        val response = listOfNotNull(
            com.metrolist.music.utils.YTPlayerUtils.recentVideoResponse(videoId),
            alternativeVideoResponses[videoId]
                ?.takeIf { now - it.first in 0L..VIDEO_OPTIONS_TTL_MS }?.second,
        ).firstOrNull { candidate ->
            candidate.playabilityStatus.status == "OK" &&
                candidate.videoDetails?.videoId == videoId &&
                candidate.streamingData?.adaptiveFormats.orEmpty().count { f ->
                    f.mimeType.startsWith("video/mp4") && f.mimeType.contains("avc1") &&
                        f.initRange != null && f.indexRange != null &&
                        f.url?.let { url -> url.startsWith("https://") &&
                            !Regex("[?&]n=").containsMatchIn(url) } == true
                } >= 2
        } ?: return null
        val duration = response.videoDetails?.lengthSeconds?.toLongOrNull()
            ?.takeIf { it > 0 } ?: return null
        data class Rendition(
            val format: PlayerResponse.StreamingData.Format,
            val direct: String,
            val codec: String,
            val initStart: Long, val initEnd: Long, val indexStart: Long, val indexEnd: Long
        )
        val formats = response.streamingData?.adaptiveFormats.orEmpty()
            .mapNotNull { f ->
                val direct = f.url?.takeIf { url -> url.startsWith("https://") &&
                    !Regex("[?&]n=").containsMatchIn(url) } ?: return@mapNotNull null
                if (!f.mimeType.startsWith("video/mp4") ||
                    !f.mimeType.contains("avc1") || (f.height ?: 0) !in 144..1080 ||
                    (f.fps ?: 30) > 30) return@mapNotNull null
                val initialization = f.initRange ?: return@mapNotNull null
                val segmentIndex = f.indexRange ?: return@mapNotNull null
                val initS = initialization.start.toLongOrNull() ?: return@mapNotNull null
                val initE = initialization.end.toLongOrNull() ?: return@mapNotNull null
                val indexS = segmentIndex.start.toLongOrNull() ?: return@mapNotNull null
                val indexE = segmentIndex.end.toLongOrNull() ?: return@mapNotNull null
                if (initS < 0 || initE < initS || indexS < 0 || indexE < indexS) return@mapNotNull null
                val codec = Regex("avc1\\.[0-9A-Fa-f]+")
                    .find(f.mimeType)?.value ?: return@mapNotNull null
                Rendition(f, direct, codec, initS, initE, indexS, indexE)
            }
            .distinctBy { it.format.height }
            .sortedBy { it.format.bitrate }
        if (formats.size < 2 || formats.none { (it.format.height ?: 0) <= 480 }) return null
        adaptiveLowestHeight[videoId] = formats.minOf { it.format.height ?: 360 }
        fun xml(value: String): String = value.replace("&", "&amp;")
            .replace("<", "&lt;").replace(">", "&gt;")
            .replace("\"", "&quot;")
        val xmlManifest = buildString {
            append("<?xml version=\"1.0\" encoding=\"UTF-8\"?>")
            append("<MPD xmlns=\"urn:mpeg:dash:schema:mpd:2011\" type=\"static\"")
            append(" profiles=\"urn:mpeg:dash:profile:isoff-on-demand:2011\"")
            append(" minBufferTime=\"PT1S\" mediaPresentationDuration=\"PT${duration}S\">")
            append("<Period id=\"podcast\" start=\"PT0S\">")
            append("<AdaptationSet id=\"0\" contentType=\"video\" mimeType=\"video/mp4\"")
            append(" segmentAlignment=\"true\" startWithSAP=\"1\">")
            formats.forEach { r ->
                val f = r.format
                append("<Representation id=\"${f.itag}\" bandwidth=\"${f.bitrate}\"")
                append(" width=\"${f.width ?: 640}\" height=\"${f.height}\"")
                append(" codecs=\"${r.codec}\" frameRate=\"${f.fps ?: 30}\">")
                append("<BaseURL>${xml(r.direct)}</BaseURL>")
                append("<SegmentBase indexRange=\"${r.indexStart}-${r.indexEnd}\">")
                append("<Initialization range=\"${r.initStart}-${r.initEnd}\"/>")
                append("</SegmentBase></Representation>")
            }
            append("</AdaptationSet></Period></MPD>")
        }
        val encoded = android.util.Base64.encodeToString(
            xmlManifest.toByteArray(Charsets.UTF_8), android.util.Base64.NO_WRAP)
        Log.i("SpotuiStartup", "Video DASH adaptation set available: ${formats.size} renditions")
        return MediaItem.Builder()
            .setUri("data:application/dash+xml;base64,$encoded")
            .setMimeType(MimeTypes.APPLICATION_MPD)
            .build().also { adaptiveManifestCache[videoId] = now to it }
    }

    /** Cheap prewarming path for URLs requiring YouTube `n` decoding.
     * Decode just ONE suitable visual rendition, never eight and never run a
     * separate CDN probe on a link that Media3 will validate when preparing.
     * Can be called just after audio begins; the resulting cache is reused on
     * the video toggle, avoiding a second Innertube lookup altogether. */
    suspend fun primeFromAudio(videoId: String): List<PodcastVideoQuality> {
        immediateFromAudio(videoId).takeIf { it.isNotEmpty() }?.let { return it }
        // Prewarming and the user's toggle must await the SAME decipher job.
        // The otherwise duplicated JavaScript resolution can monopolise CPU.
        val pending = synchronized(resolvingLock) {
            priming[videoId] ?: resolvingScope.async {
                decodeSingleVideoFromAudio(videoId)
            }.also { created ->
                priming[videoId] = created
                created.invokeOnCompletion {
                    synchronized(resolvingLock) { priming.remove(videoId, created) }
                }
            }
        }
        return pending.await()
    }

    private suspend fun decodeSingleVideoFromAudio(videoId: String): List<PodcastVideoQuality> =
        withContext(Dispatchers.IO) {
            val response = com.metrolist.music.utils.YTPlayerUtils.recentVideoResponse(videoId)
                ?: return@withContext emptyList()
            if (response.playabilityStatus.status != "OK" ||
                response.videoDetails?.videoId != videoId) return@withContext emptyList()
            val options = response.streamingData?.adaptiveFormats.orEmpty()
                .asSequence()
                .filter { it.mimeType.startsWith("video/") &&
                    (it.height ?: 0) in 144..720 && (it.fps ?: 30) <= 30 }
                .sortedWith(compareByDescending<PlayerResponse.StreamingData.Format> {
                    it.mimeType.startsWith("video/mp4") && it.mimeType.contains("avc1")
                }.thenBy { kotlin.math.abs((it.height ?: 360) - 360) })
                .toList()
            // One JS decipher attempt for startup, not an entire catalogue.
            val first = options.firstOrNull() ?: return@withContext emptyList()
            val url = runCatching { NewPipeExtractor.getStreamUrl(first, videoId) }.getOrNull()
                ?.takeIf { it.startsWith("https://") } ?: return@withContext emptyList()
            val quality = PodcastVideoQuality(
                height = first.height ?: 360, url = url,
                mimeType = if (first.mimeType.startsWith("video/webm"))
                    MimeTypes.VIDEO_WEBM else MimeTypes.VIDEO_MP4,
                frameRate = first.fps ?: 30
            )
            videoOptionsCache[videoId] = System.currentTimeMillis() to listOf(quality)
            Log.i("SpotuiStartup", "Podcast audio response decoded one video stream for fast toggle")
            listOf(quality)
        }

    /** Bounded playback preview metadata; no decoder until a tile becomes active. */
    fun warmPreview(videoId: String): PodcastVideoQuality? = previewCache[videoId]
        ?.takeIf { System.currentTimeMillis() - it.first in 0L..VIDEO_OPTIONS_TTL_MS }?.second

    /** Resolve one small, silent hover preview with a short-lived stream URL cache.
     * No high-resolution probing, audio decoder or use of the active MediaSession. */
    suspend fun resolvePreview(videoId: String): PodcastVideoQuality? {
        val now = System.currentTimeMillis()
        previewCache[videoId]?.takeIf { now - it.first in 0L..VIDEO_OPTIONS_TTL_MS }
            ?.let { return it.second }
        // Hover should reuse current podcast metadata when possible, never
        // create another three-client lookup just for a muted thumbnail.
        val options = resolveUnshared(videoId, previewOnly = true)
        val selected = options.filter { !it.muxed }
            .sortedBy { kotlin.math.abs(it.height - 360) }
            .firstOrNull { isReadable(it.url) }
        if (selected != null) previewCache[videoId] = now to selected
        return selected
    }

    /** Prefer real 24/25/30-fps H.264. In particular don't prefer a 60-fps
     * AV1/VP9 stream over the same video's decoder-friendly 30-fps MP4 stream.
     * Do not treat muxed formats as silent video: SongPlayer owns all audio. */
    private fun videoOnlyFormats(response: PlayerResponse, videoId: String): List<PodcastVideoQuality> {
        if (response.playabilityStatus.status != "OK" ||
            response.videoDetails?.videoId?.let { it != videoId } == true) return emptyList()
        val formats = response.streamingData?.adaptiveFormats.orEmpty()
            .filter { it.width != null && it.height != null &&
                (it.height ?: 0) in 144..2160 && it.mimeType.startsWith("video/") }
            .sortedWith(compareByDescending<PlayerResponse.StreamingData.Format> {
                it.mimeType.startsWith("video/mp4") && it.mimeType.contains("avc1")
            }.thenByDescending {
                (it.fps ?: 30) in 24..30
            }.thenBy { it.bitrate })
        fun qualityFor(format: PlayerResponse.StreamingData.Format, url: String) =
            PodcastVideoQuality(
                height = format.height ?: 360, url = url,
                mimeType = if (format.mimeType.startsWith("video/webm"))
                    MimeTypes.VIDEO_WEBM else MimeTypes.VIDEO_MP4,
                muxed = false, frameRate = format.fps ?: 30,
            )
        // Decoding every quality's n/signature on the critical first-frame
        // path was causing 5–10-second stalls. Prefer already-direct formats
        // without touching JS; if none exists, decipher only a small 240/360p
        // startup candidate. Never trust undeciphered n= URLs (black video).
        val distinct = formats.distinctBy { it.height }.take(10)
        val fast = distinct.mapNotNull { fmt ->
            fmt.url?.takeIf { it.startsWith("https://") &&
                !Regex("[?&]n=").containsMatchIn(it) }
                ?.let { qualityFor(fmt, it) }
        }
        if (fast.isNotEmpty()) return fast
        return distinct.sortedBy { kotlin.math.abs((it.height ?: 360) - 240) }
            .take(2).mapNotNull { fmt ->
                runCatching { NewPipeExtractor.getStreamUrl(fmt, videoId) }.getOrNull()
                    ?.takeIf { it.startsWith("https://") }
                    ?.let { qualityFor(fmt, it) }
            }
    }

    /** Preserve the best supported rendition per resolution; the first client
     *  sometimes provides only 360p progressive URLs while later clients have
     *  720p/1080p adaptive video-only tracks. All sources are resolved off-main. */
    suspend fun resolve(videoId: String, previewOnly: Boolean = false): List<PodcastVideoQuality> {
        if (previewOnly) return resolveUnshared(videoId, previewOnly = true)
        val now = System.currentTimeMillis()
        videoOptionsCache[videoId]?.takeIf { now - it.first in 0L..VIDEO_OPTIONS_TTL_MS }
            ?.second?.takeIf { items -> items.any { it.verifiedForStartup } }
            ?.let { return it }
        // Shared, cancellable lookup: one user toggle and one Wi-Fi metadata
        // prefetch must not simultaneously make three YouTube.player requests.
        val pending = synchronized(resolvingLock) {
            inFlight[videoId] ?: resolvingScope.async { resolveUnshared(videoId, previewOnly = false) }
                .also { created ->
                    inFlight[videoId] = created
                    created.invokeOnCompletion {
                        synchronized(resolvingLock) { inFlight.remove(videoId, created) }
                    }
                }
        }
        return pending.await()
    }

    /** Discard a failed signed URL/response before one real network retry. */
    fun invalidate(videoId: String) {
        alternativeVideoResponses.remove(videoId)
        adaptiveLowestHeight.remove(videoId)
        adaptiveManifestCache.remove(videoId)
        videoOptionsCache.remove(videoId)
        com.metrolist.music.utils.YTPlayerUtils.invalidateRecentVideoResponse(videoId)
    }

    private suspend fun resolveUnshared(videoId: String, previewOnly: Boolean): List<PodcastVideoQuality> = withContext(Dispatchers.IO) {
        val now = System.currentTimeMillis()
        videoOptionsCache[videoId]?.let { (savedAt, streams) ->
            if (now >= savedAt && now - savedAt < VIDEO_OPTIONS_TTL_MS &&
                streams.any { it.verifiedForStartup }) {
                return@withContext streams
            }
        }
        // Reuse exact-episode player metadata already fetched by the podcast
        // audio resolver. No extra requests or speculative video decoder.
        run {
            com.metrolist.music.utils.YTPlayerUtils.recentVideoResponse(videoId)?.let { response ->
                val fromAudio = videoOnlyFormats(response, videoId)
                if (fromAudio.isNotEmpty()) {
                    val marked = fromAudio.mapIndexed { idx, q -> if (idx == 0) q.copy(verifiedForStartup = true) else q }
                    videoOptionsCache[videoId] = now to marked
                    Log.d("SpotuiPodcastVideo", "Reused audio player response for videoId=$videoId directly")
                    return@withContext marked.sortedByDescending { it.height }
                }
            }
        }
        val result = withTimeoutOrNull(if (previewOnly) 12_000L else 16_000L) {
            val bestByHeight = LinkedHashMap<Int, PodcastVideoQuality>()
            // Two Innertube clients in parallel; a third is a FALLBACK, not
            // another speculative connection. The fast-but-unstable build fanned
            // out too many player calls when several previews/episodes changed.
            val clients = listOf(YouTubeClient.WEB, YouTubeClient.ANDROID_VR_NO_AUTH)
            kotlinx.coroutines.coroutineScope {
                val pending = clients.map { client ->
                    async { runCatching { YouTube.player(videoId, client = client).getOrNull() }.getOrNull() }
                }.toMutableList()
                var firstUsableVideoAt = 0L
                while (pending.isNotEmpty()) {
                    suspend fun awaitNext() = kotlinx.coroutines.selects.select<
                        Pair<kotlinx.coroutines.Deferred<PlayerResponse?>, PlayerResponse?>
                    > {
                        pending.forEach { deferred ->
                            deferred.onAwait { response -> deferred to response }
                        }
                    }
                    val next = if (firstUsableVideoAt != 0L) {
                        val left = (firstUsableVideoAt + 180L - android.os.SystemClock.elapsedRealtime())
                            .coerceAtLeast(1L)
                        withTimeoutOrNull(left) { awaitNext() } ?: break
                    } else awaitNext()
                    pending.remove(next.first)
                    val response = next.second ?: continue
                    if (response.playabilityStatus.status != "OK") continue
                    keepMatchingResponse(videoId, response)
                    for (quality in videoOnlyFormats(response, videoId)) {
                        bestByHeight.putIfAbsent(quality.height, quality)
                    }
                    // Do not wait for a slow client once any video-only
                    // rendition exists. Allow at most 180 ms for additional
                    // qualities before handing a URL to the visual decoder.
                    if (bestByHeight.values.any { !it.muxed }) {
                        if (previewOnly && bestByHeight.keys.any { it <= 480 }) break
                        if (firstUsableVideoAt == 0L) {
                            firstUsableVideoAt = android.os.SystemClock.elapsedRealtime()
                        }
                        if (bestByHeight.keys.any { it >= 720 } &&
                            bestByHeight.keys.any { it <= 480 }) break
                    }
                }
                pending.forEach { if (it.isActive) it.cancel() }
            }
            if (bestByHeight.isEmpty()) {
                // The third client is queried only if both preferred client
                // paths returned no playable video; never skip this fallback.
                val mobileResponse = runCatching {
                    YouTube.player(videoId, client = YouTubeClient.MOBILE).getOrNull()
                }.getOrNull()
                if (mobileResponse?.playabilityStatus?.status == "OK") {
                    keepMatchingResponse(videoId, mobileResponse)
                    videoOnlyFormats(mobileResponse, videoId).forEach { quality ->
                        bestByHeight.putIfAbsent(quality.height, quality)
                    }
                }
            }
            if (bestByHeight.isEmpty()) {
                val fallback = runCatching { YouTube.getNewPipeStreamUrls(videoId) }.getOrDefault(emptyList())
                for ((itag, url) in fallback) {
                    val height = when (itag) {
                        137 -> 1080; 136 -> 720; 135 -> 480; 134 -> 360; 133 -> 240
                        22 -> 720; 18 -> 360; else -> continue
                    }
                    if (url.startsWith("https://")) bestByHeight.putIfAbsent(
                        height, PodcastVideoQuality(height, url, MimeTypes.VIDEO_MP4,
                            muxed = itag == 18 || itag == 22)
                    )
                }
            }
            bestByHeight.values.sortedByDescending { it.height }
        } ?: emptyList()
        val playable = if (previewOnly) result else playableAtStartup(result)
        if (!previewOnly && playable.isNotEmpty()) {
            if (videoOptionsCache.size > 32) {
                videoOptionsCache.entries.removeIf { now - it.value.first > VIDEO_OPTIONS_TTL_MS }
            }
            videoOptionsCache[videoId] = System.currentTimeMillis() to playable
        }
        playable
    }
}

/**
 * Playback invariant: the podcast soundtrack is owned exclusively by SongPlayer.
 * This optional, VIDEO-ONLY decoder never changes SongPlayer's MediaItem,
 * MediaSource, seek position, audio focus or pause state. Enabling the picture
 * therefore cannot interrupt the soundtrack, even while YouTube is loading.
 * Media3 does not permit adding a new track to a prepared ProgressiveMediaSource
 * without replacing that source: that is why the previous merged-player design
 * necessarily stopped the audio on each first video enable.
 */
@OptIn(UnstableApi::class)
object PodcastVideoSession {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private val tag = "SpotuiPodcastVideo"

    var activeVideoId by mutableStateOf<String?>(null)
        private set
    /** Visibility and decoder lifetime are independent: hiding a video must
     * never call setMediaItem(), prepare() or release(). */
    var videoVisible by mutableStateOf(false)
        private set
    var fullscreen by mutableStateOf(false)
    var player by mutableStateOf<ExoPlayer?>(null)
        private set
    var isLoading by mutableStateOf(false)
        private set
    var error by mutableStateOf<String?>(null)
        private set
    var qualities by mutableStateOf<List<PodcastVideoQuality>>(emptyList())
        private set
    var selectedQuality by mutableStateOf("")
        private set
    var automaticQuality by mutableStateOf(true)
        private set
    var enteringPictureInPicture by mutableStateOf(false)
        private set

    private var resolveJob: Job? = null
    private var firstFrameWatchdog: Job? = null
    private var videoClockJob: Job? = null
    private var surfaceHandoffJob: Job? = null
    private var retainedReleaseJob: Job? = null
    private var retainedPlayer: ExoPlayer? = null
    private var retainedVideoId: String? = null
    private var retainedQuality: PodcastVideoQuality? = null
    private var retainedQualities: List<PodcastVideoQuality> = emptyList()
    private var retainedAdaptive = false
    private var warmedPlayer: ExoPlayer? = null
    private var warmedVideoId: String? = null
    private var warmedQualities: List<PodcastVideoQuality> = emptyList()
    private var warmedInitialQuality: PodcastVideoQuality? = null
    private var warmedAdaptive = false
    private var warmJob: Job? = null
    private var warmRequestedId: String? = null
    private var warmExpiryJob: Job? = null
    private var currentQuality: PodcastVideoQuality? = null
    private var adaptiveActive = false
    private var adaptiveManifest: MediaItem? = null
    private var adaptiveMaxHeight = 480
    private var appContext: Context? = null
    private var attachedView: PlayerView? = null
    private val visibleTargets = LinkedHashMap<PlayerView, Int>()
    private var isForeground = true
    private var estimatedVideoBitrate = 0L
    private var lastReadyTime = 0L
    private var lastQualityChangeTime = 0L
    private var bufferingSince = 0L
    private var hadReadyFrame = false
    // Rate-limit hard synchronization: frequent seeks to keyframes can cause
    // 1-fps looking playback even when the URL and decoder are fast.
    private var lastSyncSeekAt = 0L
    private var firstVideoAlignPending = false
    private var lastSyncReportAt = 0L
    private var lastObservedAudioAt = -1L
    private var lastObservedClockAt = 0L
    private var videoRequestedAt = 0L
    private var failedUrls = mutableSetOf<String>()
    private var freshSourceRetryAttempted = false

    private const val PREF_KEY_VIDEO_MODE = "podcast_user_preferred_video_mode_v2"

    private fun loadVideoPreference(context: Context): Boolean {
        return context.getSharedPreferences("spotui_podcast_mode_prefs", Context.MODE_PRIVATE)
            .getBoolean(PREF_KEY_VIDEO_MODE, false)
    }

    private fun saveVideoPreference(context: Context, enabled: Boolean) {
        context.getSharedPreferences("spotui_podcast_mode_prefs", Context.MODE_PRIVATE)
            .edit().putBoolean(PREF_KEY_VIDEO_MODE, enabled).apply()
    }

    var userPreferredVideo by mutableStateOf<Boolean?>(null)

    /** One companion session per episode. The audio has already resolved this
     * exact YouTube video ID. Prewarming never creates a second visual player
     * and never changes SongPlayer's audio or MediaSession. */
    fun prewarm(context: Context, videoId: String) {
        if (!videoId.matches(Regex("[A-Za-z0-9_-]{11}"))) return
        if (!SongPlayer.isPodcastEpisodeLoaded(videoId)) return
        val preferred = userPreferredVideo ?: loadVideoPreference(context).also { userPreferredVideo = it }
        ensurePrepared(context, videoId, show = preferred)
    }

    private fun releaseWarmVideo() {
        warmExpiryJob?.cancel(); warmExpiryJob = null
        warmedPlayer?.release(); warmedPlayer = null
        warmedVideoId = null
        warmedQualities = emptyList()
        warmedInitialQuality = null
        warmedAdaptive = false
    }

    fun isActive(videoId: String?): Boolean =
        videoVisible && videoId != null && activeVideoId == videoId

    fun beginPipHandoff() {
        enteringPictureInPicture = true
        fullscreen = false
    }
    fun finishPipHandoff() {
        enteringPictureInPicture = false
        updateSurface()
    }

    /** The toggle controls visibility only. A prepared video continues to use
     * the *same* muted ExoPlayer and its already-buffered network connection. */
    fun setEnabled(context: Context, videoId: String, enabled: Boolean) {
        userPreferredVideo = enabled
        saveVideoPreference(context, enabled)
        if (!enabled) {
            if (activeVideoId == videoId) {
                videoVisible = false
                fullscreen = false
                updateSurface()
            }
            return
        }
        ensurePrepared(context, videoId, show = true)
    }

    private fun ensurePrepared(context: Context, videoId: String, show: Boolean) {
        if (activeVideoId == videoId) {
            if (show && error != null && resolveJob?.isActive != true &&
                player?.playbackState != Player.STATE_READY) {
                // Explicit retry after a failed URL. Merely toggling an
                // already prepared stream must NEVER trigger this path.
                stop()
                releaseRetained()
                ensurePrepared(context, videoId, show = true)
                return
            }
            if (show) {
                videoVisible = true
                updateSurface()
                player?.let { alignVideoWithAudio(it, initial = true) }
            }
            return // A pending resolution or prepared decoder is reused as-is.
        }
        stop()
        activeVideoId = videoId
        videoVisible = show
        appContext = context.applicationContext
        error = null
        isLoading = true
        videoRequestedAt = android.os.SystemClock.elapsedRealtime()
        failedUrls.clear()
        freshSourceRetryAttempted = false

        // Exactly one resolver and one visual ExoPlayer; no second speculative
        // decoder and no parallel video preparation for the same episode.
        releaseWarmVideo()
        releaseRetained()
        resolveJob = scope.launch {
            // Only YouTube's own videoId is used; this runs concurrently with
            // audio playback, which is never suspended for source lookup.
            val choices = try {
                // First frame should not wait for HTTP range-probes or
                // fresh Innertube metadata when the active audio already
                // supplied a usable video URL for this exact episode.
                // Only one lookup for this episode. The resolver reuses the
                // audio's Innertube response and checks the first CDN range;
                // unverified optimistic URLs were causing black video in v8.
                PodcastVideoStreams.resolve(videoId)
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (t: Exception) {
                Log.w(tag, "Could not retrieve video renditions", t)
                emptyList()
            }
            if (activeVideoId != videoId) return@launch
            Log.i("SpotuiStartup", "video metadata ready=" +
                "${android.os.SystemClock.elapsedRealtime() - videoRequestedAt}ms " +
                "qualities=${choices.size} videoId=$videoId")
            val videoOnly = choices.filterNot { it.muxed }
            // Prefer separate tracks; if only a muxed fallback passed the CDN
            // probe, allow that exact verified file with its audio disabled.
            qualities = (if (videoOnly.any { it.verifiedForStartup }) videoOnly else choices)
                .sortedByDescending { it.verifiedForStartup }
                .distinctBy { it.height }.sortedBy { it.height }
            val contextNow = appContext ?: return@launch
            if (qualities.isEmpty()) {
                isLoading = false
                error = "YouTube no ofrece vídeo compatible para este episodio"
                return@launch
            }
            // DASH is only generated if the exact episode audio response supplies
            // multiple codec-compatible MP4 tracks and genuine SIDX byte ranges.
            // Its representation switching is handled by Media3 (no setMediaItem
            // calls), so bandwidth adaptation does not stop video frames.
            // The speculative v8 data-URI DASH manifest was not validated by
            // Media3 and could leave the display black. Prefer known playable
            // video-only progressive MP4; never build a synthetic MPD here.
            adaptiveManifest = null
            // Start small and ramp up when the measured video network speed
            // supports it. No sequential high-resolution HTTP probes on click.
            val metered = runCatching {
                (contextNow.getSystemService(Context.CONNECTIVITY_SERVICE)
                    as? android.net.ConnectivityManager)?.isActiveNetworkMetered ?: true
            }.getOrDefault(true)
            val initialLimit = if (metered) 240 else 360
            // Start only with a rendition proven readable by the CDN range
            // probe. Other advertised qualities remain selectable manually.
            val first = qualities.filter { it.verifiedForStartup }.minByOrNull { it.height }
                ?: qualities.filter { it.height <= initialLimit }.maxByOrNull { it.height }
                ?: qualities.minByOrNull { it.height } ?: return@launch
            createVideoPlayer(contextNow, videoId)
            switchVisualQuality(first)
            startVideoClock(videoId)
        }
    }

    private fun buildVideoPlayer(context: Context, videoId: String): ExoPlayer {
        // IMPORTANT: do NOT reuse SongPlayer.exoPlayer here. Only a separate,
        // muted video decoder allows truly seamless audio -> video transition.
        val http = androidx.media3.datasource.DefaultHttpDataSource.Factory()
            .setUserAgent("com.google.ios.youtube/21.03.1 (iPhone16,2; U; CPU iOS 18_2 like Mac OS X;)")
            .setAllowCrossProtocolRedirects(true)
            .setConnectTimeoutMs(5_000)
            .setReadTimeoutMs(8_000)
        val factory = androidx.media3.exoplayer.source.DefaultMediaSourceFactory(
            androidx.media3.datasource.DefaultDataSource.Factory(context, http)
        )
        val loadControl = androidx.media3.exoplayer.DefaultLoadControl.Builder()
            .setBufferDurationsMs(5_000, 25_000, 450, 800).build()
        val visualPlayer = ExoPlayer.Builder(context)
            .setMediaSourceFactory(factory)
            .setLoadControl(loadControl)
            .setAudioAttributes(
                androidx.media3.common.AudioAttributes.Builder()
                    .setContentType(androidx.media3.common.C.AUDIO_CONTENT_TYPE_MOVIE)
                    .setUsage(androidx.media3.common.C.USAGE_MEDIA).build(),
                false // Visual-only player must never request audio focus.
            )
            .setHandleAudioBecomingNoisy(false)
            .build()
        visualPlayer.volume = 0f
        visualPlayer.trackSelectionParameters = visualPlayer.trackSelectionParameters.buildUpon()
            .setTrackTypeDisabled(androidx.media3.common.C.TRACK_TYPE_AUDIO, true)
            .build()
        visualPlayer.addListener(object : Player.Listener {
            override fun onPlaybackStateChanged(state: Int) {
                if (player !== visualPlayer || activeVideoId != videoId) return
                val now = android.os.SystemClock.elapsedRealtime()
                when (state) {
                    Player.STATE_READY -> {
                        if (!hadReadyFrame) {
                            Log.i("SpotuiStartup", "video first READY=" +
                                "${now - videoRequestedAt}ms videoId=$videoId " +
                                "quality=${currentQuality?.label} fps=${currentQuality?.frameRate}")
                        }
                        lastReadyTime = now
                        bufferingSince = 0L
                        // STATE_READY is not proof that a frame has been drawn.
                        // Keep the loading indicator until the visual decoder
                        // really renders; otherwise the UI is silently black.
                        alignVideoWithAudio(visualPlayer, true)
                        watchForFirstFrame(videoId, visualPlayer)
                    }
                    Player.STATE_BUFFERING -> if (bufferingSince == 0L) {
                        bufferingSince = now
                    }
                    else -> Unit
                }
            }
            override fun onRenderedFirstFrame() {
                if (player !== visualPlayer || activeVideoId != videoId) return
                val wasWaiting = !hadReadyFrame
                hadReadyFrame = true
                firstFrameWatchdog?.cancel()
                firstFrameWatchdog = null
                isLoading = false
                error = null
                if (wasWaiting) Log.i("SpotuiStartup", "video FIRST_FRAME=${android.os.SystemClock.elapsedRealtime() - videoRequestedAt}ms videoId=$videoId")
                if (adaptiveActive && automaticQuality) {
                    // Unlock quality adaptation only after visible video already
                    // plays; Media3's DASH track selector then changes quality
                    // on a segment boundary instead of re-preparing the source.
                    scope.launch {
                        delay(700L)
                        if (player === visualPlayer && adaptiveActive && automaticQuality) {
                            visualPlayer.trackSelectionParameters = visualPlayer.trackSelectionParameters
                                .buildUpon().setMaxVideoSize(Int.MAX_VALUE, Int.MAX_VALUE).build()
                        }
                    }
                }
            }
            override fun onPlayerError(failure: PlaybackException) {
                if (player !== visualPlayer || activeVideoId != videoId) return
                Log.w(tag, "Optional video failed (audio stays uninterrupted)", failure)
                recoverVideoImage(visualPlayer, videoId)
            }
        })
        // Actual transferred bytes, not claimed Wi-Fi link speed, determine
        // whether raising resolution is safe in Auto mode.
        visualPlayer.addAnalyticsListener(object : androidx.media3.exoplayer.analytics.AnalyticsListener {
            override fun onBandwidthEstimate(
                eventTime: androidx.media3.exoplayer.analytics.AnalyticsListener.EventTime,
                totalLoadTimeMs: Int,
                totalBytesLoaded: Long,
                bitrateEstimate: Long,
            ) {
                if (player === visualPlayer && bitrateEstimate > 0L) {
                    estimatedVideoBitrate = bitrateEstimate
                }
            }
        })
        return visualPlayer
    }

    private fun createVideoPlayer(context: Context, videoId: String) {
        player = buildVideoPlayer(context, videoId)
        updateSurface()
    }

    /** Media3 can report READY with an empty/invalid TextureView stream.
     * A missing first frame is treated as a playback failure, never success. */
    private fun watchForFirstFrame(videoId: String, p: ExoPlayer) {
        if (hadReadyFrame || player !== p || activeVideoId != videoId ||
            visibleTargets.isEmpty() || !SongPlayer.isPlaying()) return
        if (firstFrameWatchdog?.isActive == true) return
        firstFrameWatchdog = scope.launch {
            delay(5_500L)
            if (player === p && activeVideoId == videoId && !hadReadyFrame &&
                visibleTargets.isNotEmpty() && SongPlayer.isPlaying()) {
                Log.w(tag, "No first video frame after READY; trying a different source")
                recoverVideoImage(p, videoId)
            }
        }
    }

    /** Only the optional visual stream is replaced. Never touch SongPlayer. */
    private fun recoverVideoImage(p: ExoPlayer, videoId: String) {
        if (player !== p || activeVideoId != videoId) return
        firstFrameWatchdog?.cancel()
        firstFrameWatchdog = null
        if (adaptiveActive) {
            Log.w(tag, "DASH video unavailable; falling back to progressive MP4")
            adaptiveActive = false
            adaptiveManifest = null
            p.trackSelectionParameters = p.trackSelectionParameters.buildUpon()
                .setMaxVideoSize(Int.MAX_VALUE, Int.MAX_VALUE).build()
            val fallback = qualities.filter { it.height <= 360 }.maxByOrNull { it.height }
                ?: qualities.minByOrNull { it.height }
            if (fallback != null) { switchVisualQuality(fallback); return }
        }
        currentQuality?.url?.let { failedUrls.add(it) }
        // Don't cycle through many different resolutions from the same broken
        // player response. The cache may hold URLs lacking the right signature.
        val next = if (failedUrls.size < 2) {
            qualities.filter { it.url !in failedUrls }
                .filter { it.height < (currentQuality?.height ?: Int.MAX_VALUE) }
                .maxByOrNull { it.height }
                ?: qualities.filter { it.url !in failedUrls }.minByOrNull { it.height }
        } else null
        if (next != null) { switchVisualQuality(next); return }
        if (!freshSourceRetryAttempted) {
            freshSourceRetryAttempted = true
            isLoading = true
            scope.launch {
                PodcastVideoStreams.invalidate(videoId)
                val fresh = try { PodcastVideoStreams.resolve(videoId) }
                    catch (cancelled: CancellationException) { throw cancelled }
                    catch (failure: Exception) { Log.w(tag, "Video source retry failed", failure); emptyList() }
                if (activeVideoId != videoId || player !== p) return@launch
                val freshVideoOnly = fresh.filterNot { it.muxed }
                qualities = (if (freshVideoOnly.any { it.verifiedForStartup }) freshVideoOnly else fresh)
                    .sortedByDescending { it.verifiedForStartup }
                    .distinctBy { it.height }.sortedBy { it.height }
                failedUrls.clear()
                val first = qualities.firstOrNull { it.verifiedForStartup }
                    ?: qualities.filter { it.height <= 360 }.maxByOrNull { it.height }
                    ?: qualities.minByOrNull { it.height }
                if (first != null) switchVisualQuality(first)
                else { isLoading = false; error = "No se pudo obtener el vídeo; el audio sigue reproduciéndose" }
            }
        } else {
            isLoading = false
            error = "No se ha recibido ningún fotograma; el audio sigue reproduciéndose"
            p.playWhenReady = false
        }
    }

    /** Initial DASH load is pinned to the smallest available visual track,
     * then Media3 unlocks all tracks after the first frame; bitrate ABR owns
     * subsequent quality changes and does not reset the MediaItem/decoder. */
    private fun startAdaptiveVideo(fallback: PodcastVideoQuality) {
        val p = player ?: return
        val item = adaptiveManifest ?: run { switchVisualQuality(fallback); return }
        adaptiveActive = true
        currentQuality = fallback
        selectedQuality = "Auto"
        adaptiveMaxHeight = PodcastVideoStreams.lowestAdaptiveHeight(activeVideoId.orEmpty())
            ?: qualities.minOfOrNull { it.height } ?: 360
        lastQualityChangeTime = android.os.SystemClock.elapsedRealtime()
        firstVideoAlignPending = true
        hadReadyFrame = false
        isLoading = true
        p.trackSelectionParameters = p.trackSelectionParameters.buildUpon()
            .setMaxVideoSize(Int.MAX_VALUE, adaptiveMaxHeight)
            .build()
        p.setMediaItem(item, SongPlayer.getCurrentPosition().coerceAtLeast(0L))
        p.prepare()
        p.playWhenReady = SongPlayer.isPlaying() && visibleTargets.isNotEmpty()
    }

    private fun switchVisualQuality(choice: PodcastVideoQuality) {
        adaptiveActive = false
        val p = player ?: return
        if (activeVideoId == null || choice.url in failedUrls) return
        // A previously pinned low-resolution DASH startup track must never
        // restrict a manual progressive quality choice.
        p.trackSelectionParameters = p.trackSelectionParameters.buildUpon()
            .setMaxVideoSize(Int.MAX_VALUE, Int.MAX_VALUE).build()
        if (currentQuality == choice && p.mediaItemCount > 0) return
        firstFrameWatchdog?.cancel()
        firstFrameWatchdog = null
        currentQuality = choice
        selectedQuality = if (automaticQuality) "Auto · ${choice.label}" else choice.label
        lastQualityChangeTime = android.os.SystemClock.elapsedRealtime()
        lastSyncSeekAt = lastQualityChangeTime
        firstVideoAlignPending = true
        lastObservedAudioAt = -1L
        lastObservedClockAt = 0L
        lastReadyTime = lastQualityChangeTime
        bufferingSince = 0L
        hadReadyFrame = false
        isLoading = true
        error = null
        // Re-preparation affects VIDEO ONLY. SongPlayer's soundtrack, position,
        // crossfade and MediaSession are never touched.
        p.setMediaItem(MediaItem.Builder().setUri(choice.url)
            .setMimeType(choice.mimeType).build(), SongPlayer.getCurrentPosition().coerceAtLeast(0L))
        p.prepare()
        p.playWhenReady = SongPlayer.isPlaying()
    }

    /** Called only for the optional video clock. Never seek the audio player. */
    private fun alignVideoWithAudio(p: ExoPlayer, initial: Boolean = false) {
        if (p.playbackState == Player.STATE_IDLE || p.playbackState == Player.STATE_ENDED) return
        val audioAt = SongPlayer.getCurrentPosition().coerceAtLeast(0L)
        val drift = audioAt - p.currentPosition
        val audioPlaying = SongPlayer.isPlaying()
        val shouldPlay = audioPlaying &&
            (isForeground || PodcastVideoPipState.isInPictureInPicture || enteringPictureInPicture)
        if (p.playWhenReady != shouldPlay) p.playWhenReady = shouldPlay
        if (p.playbackState != Player.STATE_READY) return
        val now = android.os.SystemClock.elapsedRealtime()
        // Compare the podcast clock with elapsed real time. A sudden jump means
        // the user sought in the AUDIO player. This is when a VIDEO seek is
        // warranted; ordinary streaming lag is not a reason to thrash keyframes.
        val wallDelta = if (lastObservedClockAt == 0L) 0L else (now - lastObservedClockAt).coerceAtLeast(0L)
        val audioDelta = if (lastObservedAudioAt < 0L) 0L else audioAt - lastObservedAudioAt
        val explicitAudioSeek = lastObservedAudioAt >= 0L && wallDelta in 1L..3_000L &&
            (audioDelta < -1_000L || audioDelta > wallDelta + 2_000L)
        lastObservedAudioAt = audioAt
        lastObservedClockAt = now

        // READY can fire before PlayerView has an attached Surface. Preserve
        // initial catch-up until the first *visible* frame can be rendered.
        val initialCatchUp = firstVideoAlignPending && visibleTargets.isNotEmpty() &&
            abs(drift) > 650L
        if (firstVideoAlignPending && visibleTargets.isNotEmpty()) {
            firstVideoAlignPending = false
        }

        // Hard seek only for explicit user seeks or massive position drift (>5s)
        val mayRecover = shouldPlay && now - lastSyncSeekAt > 20_000L &&
            now - lastReadyTime > 3_000L && abs(drift) > 5_000L &&
            p.playbackState == Player.STATE_READY
        if (shouldPlay && (initialCatchUp || explicitAudioSeek || mayRecover)) {
            lastSyncSeekAt = now
            p.seekTo(audioAt)
        } else if (shouldPlay && p.playbackState == Player.STATE_READY) {
            // Smooth micro-adjustments via playback speed avoid decoder resets and keyframe stalls
            val targetSpeed = when {
                drift > 300L -> 1.05f  // Video is behind audio: speed up slightly
                drift < -300L -> 0.95f // Video is ahead of audio: slow down slightly
                else -> 1.0f           // In sync
            }
            if (p.playbackParameters.speed != targetSpeed) {
                p.playbackParameters = androidx.media3.common.PlaybackParameters(targetSpeed)
            }
        }
        if (now - lastSyncReportAt >= 10_000L) {
            lastSyncReportAt = now
            Log.d(tag, "video sync drift=${drift}ms playing=${p.isPlaying} " +
                "state=${p.playbackState} quality=${currentQuality?.label} " +
                "fps=${currentQuality?.frameRate} buffered=${p.totalBufferedDuration}ms")
        }
    }

    private fun startVideoClock(videoId: String) {
        videoClockJob?.cancel()
        videoClockJob = scope.launch {
            var pendingAudioMs = 0L
            while (activeVideoId == videoId) {
                val p = player ?: break
                if (!SongPlayer.isPodcastEpisodeLoaded(videoId)) {
                    // A tap made while the same episode is still resolving must
                    // not destroy the visual player before the audio starts.
                    if (SongPlayer.isPreparingTrack() && pendingAudioMs < 20_000L) {
                        pendingAudioMs += 250L
                        delay(250L)
                        continue
                    }
                    stop()
                    break
                }
                pendingAudioMs = 0L
                alignVideoWithAudio(p)
                if (!hadReadyFrame && p.playbackState == Player.STATE_READY) {
                    watchForFirstFrame(videoId, p)
                }
                adjustAutomaticQuality(p)
                delay(250L)
            }
        }
    }

    private fun adjustAutomaticQuality(p: ExoPlayer) {
        if (adaptiveActive) return // Media3 handles DASH adaptation automatically.
        if (!automaticQuality || activeVideoId == null || qualities.size < 2) return
        val now = android.os.SystemClock.elapsedRealtime()
        if (now - lastQualityChangeTime < 12_000L) return
        val active = currentQuality ?: return
        // Drop one level after a sustained rebuffer. Initial startup buffering
        // does not count; prevent loops when the CDN simply rejects a URL.
        if (hadReadyFrame && bufferingSince > 0 && now - bufferingSince > 1_500L) {
            val lower = qualities.filter { it.height < active.height && it.url !in failedUrls }
                .maxByOrNull { it.height }
            if (lower != null) { switchVisualQuality(lower); return }
        }
        // Video is delivered as one progressive URL per quality, not a DASH
        // adaptation set. An automatic upshift requires setMediaItem+prepare:
        // that stops video frames. Prefer a stable automatically chosen start
        // resolution, then downshift only when sustained buffering is observed.
        // Higher resolution remains available through the explicit user selector.
    }

    /** Manual quality remains fixed until the user selects Auto again. */
    fun selectQuality(height: Int) {
        val target = qualities.firstOrNull { it.height == height } ?: return
        automaticQuality = false
        failedUrls.remove(target.url)
        switchVisualQuality(target)
    }

    fun selectAutomaticQuality() {
        automaticQuality = true
        currentQuality?.let { selectedQuality = "Auto · ${it.label}" }
        lastQualityChangeTime = 0L
    }

    fun stopIfDifferent(currentPodcastId: String?) {
        if (warmedVideoId != null && warmedVideoId != currentPodcastId) releaseWarmVideo()
        if (activeVideoId != null && activeVideoId != currentPodcastId) {
            stop()
            releaseRetained()
        }
        if (retainedVideoId != null && retainedVideoId != currentPodcastId) releaseRetained()
    }

    fun stop() {
        resolveJob?.cancel(); resolveJob = null
        firstFrameWatchdog?.cancel(); firstFrameWatchdog = null
        videoClockJob?.cancel(); videoClockJob = null
        surfaceHandoffJob?.cancel(); surfaceHandoffJob = null
        attachedView?.player = null
        attachedView = null
        visibleTargets.clear()
        val p = player
        // Retain the prepared VIDEO ONLY for a brief audio/video toggle back.
        // No audio decoder or podcast MediaSource is retained here.
        if (p != null) {
            p.playWhenReady = false
            releaseRetained()
            retainedPlayer = p
            retainedVideoId = activeVideoId
            retainedQuality = currentQuality
            retainedAdaptive = adaptiveActive
            retainedQualities = qualities
            retainedReleaseJob = scope.launch {
                delay(90_000L)
                if (retainedPlayer === p) releaseRetained()
            }
        }
        player = null
        activeVideoId = null
        videoVisible = false
        fullscreen = false
        isLoading = false
        error = null
        qualities = emptyList()
        selectedQuality = ""
        currentQuality = null
        adaptiveActive = false
        adaptiveManifest = null
        appContext = null
        enteringPictureInPicture = false
    }

    private fun releaseRetained() {
        retainedReleaseJob?.cancel(); retainedReleaseJob = null
        retainedPlayer?.release(); retainedPlayer = null
        retainedVideoId = null
        retainedQuality = null
        retainedQualities = emptyList()
    }

    fun sync(foreground: Boolean) {
        isForeground = foreground
        // The soundtrack is deliberately not controlled from this function.
    }

    fun attach(view: PlayerView, priority: Int) {
        visibleTargets[view] = priority
        surfaceHandoffJob?.cancel()
        updateSurface()
    }

    fun detach(view: PlayerView) {
        visibleTargets.remove(view)
        if (view !== attachedView) { updateSurface(); return }
        surfaceHandoffJob?.cancel()
        if (visibleTargets.isNotEmpty()) updateSurface()
        else surfaceHandoffJob = scope.launch { delay(220L); updateSurface() }
    }

    private fun updateSurface() {
        val target = visibleTargets.entries.maxByOrNull { it.value }?.key
        val p = player ?: return
        if (target !== attachedView) {
            PlayerView.switchTargetView(p, attachedView, target)
            attachedView = target
        }
        // A ready player may have waited for this surface. Perform its single
        // initial alignment now; never seek the actual podcast soundtrack.
        if (target != null && firstVideoAlignPending && p.playbackState == Player.STATE_READY) {
            alignVideoWithAudio(p, initial = true)
        }
        if (target != null && !hadReadyFrame && p.playbackState == Player.STATE_READY) {
            activeVideoId?.let { watchForFirstFrame(it, p) }
        }
        // Video frame lifecycle has no effect on SongPlayer or audio focus.
    }
}

/** Display surface only: creating or destroying this composable never restarts the video. */
@OptIn(UnstableApi::class)
@Composable
private fun VideoSurface(modifier: Modifier = Modifier, priority: Int = 1) {
    val context = LocalContext.current
    val player = PodcastVideoSession.player
    if (player == null) {
        Box(modifier.background(Color.Black), contentAlignment = Alignment.Center) {
            CircularProgressIndicator(color = Color.White)
        }
        return
    }
    val view = remember(context) {
        (LayoutInflater.from(context).inflate(R.layout.spotui_podcast_video_view, null, false) as PlayerView).apply {
            useController = false
            resizeMode = AspectRatioFrameLayout.RESIZE_MODE_FIT
            setShowBuffering(PlayerView.SHOW_BUFFERING_NEVER)
            setArtworkDisplayMode(PlayerView.ARTWORK_DISPLAY_MODE_OFF)
            setBackgroundColor(android.graphics.Color.TRANSPARENT)
        }
    }
    // AndroidView must attach to its parent *before* Media3 receives its
    // Surface/TextureView. Otherwise ExoPlayer can render into a dead surface.
    AndroidView(
        factory = { view },
        modifier = modifier.background(Color.Black),
        update = { PodcastVideoSession.attach(it, priority) },
        onRelease = { PodcastVideoSession.detach(it) }
    )
}

/** Tiny persistent TextureView: keeps the same video-only decoder attached
 * while the artwork is shown. This is intentionally at the app root, not in
 * the mini/full player, so switching layouts cannot start duplicate loads. */
@Composable
fun PodcastStandbyVideoSurface(videoId: String?) {
    if (videoId == null || PodcastVideoSession.activeVideoId != videoId ||
        PodcastVideoSession.player == null) return
    Box(Modifier.size(8.dp).graphicsLayer { alpha = 0.01f }) {
        VideoSurface(Modifier.fillMaxSize(), priority = -10)
    }
}

@Composable
fun PodcastVideoFrame(
    videoId: String,
    modifier: Modifier = Modifier,
    onUseAudio: () -> Unit,
    compact: Boolean = false,
    surfacePriority: Int = 1,
) {
    val error = PodcastVideoSession.error
    val active = PodcastVideoSession.isActive(videoId)
    Box(modifier.background(Color.Black), contentAlignment = Alignment.Center) {
        when {
            !active -> Unit
            error != null -> {
                if (!compact) Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Text(error, color = Color.White, modifier = Modifier.padding(12.dp))
                    TextButton(onClick = onUseAudio) { Text(uiLocalized("Continuar solo con audio")) }
                }
            }
            PodcastVideoSession.fullscreen || PodcastVideoSession.enteringPictureInPicture -> Unit
            else -> VideoSurface(
                Modifier.fillMaxSize(), priority = if (compact) 0 else surfacePriority
            )
        }
        if (active && !compact && error == null && !PodcastVideoSession.fullscreen &&
            PodcastVideoSession.isLoading) {
            CircularProgressIndicator(
                color = Color.White,
                modifier = Modifier.align(Alignment.Center).size(30.dp)
            )
        }
        if (active && !compact && error == null && !PodcastVideoSession.fullscreen) {
            IconButton(
                onClick = { PodcastVideoSession.fullscreen = true },
                modifier = Modifier.align(Alignment.BottomEnd)
                    .background(Color.Black.copy(alpha = 0.50f), RoundedCornerShape(12.dp))
            ) {
                Icon(Icons.Default.Fullscreen,
                    contentDescription = uiLocalized("Vídeo a pantalla completa"), tint = Color.White)
            }
        }
    }
}

/** Hosted ONCE at the activity root, not inside a portrait/landscape player branch.
 * Recomposition on rotation cannot close or relaunch this fullscreen player. */
@Composable
fun PodcastFullscreenOverlay() {
    val videoId = PodcastVideoSession.activeVideoId ?: return
    if (!PodcastVideoSession.fullscreen || PodcastVideoPipState.isInPictureInPicture) return

    val activity = LocalContext.current.spotuiActivity()
    var positionMs by remember(videoId) { mutableLongStateOf(0L) }
    var durationMs by remember(videoId) { mutableLongStateOf(0L) }
    var audioPlaying by remember(videoId) { mutableStateOf(false) }
    var controlsVisible by remember(videoId) { mutableStateOf(true) }
    var scrubbing by remember(videoId) { mutableStateOf(false) }
    var scrubFraction by remember(videoId) { mutableFloatStateOf(0f) }
    var qualityMenuOpen by remember(videoId) { mutableStateOf(false) }
    var interactionCount by remember(videoId) { mutableIntStateOf(0) }
    var tapDirection by remember(videoId) { mutableIntStateOf(0) }
    var tapSequence by remember(videoId) { mutableIntStateOf(0) }
    val glass = Color(0xFF24272B).copy(alpha = 0.76f)
    val lime = Color(0xFF1ED760)

    fun showControls() { controlsVisible = true; interactionCount++ }
    fun skip(ms: Long) {
        val current = SongPlayer.getCurrentPosition().coerceAtLeast(0L)
        val duration = SongPlayer.getDuration()
        val next = (current + ms).coerceAtLeast(0L)
        SongPlayer.seekTo(if (duration > 0) next.coerceAtMost(duration) else next)
        showControls()
    }

    LaunchedEffect(controlsVisible, interactionCount, scrubbing, qualityMenuOpen) {
        if (controlsVisible && !scrubbing && !qualityMenuOpen) {
            delay(3_500L)
            controlsVisible = false
        }
    }
    LaunchedEffect(tapSequence) {
        if (tapSequence > 0) {
            delay(700L)
            tapDirection = 0
        }
    }
    LaunchedEffect(videoId) {
        while (PodcastVideoSession.isActive(videoId) && PodcastVideoSession.fullscreen) {
            positionMs = SongPlayer.getCurrentPosition().coerceAtLeast(0L)
            durationMs = SongPlayer.getDuration().coerceAtLeast(0L)
            audioPlaying = SongPlayer.isPlaying()
            delay(250L)
        }
    }

    Dialog(
        onDismissRequest = { PodcastVideoSession.fullscreen = false },
        properties = DialogProperties(
            usePlatformDefaultWidth = false,
            decorFitsSystemWindows = false
        )
    ) {
        // Fullscreen is restricted to this dialog. Restore app system bars on exit.
        val view = androidx.compose.ui.platform.LocalView.current
        androidx.compose.runtime.DisposableEffect(view, activity) {
            // The DialogWindowProvider may be several parents above LocalView.
            var dialogWindow: android.view.Window? = null
            var node: android.view.View? = view
            while (node != null && dialogWindow == null) {
                if (node is androidx.compose.ui.window.DialogWindowProvider) {
                    dialogWindow = node.window
                    break
                }
                val parent = node.parent
                if (parent is androidx.compose.ui.window.DialogWindowProvider) {
                    dialogWindow = parent.window
                    break
                }
                node = parent as? android.view.View
            }
            dialogWindow?.addFlags(android.view.WindowManager.LayoutParams.FLAG_FULLSCREEN)
            dialogWindow?.let { androidx.core.view.WindowCompat.setDecorFitsSystemWindows(it, false) }
            activity?.let {
                androidx.core.view.WindowInsetsControllerCompat(it.window, it.window.decorView)
                    .hide(androidx.core.view.WindowInsetsCompat.Type.statusBars())
            }
            onDispose {
                dialogWindow?.clearFlags(android.view.WindowManager.LayoutParams.FLAG_FULLSCREEN)
                activity?.let {
                    androidx.core.view.WindowInsetsControllerCompat(it.window, it.window.decorView)
                        .show(androidx.core.view.WindowInsetsCompat.Type.statusBars())
                }
            }
        }

        Box(Modifier.fillMaxSize().background(Color.Black)) {
            // Media3's FIT resize mode maximizes 16:9 inside any orientation
            // without cropping; no safeDrawingPadding shrinks the video image.
            VideoSurface(Modifier.fillMaxSize(), priority = 3)

            // The transparent gesture layer sits ABOVE AndroidView to receive
            // single and double taps. Buttons and slider are on top of it.
            Box(
                Modifier.fillMaxSize().pointerInput(videoId) {
                    detectTapGestures(
                        onTap = {
                            controlsVisible = !controlsVisible
                            interactionCount++
                        },
                        onDoubleTap = { offset ->
                            val backwards = offset.x < size.width / 2f
                            skip(if (backwards) -15_000L else 15_000L)
                            tapDirection = if (backwards) -1 else 1
                            tapSequence++
                        }
                    )
                }
            )

            AnimatedVisibility(
                visible = tapDirection != 0,
                enter = fadeIn(tween(130)), exit = fadeOut(tween(270)),
                modifier = Modifier.align(
                    if (tapDirection < 0) Alignment.CenterStart else Alignment.CenterEnd
                )
            ) {
                Row(
                    modifier = Modifier.padding(horizontal = 24.dp)
                        .background(glass, RoundedCornerShape(32.dp))
                        .padding(horizontal = 19.dp, vertical = 13.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    com.music.spotui.ui.components.SeekFifteenIcon(
                        backwards = tapDirection < 0, modifier = Modifier.size(27.dp), tint = lime
                    )
                    Spacer(Modifier.width(6.dp))
                    Text("15 s", color = Color.White, fontWeight = FontWeight.SemiBold)
                }
            }

            AnimatedVisibility(
                visible = controlsVisible,
                enter = fadeIn(tween(180)), exit = fadeOut(tween(200)),
                modifier = Modifier.fillMaxSize()
            ) {
                Box(Modifier.fillMaxSize()) {
                    // Readable floating controls, not an opaque controls panel.
                    Box(
                        Modifier.fillMaxWidth().height(110.dp)
                            .background(Brush.verticalGradient(
                                listOf(Color.Black.copy(alpha = .57f), Color.Transparent)
                            ))
                            .align(Alignment.TopCenter)
                    )
                    Row(
                        Modifier.align(Alignment.TopEnd)
                            .padding(top = 14.dp, end = 16.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(10.dp)
                    ) {
                        Box {
                            IconButton(
                                onClick = { qualityMenuOpen = true; showControls() },
                                modifier = Modifier.background(glass, CircleShape)
                            ) {
                                Icon(Icons.Default.Settings, "Calidad de vídeo", tint = Color.White)
                            }
                            DropdownMenu(
                                expanded = qualityMenuOpen,
                                onDismissRequest = { qualityMenuOpen = false },
                                modifier = Modifier.background(Color(0xFF24272B))
                            ) {
                                DropdownMenuItem(
                                    text = { Text(
                                        "Automática" + if (PodcastVideoSession.automaticQuality) " ✓" else "",
                                        color = Color.White
                                    ) },
                                    onClick = {
                                        PodcastVideoSession.selectAutomaticQuality()
                                        qualityMenuOpen = false
                                        showControls()
                                    }
                                )
                                PodcastVideoSession.qualities.forEach { quality ->
                                    DropdownMenuItem(
                                        text = {
                                            Text(
                                                quality.label + if (!PodcastVideoSession.automaticQuality && quality.label == PodcastVideoSession.selectedQuality) " ✓" else "",
                                                color = Color.White
                                            )
                                        },
                                        onClick = {
                                            PodcastVideoSession.selectQuality(quality.height)
                                            qualityMenuOpen = false
                                            showControls()
                                        }
                                    )
                                }
                            }
                        }
                        if (activity != null && PodcastVideoSession.player != null) {
                            IconButton(
                                onClick = {
                                    activity.enterPodcastPictureInPicture()
                                },
                                modifier = Modifier.background(glass, CircleShape)
                            ) {
                                Icon(Icons.Default.PictureInPictureAlt,
                                    contentDescription = "Picture-in-Picture", tint = Color.White)
                            }
                        }
                        IconButton(
                            onClick = { PodcastVideoSession.fullscreen = false },
                            modifier = Modifier.background(glass, CircleShape)
                        ) {
                            Icon(Icons.Default.Close,
                                contentDescription = "Salir de pantalla completa", tint = Color.White)
                        }
                    }

                    Row(
                        Modifier.align(Alignment.Center)
                            .padding(horizontal = 20.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(26.dp)
                    ) {
                        IconButton(onClick = { skip(-15_000L) },
                            modifier = Modifier.size(58.dp).background(glass, CircleShape)) {
                            com.music.spotui.ui.components.SeekFifteenIcon(
                                backwards = true, modifier = Modifier.size(30.dp)
                            )
                        }
                        IconButton(
                            onClick = { SongPlayer.togglePlay(); showControls() },
                            modifier = Modifier.size(74.dp)
                                .background(Color.White.copy(alpha = 0.94f), CircleShape)
                        ) {
                            Icon(
                                if (audioPlaying) Icons.Default.Pause else Icons.Default.PlayArrow,
                                contentDescription = if (audioPlaying) "Pausar" else "Reproducir",
                                tint = Color.Black, modifier = Modifier.size(38.dp)
                            )
                        }
                        IconButton(onClick = { skip(15_000L) },
                            modifier = Modifier.size(58.dp).background(glass, CircleShape)) {
                            com.music.spotui.ui.components.SeekFifteenIcon(
                                backwards = false, modifier = Modifier.size(30.dp)
                            )
                        }
                    }

                    Column(
                        Modifier.align(Alignment.BottomCenter)
                            .fillMaxWidth()
                            .background(Brush.verticalGradient(
                                listOf(Color.Transparent, Color.Black.copy(alpha = .75f))
                            ))
                            .navigationBarsPadding()
                            .padding(start = 22.dp, end = 22.dp, top = 18.dp, bottom = 12.dp)
                    ) {
                        val validDuration = durationMs > 0L
                        val fraction = if (scrubbing) scrubFraction else if (validDuration)
                            (positionMs.toFloat() / durationMs.toFloat()).coerceIn(0f, 1f) else 0f
                        Slider(
                            value = fraction,
                            onValueChange = { scrubFraction = it; scrubbing = true; showControls() },
                            onValueChangeFinished = {
                                if (validDuration) SongPlayer.seekTo((scrubFraction * durationMs).toLong())
                                scrubbing = false
                                showControls()
                            },
                            enabled = validDuration,
                            modifier = Modifier.fillMaxWidth().height(32.dp),
                            colors = androidx.compose.material3.SliderDefaults.colors(
                                thumbColor = lime, activeTrackColor = lime,
                                inactiveTrackColor = Color.White.copy(alpha = .35f)
                            )
                        )
                        Row(
                            Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween
                        ) {
                            Text(formatPodcastTime(if (scrubbing) (scrubFraction * durationMs).toLong() else positionMs),
                                color = Color.White, fontSize = 12.sp)
                            Text(formatPodcastTime(durationMs),
                                color = Color.White.copy(alpha = .8f), fontSize = 12.sp)
                        }
                    }
                }
            }
        }
    }
}

private fun formatPodcastTime(milliseconds: Long): String {
    val seconds = milliseconds.coerceAtLeast(0L) / 1000L
    val hours = seconds / 3600L
    return if (hours > 0L) String.format(Locale.ROOT, "%d:%02d:%02d", hours, (seconds / 60L) % 60L, seconds % 60L)
    else String.format(Locale.ROOT, "%d:%02d", seconds / 60L, seconds % 60L)
}
