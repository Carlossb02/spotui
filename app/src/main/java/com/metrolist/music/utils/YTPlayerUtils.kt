/**
 * Metrolist Project (C) 2026
 * Licensed under GPL-3.0 | See git history for contributors
 */

package com.metrolist.music.utils

import android.content.Context
import android.net.ConnectivityManager
import android.net.Uri
import android.util.Log
import androidx.media3.common.PlaybackException
import com.metrolist.innertube.NewPipeExtractor
import com.metrolist.innertube.YouTube
import com.metrolist.innertube.models.YouTubeClient
import com.metrolist.innertube.models.YouTubeClient.Companion.ANDROID_CREATOR
import com.metrolist.innertube.models.YouTubeClient.Companion.ANDROID_NO_SDK
import com.metrolist.innertube.models.YouTubeClient.Companion.ANDROID_VR_1_43_32
import com.metrolist.innertube.models.YouTubeClient.Companion.ANDROID_VR_1_61_48
import com.metrolist.innertube.models.YouTubeClient.Companion.ANDROID_VR_NO_AUTH
import com.metrolist.innertube.models.YouTubeClient.Companion.IOS
import com.metrolist.innertube.models.YouTubeClient.Companion.IPADOS
import com.metrolist.innertube.models.YouTubeClient.Companion.MOBILE
import com.metrolist.innertube.models.YouTubeClient.Companion.TVHTML5
import com.metrolist.innertube.models.YouTubeClient.Companion.TVHTML5_SIMPLY_EMBEDDED_PLAYER
import com.metrolist.innertube.models.YouTubeClient.Companion.WEB
import com.metrolist.innertube.models.YouTubeClient.Companion.WEB_CREATOR
import com.metrolist.innertube.models.YouTubeClient.Companion.WEB_REMIX
import com.metrolist.innertube.models.response.PlayerResponse
import com.metrolist.music.constants.AudioQuality
import com.metrolist.music.utils.cipher.CipherDeobfuscator
import com.metrolist.music.utils.YTPlayerUtils.MAIN_CLIENT
import com.metrolist.music.utils.YTPlayerUtils.STREAM_FALLBACK_CLIENTS
import com.metrolist.music.utils.YTPlayerUtils.validateStatus
import com.metrolist.music.utils.potoken.PoTokenGenerator
import com.metrolist.music.utils.potoken.PoTokenResult
import com.metrolist.music.utils.sabr.EjsNTransformSolver
import kotlinx.coroutines.DelicateCoroutinesApi
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.GlobalScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.cancel
import kotlinx.coroutines.Deferred
import okhttp3.OkHttpClient
import timber.log.Timber
import java.util.concurrent.TimeUnit

object YTPlayerUtils {
    private const val logTag = "YTPlayerUtils"
    private const val TAG = "YTPlayerUtils"

    /**
     * Verbose audio-track diagnostics. Use Log.e so Android Studio Logcat shows
     * these entries in red. Do not log stream URLs, cookies, PoTokens, or signatures.
     */
    private const val VERBOSE_AUDIO_DIAGNOSTICS = false
    private fun audioDebug(message: String) {
        // Hundreds of per-format messages per cold track were emitted at ERROR
        // priority. Keep summaries and failures without flooding Logcat/CPU.
        if (VERBOSE_AUDIO_DIAGNOSTICS || message.startsWith("FINAL ") ||
            message.startsWith("ALL CLIENTS") || message.startsWith("NO_TRACK_SAFE_URL")) {
            Log.d("YT_AUDIO_DEBUG", message)
        }
    }

    private fun safeAudioUrlInfo(url: String?): String {
        if (url.isNullOrBlank()) return "url=null"
        return try {
            val uri = Uri.parse(url)
            val queryKeys = uri.queryParameterNames.sorted()
            val safeParams = listOf("itag", "mime", "lang", "xtags", "alr")
                .mapNotNull { key ->
                    uri.getQueryParameter(key)?.let { value ->
                        // Keep language/itag hints, but never expose signatures, tokens, or full URLs.
                        "$key=${value.take(160)}"
                    }
                }
                .joinToString(",")
            "host=${uri.host} path=${uri.path} queryKeys=$queryKeys hints={$safeParams}"
        } catch (e: Exception) {
            "urlParseError=${e.javaClass.simpleName}"
        }
    }

    /**
     * Returns an explicit language hint embedded in a Googlevideo URL, if any.
     * This is only a validation hint: absence of a hint is not proof of language.
     */
    private fun audioUrlLanguageHint(url: String?): String? {
        if (url.isNullOrBlank()) return null
        return try {
            val uri = Uri.parse(url)
            val directLang = uri.getQueryParameter("lang")
            val xtags = uri.getQueryParameter("xtags").orEmpty()
            // YouTube commonly emits regional tags such as lang=es-US in xtags.
            // Capture the full tag, then compare its base language (es) to trackLang.
            // Region subtags in YouTube xtags are often uppercase (for example,
            // `lang=es-US`). The old case-sensitive regex failed to recognize those
            // values, so a valid Spanish target URL was treated as having no language
            // hint and the code kept the NewPipe URL that pointed to Arabic.
            val taggedLang = Regex(
                """(?:^|[:&;,])lang=([a-z]{2,3}(?:-[a-z]{2,3})?)(?:$|[:&;,])""",
                RegexOption.IGNORE_CASE,
            ).find(xtags)?.groupValues?.getOrNull(1)
            (directLang ?: taggedLang)?.substringBefore('-')?.lowercase()
        } catch (_: Exception) {
            null
        }
    }

    /**
     * Returns the audio content type embedded in a Googlevideo URL's xtags
     * (`acont=original`, `acont=dubbed`, `acont=dubbed-auto`, `acont=descriptive`).
     * This is far more reliable than the localized track display name.
     */
    private fun audioUrlContentHint(url: String?): String? {
        if (url.isNullOrBlank()) return null
        return try {
            val xtags = Uri.parse(url).getQueryParameter("xtags").orEmpty()
            Regex("""(?:^|[:&;,])acont=([a-z\-]+)""", RegexOption.IGNORE_CASE)
                .find(xtags)?.groupValues?.getOrNull(1)?.lowercase()
        } catch (_: Exception) {
            null
        }
    }

    // Language and "original" are independent properties. A Spanish dub is not
    // necessarily the original soundtrack, nor is every "original" Spanish.
    private fun isOriginalTrack(f: PlayerResponse.StreamingData.Format): Boolean {
        when (audioUrlContentHint(f.url)) {
            "original" -> return true
            "dubbed", "dubbed-auto", "descriptive" -> return false
        }
        if (f.isOriginal) return true
        val name = f.audioTrack?.displayName.orEmpty()
        val id = f.audioTrack?.id.orEmpty()
        return name.contains("original", ignoreCase = true) ||
                id.contains("original", ignoreCase = true)
    }

    private fun dumpAudioFormats(stage: String, response: PlayerResponse?) {
        val formats = response?.streamingData?.adaptiveFormats.orEmpty().filter { it.isAudio }
        audioDebug("===== $stage | videoId=${response?.videoDetails?.videoId} | title=${response?.videoDetails?.title} | status=${response?.playabilityStatus?.status} | audioFormats=${formats.size} =====")
        formats.forEachIndexed { index, f ->
            val track = f.audioTrack
            audioDebug(
                "[$stage][$index] itag=${f.itag} mime=${f.mimeType} bitrate=${f.bitrate} " +
                        "isOriginal=${f.isOriginal} trackId=${track?.id} trackName=${track?.displayName} " +
                        "trackLang=${trackLang(f)} dubbed=${isDubbedTrack(f)} contentHint=${audioUrlContentHint(f.url)} urlPresent=${!f.url.isNullOrEmpty()} " +
                        "signatureCipherPresent=${!f.signatureCipher.isNullOrEmpty()} cipherPresent=${!f.cipher.isNullOrEmpty()}"
            )
        }
        if (formats.isEmpty()) audioDebug("[$stage] No audio adaptive formats or response is null")
    }
    /** Max seconds to wait for signature-timestamp resolution before giving up. */
    private const val SIG_FUTURE_TIMEOUT_SEC = 5L
    /** Max seconds to wait for PoToken generation before giving up. */
    private const val POT_FUTURE_TIMEOUT_SEC = 5L

    private val httpClient = OkHttpClient.Builder()
        .proxy(YouTube.proxy)
        .connectTimeout(20, TimeUnit.SECONDS)
        .readTimeout(20, TimeUnit.SECONDS)
        .writeTimeout(20, TimeUnit.SECONDS)
        .retryOnConnectionFailure(true)
        .build()

    private val poTokenGenerator = PoTokenGenerator()

    // A signature timestamp comes from YouTube's player script, not from the song.
    // Reuse ONLY successful timestamps for a short period. Failures stay video-specific
    // (important for age restrictions); a refreshed script is fetched after TTL.
    @Volatile private var recentSignatureTimestamp: Pair<Long, Int>? = null
    private const val SIGNATURE_TIMESTAMP_TTL_MS = 5 * 60_000L
    private val signatureTimestampLock = Any()

    // Resolving several fallback recordings simultaneously must not launch
    // several identical NewPipe player-script downloads on a cold start.
    // Only verified non-age-restricted timestamps are shared; failures and
    // age-restricted responses retain their individual resolution behavior.
    private fun signatureTimestampFast(videoId: String): SignatureTimestampResult =
        synchronized(signatureTimestampLock) {
            val now = System.currentTimeMillis()
            recentSignatureTimestamp?.let { (savedAt, timestamp) ->
                if (now >= savedAt && now - savedAt < SIGNATURE_TIMESTAMP_TTL_MS) {
                    return@synchronized SignatureTimestampResult(timestamp, isAgeRestricted = false)
                }
            }
            val result = getSignatureTimestampOrNull(videoId)
            if (result.timestamp != null && !result.isAgeRestricted) {
                recentSignatureTimestamp = System.currentTimeMillis() to result.timestamp
            }
            result
        }

    private val MAIN_CLIENT: YouTubeClient = WEB_REMIX

    private val STREAM_FALLBACK_CLIENTS: Array<YouTubeClient> = arrayOf(
        ANDROID_VR_NO_AUTH,
        TVHTML5_SIMPLY_EMBEDDED_PLAYER,
        ANDROID_NO_SDK,
        WEB,
        IOS,
        IPADOS,
        MOBILE,
        TVHTML5,
        ANDROID_VR_1_43_32,
        ANDROID_VR_1_61_48,
        ANDROID_CREATOR,
        WEB_CREATOR
    )
    // The audio resolver has already fetched and validated an Innertube player
    // response for this exact YouTube ID. Reuse its *video-format metadata* for
    // podcasts instead of doing another multi-client player lookup on Video tap.
    // This is strictly a side cache: it does not change audio selection or the
    // validation/priority of any stream provider.
    private val recentVideoResponses = java.util.concurrent.ConcurrentHashMap<
        String, Pair<Long, PlayerResponse>>()
    private const val VIDEO_RESPONSE_TTL_MS = 90_000L

    fun recentVideoResponse(videoId: String): PlayerResponse? {
        val saved = recentVideoResponses[videoId] ?: return null
        val age = android.os.SystemClock.elapsedRealtime() - saved.first
        return if (age in 0L until VIDEO_RESPONSE_TTL_MS) saved.second else {
            recentVideoResponses.remove(videoId, saved)
            null
        }
    }

    fun invalidateRecentVideoResponse(videoId: String) {
        recentVideoResponses.remove(videoId)
    }

    private fun rememberVerifiedVideoResponse(videoId: String, response: PlayerResponse) {
        if (response.playabilityStatus.status != "OK" ||
            response.videoDetails?.videoId != videoId ||
            response.streamingData?.adaptiveFormats.orEmpty().none { fmt ->
                fmt.height != null && fmt.mimeType.startsWith("video/") &&
                    fmt.url?.startsWith("https://") == true
            }) return
        val now = android.os.SystemClock.elapsedRealtime()
        if (recentVideoResponses.size >= 16) {
            recentVideoResponses.entries.removeIf {
                now - it.value.first !in 0L until VIDEO_RESPONSE_TTL_MS
            }
            if (recentVideoResponses.size >= 16) {
                recentVideoResponses.entries.minByOrNull { it.value.first }?.let {
                    recentVideoResponses.remove(it.key, it.value)
                }
            }
        }
        recentVideoResponses[videoId] = now to response
    }

    data class PlaybackData(
        val audioConfig: PlayerResponse.PlayerConfig.AudioConfig?,
        val videoDetails: PlayerResponse.VideoDetails?,
        val playbackTracking: PlayerResponse.PlaybackTracking?,
        val format: PlayerResponse.StreamingData.Format,
        val streamUrl: String,
        val streamExpiresInSeconds: Int,
    )
    /**
     * Custom player response intended to use for playback.
     * Metadata like audioConfig and videoDetails are from [MAIN_CLIENT].
     * Format & stream can be from [MAIN_CLIENT] or [STREAM_FALLBACK_CLIENTS].
     */
    suspend fun playerResponseForPlayback(
        videoId: String,
        playlistId: String? = null,
        audioQuality: AudioQuality,
        connectivityManager: ConnectivityManager,
        skipValidation: Boolean = false,
    ): Result<PlaybackData> = runCatching {
        val streamStartedAt = android.os.SystemClock.elapsedRealtime()
        Timber.tag(TAG).d("=== PLAYER RESPONSE FOR PLAYBACK ===")
        Timber.tag(TAG).d("videoId: $videoId")
        Timber.tag(TAG).d("playlistId: $playlistId")
        Timber.tag(TAG).d("audioQuality: $audioQuality")
        audioDebug("REQUEST videoId=$videoId playlistId=$playlistId audioQuality=$audioQuality deviceLocale=${java.util.Locale.getDefault()} isMetered=${connectivityManager.isActiveNetworkMetered}")

        // Check if this is an uploaded/privately owned track
        val isUploadedTrack = playlistId == "MLPT" || playlistId?.contains("MLPT") == true
        Timber.tag(TAG).d("Content type detection (preliminary):")
        Timber.tag(TAG).d("  isUploadedTrack (from playlistId): $isUploadedTrack")

        val isLoggedIn = YouTube.cookie != null
        Timber.tag(TAG).d("Authentication status: ${if (isLoggedIn) "LOGGED_IN" else "ANONYMOUS"}")

        // Run signature-timestamp and PoToken generation in parallel — they are
        // independent and each takes 1-3s, so overlapping them nearly halves the
        // cold-start latency. Both are blocking calls, so we use Java futures on
        // the IO executor rather than coroutine async (runCatching is non-suspend).
        val sessionId = if (isLoggedIn) YouTube.dataSyncId else YouTube.visitorData
        val mainClientNeedsPoToken = MAIN_CLIENT.useWebPoTokens

        val sigFuture = java.util.concurrent.CompletableFuture.supplyAsync {
            signatureTimestampFast(videoId)
        }
        val potFuture: java.util.concurrent.CompletableFuture<PoTokenResult?>? =
            if (mainClientNeedsPoToken && sessionId != null) {
                java.util.concurrent.CompletableFuture.supplyAsync {
                    Timber.tag(logTag).d("Generating PoToken for WEB_REMIX with sessionId")
                    try {
                        poTokenGenerator.getWebClientPoToken(videoId, sessionId).also {
                            if (it != null) Timber.tag(logTag).d("PoToken generated successfully")
                        }
                    } catch (e: Exception) {
                        Timber.tag(logTag).e(e, "PoToken generation failed: ${e.message}")
                        null
                    }
                }
            } else null

        // Both signature-timestamp and PoToken generation can hang indefinitely when
        // the app is in the background (WebView JS may never call back, or NewPipe
        // extraction may stall). Use bounded waits so the resolution pipeline can
        // still fall through to clients that don't require these tokens.
        val signatureTimestamp = try {
            sigFuture.get(800L, java.util.concurrent.TimeUnit.MILLISECONDS)
        } catch (e: Exception) {
            Timber.tag(logTag).w("Signature timestamp timed out or failed: ${e.message}")
            SignatureTimestampResult(null, isAgeRestricted = false)
        }
        Timber.tag(logTag).d("Signature timestamp: ${signatureTimestamp.timestamp}")
        var poToken: PoTokenResult? = try {
            potFuture?.get(200L, java.util.concurrent.TimeUnit.MILLISECONDS)
        } catch (e: Exception) {
            Timber.tag(logTag).w("PoToken timed out or failed: ${e.message}")
            null
        }

        Log.i("SpotuiStartup", "ytAuth cost=${android.os.SystemClock.elapsedRealtime() - streamStartedAt}ms " +
            "signature=${signatureTimestamp.timestamp != null} token=${poToken != null}")

        // If MAIN_CLIENT needs a PoToken but we couldn't get one (WebView missing, JS
        // blocked, network hostile), WEB_REMIX will return streams that 403 on play.
        // Skip it and go straight to the fallback chain.
        val skipMainClient = mainClientNeedsPoToken && poToken == null
        if (skipMainClient) {
            Timber.tag(TAG).w("PoToken unavailable — skipping MAIN_CLIENT and using fallback chain directly")
        }

        var mainPlayerResponse: PlayerResponse? = if (skipMainClient) null else {
            Timber.tag(logTag).d("Attempting to get player response using MAIN_CLIENT: ${MAIN_CLIENT.clientName}")
            YouTube.player(videoId, playlistId, MAIN_CLIENT, signatureTimestamp.timestamp, poToken?.playerRequestPoToken).getOrNull()
        }
        audioDebug("MAIN_CLIENT=${MAIN_CLIENT.clientName} skipMainClient=$skipMainClient signatureTimestamp=${signatureTimestamp.timestamp != null} poTokenPresent=${poToken != null}")
        dumpAudioFormats("MAIN_RESPONSE", mainPlayerResponse)

        // Debug uploaded track response
        if (mainPlayerResponse != null && (isUploadedTrack || playlistId?.contains("MLPT") == true)) {
            println("[PLAYBACK_DEBUG] Main player response status: ${mainPlayerResponse.playabilityStatus.status}")
            println("[PLAYBACK_DEBUG] Playability reason: ${mainPlayerResponse.playabilityStatus.reason}")
            println("[PLAYBACK_DEBUG] Video details: title=${mainPlayerResponse.videoDetails?.title}, videoId=${mainPlayerResponse.videoDetails?.videoId}")
            println("[PLAYBACK_DEBUG] Streaming data null? ${mainPlayerResponse.streamingData == null}")
            println("[PLAYBACK_DEBUG] Adaptive formats count: ${mainPlayerResponse.streamingData?.adaptiveFormats?.size ?: 0}")
        }

        var usedAgeRestrictedClient: YouTubeClient? = null
        val wasOriginallyAgeRestricted: Boolean

        val mainStatus = mainPlayerResponse?.playabilityStatus?.status
        val mainReason = mainPlayerResponse?.playabilityStatus?.reason.orEmpty()

        val isBotDetection = mainReason.contains("bot", ignoreCase = true) ||
                mainReason.contains("confirm you", ignoreCase = true) ||
                mainReason.contains("Sign in to confirm", ignoreCase = true) ||
                mainReason.contains("Log in to confirm", ignoreCase = true)

        if (isBotDetection) {
            Timber.tag(TAG).w("Bot detection triggered on MAIN_CLIENT (reason: $mainReason). Falling back to non-PoToken client chain.")
            // Asynchronously refresh visitorData so future sessions obtain a clean session token
            @OptIn(kotlinx.coroutines.DelicateCoroutinesApi::class)
            kotlinx.coroutines.GlobalScope.launch(kotlinx.coroutines.Dispatchers.IO) {
                runCatching { YouTube.visitorData = YouTube.visitorData().getOrNull() ?: YouTube.visitorData }
            }
        }

        // Only classify as age-restricted if it is NOT bot detection and has age check status / reason
        val isAgeRestrictedFromResponse = !isBotDetection && (
                mainStatus in listOf("AGE_CHECK_REQUIRED", "AGE_VERIFICATION_REQUIRED", "CONTENT_CHECK_REQUIRED") ||
                        (mainStatus == "LOGIN_REQUIRED" && (mainReason.contains("age", ignoreCase = true) || mainReason.contains("verify", ignoreCase = true)))
                )
        wasOriginallyAgeRestricted = isAgeRestrictedFromResponse

        if (isAgeRestrictedFromResponse && isLoggedIn) {
            // Age-restricted: use WEB_CREATOR directly (no NewPipe needed from here)
            Timber.tag(logTag).d("Age-restricted detected, using WEB_CREATOR")
            Timber.tag(TAG).i("Age-restricted: using WEB_CREATOR for videoId=$videoId")
            val creatorResponse = YouTube.player(videoId, playlistId, WEB_CREATOR, null, null).getOrNull()
            if (creatorResponse?.playabilityStatus?.status == "OK") {
                Timber.tag(logTag).d("WEB_CREATOR works for age-restricted content")
                mainPlayerResponse = creatorResponse
                usedAgeRestrictedClient = WEB_CREATOR
            }
        }

        var format: PlayerResponse.StreamingData.Format? = null
        var streamUrl: String? = null
        var streamExpiresInSeconds: Int? = null
        var streamPlayerResponse: PlayerResponse? = null
        var fallbackStreamUrl: String? = null
        var fallbackFormat: PlayerResponse.StreamingData.Format? = null
        var fallbackExpiresInSeconds: Int? = null
        val retryMainPlayerResponse: PlayerResponse? = if (usedAgeRestrictedClient != null) mainPlayerResponse else null

        // Check current status
        val isAgeRestricted = isAgeRestrictedFromResponse

        if (isAgeRestricted) {
            Timber.tag(logTag).d("Content is age-restricted (status: $mainStatus), will try fallback clients")
            Timber.tag(TAG).i("Age-restricted content detected: videoId=$videoId, status=$mainStatus")
        }

        // Check if this is a privately owned track (uploaded song)
        val isPrivateTrack = mainPlayerResponse?.videoDetails?.musicVideoType == "MUSIC_VIDEO_TYPE_PRIVATELY_OWNED_TRACK"

        // For private tracks: use TVHTML5 (index 1) with PoToken + n-transform
        // For age-restricted: skip main client, start with fallbacks
        // For normal content: standard order
        val startIndex = when {
            isPrivateTrack -> 1  // TVHTML5
            isAgeRestricted -> 0
            skipMainClient -> 0  // MAIN_CLIENT streams unplayable without PoToken
            mainPlayerResponse == null || mainPlayerResponse.playabilityStatus.status != "OK" -> 0
            else -> -1
        }

        // Request the next two fallback client responses concurrently, but
        // CONSUME them in the original order below. Prefetch is limited to two
        // pending clients and cancelled once a source has been selected.
        // This does not change the source/format/lang ranking or the fallback
        // behavior when a request fails.
        val fallbackScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        val prefetchedResponses = java.util.HashMap<Int, Deferred<PlayerResponse?>>()
        fun queueFallback(index: Int) {
            if (index !in STREAM_FALLBACK_CLIENTS.indices || index in prefetchedResponses) return
            val nextClient = STREAM_FALLBACK_CLIENTS[index]
            if (nextClient.loginRequired && !isLoggedIn && YouTube.cookie == null) return
            val clientPoToken = if (nextClient.useWebPoTokens) poToken?.playerRequestPoToken else null
            val clientSigTimestamp = if (wasOriginallyAgeRestricted) null else signatureTimestamp.timestamp
            prefetchedResponses[index] = fallbackScope.async {
                YouTube.player(videoId, playlistId, nextClient, clientSigTimestamp, clientPoToken).getOrNull()
            }
        }
        if (startIndex >= 0) {
            queueFallback(startIndex)
            queueFallback(startIndex + 1)
        }
        try {
        for (clientIndex in (startIndex until STREAM_FALLBACK_CLIENTS.size)) {
            // reset for each client
            format = null
            streamUrl = null
            streamExpiresInSeconds = null

            // decide which client to use for streams and load its player response
            val client: YouTubeClient
            if (clientIndex == -1) {
                // try with streams from main client first (use retry response if available)
                client = MAIN_CLIENT
                streamPlayerResponse = retryMainPlayerResponse ?: mainPlayerResponse
                Timber.tag(logTag).d("Trying stream from MAIN_CLIENT: ${client.clientName}")
            } else {
                // after main client use fallback clients
                client = STREAM_FALLBACK_CLIENTS[clientIndex]
                Timber.tag(logTag).d("Trying fallback client ${clientIndex + 1}/${STREAM_FALLBACK_CLIENTS.size}: ${client.clientName}")

                if (client.loginRequired && !isLoggedIn && YouTube.cookie == null) {
                    // skip client if it requires login but user is not logged in
                    Timber.tag(logTag).d("Skipping client ${client.clientName} - requires login but user is not logged in")
                    continue
                }

                Timber.tag(logTag).d("Fetching player response for fallback client: ${client.clientName}")
                audioDebug("TRY_CLIENT index=$clientIndex client=${client.clientName} loginRequired=${client.loginRequired} useWebPoTokens=${client.useWebPoTokens}")
                // Only pass poToken for clients that support it
                val clientPoToken = if (client.useWebPoTokens) poToken?.playerRequestPoToken else null
                // Skip signature timestamp for age-restricted (faster), use it for normal content
                val clientSigTimestamp = if (wasOriginallyAgeRestricted) null else signatureTimestamp.timestamp
                // Start fetching the following response while this one is
                // checked. No speculative request changes which client wins.
                queueFallback(clientIndex)
                queueFallback(clientIndex + 1)
                val startedClientAt = android.os.SystemClock.elapsedRealtime()
                val inProgress = prefetchedResponses.remove(clientIndex)
                streamPlayerResponse = if (inProgress != null) inProgress.await()
                    else YouTube.player(videoId, playlistId, client, clientSigTimestamp, clientPoToken).getOrNull()
                Log.i("SpotuiStartup", "ytClient[${clientIndex}] latency=${android.os.SystemClock.elapsedRealtime() - startedClientAt}ms " +
                    "status=${streamPlayerResponse?.playabilityStatus?.status}")
                dumpAudioFormats("CLIENT_${client.clientName}", streamPlayerResponse)
            }

            // YouTube content substitution guard: a client with a mismatched
            // session can return a playable response for a DIFFERENT video (the
            // classic "plays the wrong song" bug). Never accept streams whose
            // videoId doesn't match what we asked for.
            val returnedVideoId = streamPlayerResponse?.videoDetails?.videoId
            if (returnedVideoId != null && returnedVideoId != videoId) {
                Timber.tag(TAG).w(
                    "Client ${if (clientIndex == -1) MAIN_CLIENT.clientName else STREAM_FALLBACK_CLIENTS[clientIndex].clientName} " +
                            "returned WRONG video: $returnedVideoId != $videoId — skipping",
                )
                continue
            }

            // The returned ID has been checked above. Caching a verified
            // response has no influence on audio formats or the selected URL.
            streamPlayerResponse?.let { rememberVerifiedVideoResponse(videoId, it) }

            // process current client response
            if (streamPlayerResponse?.playabilityStatus?.status == "OK") {
                Timber.tag(logTag).d("Player response status OK for client: ${if (clientIndex == -1) MAIN_CLIENT.clientName else STREAM_FALLBACK_CLIENTS[clientIndex].clientName}")

                // 1. Find target format and itag using InnerTube response (which has audioTrack metadata for language selection)
                val targetFormat = findFormat(
                    streamPlayerResponse,
                    audioQuality,
                    connectivityManager,
                )

                if (targetFormat == null) {
                    Timber.tag(logTag).d("No suitable format found for client")
                    continue
                }

                val targetItag = targetFormat.itag
                Timber.tag(logTag).d("Selected target itag from metadata: $targetItag (audioTrack: ${targetFormat.audioTrack})")
                audioDebug("SELECTED client=${client.clientName} targetItag=$targetItag trackId=${targetFormat.audioTrack?.id} trackName=${targetFormat.audioTrack?.displayName} trackLang=${trackLang(targetFormat)} isOriginal=${targetFormat.isOriginal} dubbed=${isDubbedTrack(targetFormat)} mime=${targetFormat.mimeType} bitrate=${targetFormat.bitrate}")

                // 2. Get decrypted stream URLs via NewPipe method (skip NewPipe for multi-language videos
                // as NewPipe's single-itag stream mapping overwrites distinct audio tracks)
                val isMultiLangVideo = hasMultipleAudioTracks(streamPlayerResponse)

                val newPipeResponse = if (wasOriginallyAgeRestricted || isMultiLangVideo) null else YouTube.newPipePlayer(videoId, streamPlayerResponse)
                val responseToUse = newPipeResponse ?: streamPlayerResponse

                // 3. Match targetItag in responseToUse to get NewPipe's playable URL for that exact Spanish track
                // IMPORTANT: in multi-language videos EVERY audio track shares the same itag
                // (e.g. 251), so matching by itag alone returns the first track in the list
                // (often the auto-dubbed Arabic one). Match the audio track too.
                val targetTrackId = targetFormat.audioTrack?.id
                val responseAudio = responseToUse.streamingData?.adaptiveFormats.orEmpty().filter { it.isAudio }
                format = if (targetTrackId != null) {
                    responseAudio.find { it.itag == targetItag && it.audioTrack?.id == targetTrackId }
                    // Different InnerTube clients sometimes encode the same track ID
                    // differently. Match the base language before considering the
                    // original selected format; never choose an arbitrary same-itag
                    // stream, since Arabic and Spanish may share an itag.
                        ?: responseAudio.find {
                            it.itag == targetItag &&
                                    trackLang(it) == trackLang(targetFormat) &&
                                    (it.isOriginal || !isDubbedTrack(it))
                        }
                        ?: targetFormat
                } else {
                    responseAudio.find { it.itag == targetItag }
                        ?: responseToUse.streamingData?.adaptiveFormats?.find { it.itag == targetItag }
                        ?: targetFormat
                }

                Timber.tag(logTag).d("Format found after resolution: ${format.mimeType}, bitrate: ${format.bitrate}, url present: ${!format.url.isNullOrEmpty()}")
                audioDebug("RESOLVED itag=${format.itag} trackId=${format.audioTrack?.id} trackName=${format.audioTrack?.displayName} trackLang=${trackLang(format)} isOriginal=${format.isOriginal} dubbed=${isDubbedTrack(format)} mime=${format.mimeType} bitrate=${format.bitrate} urlPresent=${!format.url.isNullOrEmpty()}")

                audioDebug("URL_SOURCE_RESPONSE newPipeResponse=${newPipeResponse != null} responseAudioCount=${responseToUse.streamingData?.adaptiveFormats.orEmpty().count { it.isAudio }} selectedItag=${format.itag} selectedTrackId=${format.audioTrack?.id} selectedTrackName=${format.audioTrack?.displayName} selectedLang=${trackLang(format)}")
                responseToUse.streamingData?.adaptiveFormats.orEmpty()
                    .filter { it.isAudio && it.itag == format.itag }
                    .forEachIndexed { index, candidate ->
                        audioDebug("URL_MATCH_CANDIDATE[$index] itag=${candidate.itag} trackId=${candidate.audioTrack?.id} trackName=${candidate.audioTrack?.displayName} lang=${trackLang(candidate)} original=${candidate.isOriginal} mime=${candidate.mimeType} bitrate=${candidate.bitrate} ${safeAudioUrlInfo(candidate.url)}")
                    }
                audioDebug("TARGET_FORMAT_URL ${safeAudioUrlInfo(targetFormat.url)}")
                audioDebug("URL_FORMAT_BEFORE_RESOLVE ${safeAudioUrlInfo(format.url)}")

                // For multilingual videos, duplicate itags are NOT interchangeable.
                // Preserve the chosen audio-track identity throughout URL resolution.
                val wantedLang = trackLang(targetFormat).ifBlank {
                    audioUrlLanguageHint(targetFormat.url).orEmpty()
                }
                val matchingFormats = responseAudio.filter { candidate ->
                    candidate.itag == targetItag &&
                            (targetTrackId == null || candidate.audioTrack?.id == targetTrackId ||
                                    trackLang(candidate) == wantedLang) &&
                            (wantedLang.isEmpty() || languageMatchesUrl(candidate, wantedLang))
                }
                val urlFormat = if (isMultiLangVideo) {
                    matchingFormats.firstOrNull { !it.url.isNullOrBlank() }
                        ?: matchingFormats.firstOrNull()
                        ?: format
                } else format

                format = urlFormat
                streamUrl = findUrlOrNull(
                    urlFormat, videoId, responseToUse,
                    skipNewPipe = wasOriginallyAgeRestricted || isMultiLangVideo,
                ) ?: findUrlOrNull(
                    targetFormat, videoId, responseToUse,
                    skipNewPipe = wasOriginallyAgeRestricted,
                )

                if (streamUrl.isNullOrBlank()) {
                    audioDebug("NO_TRACK_SAFE_URL videoId=$videoId itag=$targetItag")
                    continue
                }
                audioDebug("RESOLVED_URL_INFO ${safeAudioUrlInfo(streamUrl)} wantedLang=$wantedLang")

                var activeUrl: String = streamUrl

                // Apply n-transform for throttle parameter handling
                val currentClient = if (clientIndex == -1) {
                    usedAgeRestrictedClient ?: MAIN_CLIENT
                } else {
                    STREAM_FALLBACK_CLIENTS[clientIndex]
                }

                // Check if this is a privately owned track
                val isPrivatelyOwnedTrack = streamPlayerResponse.videoDetails?.musicVideoType == "MUSIC_VIDEO_TYPE_PRIVATELY_OWNED_TRACK"
                val musicVideoType = streamPlayerResponse.videoDetails?.musicVideoType

                Timber.tag(TAG).d("=== N-TRANSFORM DECISION ===")
                Timber.tag(TAG).d("Content type analysis:")
                Timber.tag(TAG).d("  musicVideoType: $musicVideoType")
                Timber.tag(TAG).d("  isPrivatelyOwnedTrack: $isPrivatelyOwnedTrack")
                Timber.tag(TAG).d("  isUploadedTrack (from playlistId): $isUploadedTrack")
                Timber.tag(TAG).d("  wasOriginallyAgeRestricted: $wasOriginallyAgeRestricted")
                Timber.tag(TAG).d("Client analysis:")
                Timber.tag(TAG).d("  currentClient: ${currentClient.clientName}")
                Timber.tag(TAG).d("  useWebPoTokens: ${currentClient.useWebPoTokens}")

                // Apply n-transform and PoToken for web clients OR for private tracks (including TVHTML5)
                val hasNParam = activeUrl.contains(Regex("[?&]n="))
                val needsNTransform = hasNParam || currentClient.useWebPoTokens ||
                        currentClient.clientName in listOf("WEB", "WEB_REMIX", "WEB_CREATOR", "TVHTML5", "TVHTML5_SIMPLY_EMBEDDED_PLAYER") ||
                        isPrivatelyOwnedTrack

                Timber.tag(TAG).d("N-transform decision:")
                Timber.tag(TAG).d("  needsNTransform: $needsNTransform")
                Timber.tag(TAG).d("  Reason: hasNParam=$hasNParam, useWebPoTokens=${currentClient.useWebPoTokens}, " +
                        "isPrivatelyOwnedTrack=$isPrivatelyOwnedTrack")

                if (needsNTransform) {
                    try {
                        Timber.tag(TAG).d("Applying n-transform to stream URL...")
                        Timber.tag(TAG).d("  Original URL length: ${activeUrl.length}")
                        Timber.tag(TAG).d("  Original URL details: ${safeAudioUrlInfo(activeUrl)}")

                        val originalUrl = activeUrl
                        // Use CipherDeobfuscator for n-transform
                        activeUrl = CipherDeobfuscator.transformNParamInUrl(activeUrl)
                        if (hasNParam && activeUrl == originalUrl) {
                            Timber.tag(TAG).d("CipherDeobfuscator left n-param unchanged, trying EjsNTransformSolver fallback...")
                            activeUrl = EjsNTransformSolver.transformNParamInUrl(originalUrl)
                        }

                        Timber.tag(TAG).d("  Transformed URL length: ${activeUrl.length}")
                        Timber.tag(TAG).d("  URL changed: ${originalUrl != activeUrl}")

                        // Append pot= parameter with streaming data poToken
                        val needsPoToken = (currentClient.useWebPoTokens || isPrivatelyOwnedTrack) && poToken?.streamingDataPoToken != null
                        Timber.tag(TAG).d("PoToken decision:")
                        Timber.tag(TAG).d("  needsPoToken: $needsPoToken")
                        Timber.tag(TAG).d("  hasStreamingDataPoToken: ${poToken?.streamingDataPoToken != null}")

                        if (needsPoToken) {
                            Timber.tag(TAG).d("Appending pot= parameter to stream URL")
                            val separator = if ("?" in activeUrl) "&" else "?"
                            activeUrl = "${activeUrl}${separator}pot=${Uri.encode(poToken.streamingDataPoToken)}"
                            Timber.tag(TAG).d("  Final URL length (with pot): ${activeUrl.length}")
                        }
                    } catch (e: Exception) {
                        Timber.tag(TAG).e(e, "N-transform or pot append failed: ${e.message}")
                        Timber.tag(TAG).e("Stack trace: ${e.stackTraceToString().take(500)}")
                        // Continue with original URL
                    }
                } else {
                    Timber.tag(TAG).d("Skipping n-transform (not required for this client/content)")
                }
                streamUrl = activeUrl
                // Keep an internally consistent fallback tuple, never a pre-transform
                // or language-rejected URL.
                if (fallbackStreamUrl == null) {
                    fallbackStreamUrl = activeUrl
                    fallbackFormat = format
                    fallbackExpiresInSeconds = streamPlayerResponse.streamingData?.expiresInSeconds
                }

                streamExpiresInSeconds = streamPlayerResponse.streamingData?.expiresInSeconds
                if (streamExpiresInSeconds == null) {
                    Timber.tag(logTag).d("Stream expiration time not found")
                    continue
                }

                Timber.tag(logTag).d("Stream expires in: $streamExpiresInSeconds seconds")

                // Check if this is a privately owned track (uploaded song)
                val isPrivatelyOwned = streamPlayerResponse.videoDetails?.musicVideoType == "MUSIC_VIDEO_TYPE_PRIVATELY_OWNED_TRACK"

                if (clientIndex == STREAM_FALLBACK_CLIENTS.size - 1 || isPrivatelyOwned) {
                    /** skip [validateStatus] for last client or private tracks */
                    if (isPrivatelyOwned) {
                        Timber.tag(logTag).d("Skipping validation for privately owned track: ${currentClient.clientName}")
                        println("[PLAYBACK_DEBUG] Using stream without validation for PRIVATELY_OWNED_TRACK")
                    } else {
                        Timber.tag(logTag).d("Using last fallback client without validation: ${STREAM_FALLBACK_CLIENTS[clientIndex].clientName}")
                    }
                    Timber.tag(TAG)
                        .i("Playback: client=${currentClient.clientName}, videoId=$videoId, private=$isPrivatelyOwned")
                    break
                }

                if (skipValidation || validateStatus(activeUrl)) {
                    // working stream found
                    Timber.tag(logTag).d("Stream validated successfully with client: ${currentClient.clientName}")
                    // Log for release builds
                    Timber.tag(TAG).i("Playback: client=${currentClient.clientName}, videoId=$videoId")
                    break
                } else {
                    Timber.tag(logTag).d("Stream validation failed for client: ${currentClient.clientName}")
                    streamUrl = null
                }
            } else {
                Timber.tag(logTag).d("Player response status not OK: ${streamPlayerResponse?.playabilityStatus?.status}, reason: ${streamPlayerResponse?.playabilityStatus?.reason}")
            }
        }

        } finally {
            // No orphan requests after success, error or a new track.
            fallbackScope.cancel()
        }

        if (streamUrl.isNullOrBlank() && fallbackStreamUrl != null) {
            audioDebug("ALL CLIENTS EXHAUSTED: using previously language-checked fallback stream")
            streamUrl = fallbackStreamUrl
            format = fallbackFormat
            streamExpiresInSeconds = fallbackExpiresInSeconds ?: 21600
        }

        if (streamUrl == null) {
            Timber.tag(logTag).e("Could not find stream url")
            throw Exception("Could not find stream url")
        }

        if (streamExpiresInSeconds == null) {
            streamExpiresInSeconds = 21600
        }

        if (format == null) {
            format = fallbackFormat ?: run {
                streamPlayerResponse?.streamingData?.adaptiveFormats?.firstOrNull { it.isAudio }
            } ?: throw Exception("Could not find format")
        }

        Timber.tag(logTag).d("Successfully obtained playback data with format: ${format.mimeType}, bitrate: ${format.bitrate}")
        audioDebug("FINAL videoId=$videoId title=${streamPlayerResponse?.videoDetails?.title} client=${(if (usedAgeRestrictedClient != null) usedAgeRestrictedClient else MAIN_CLIENT)?.clientName} itag=${format.itag} trackId=${format.audioTrack?.id} trackName=${format.audioTrack?.displayName} trackLang=${trackLang(format)} isOriginal=${format.isOriginal} mime=${format.mimeType} bitrate=${format.bitrate} expiresIn=$streamExpiresInSeconds ${safeAudioUrlInfo(streamUrl)}")
        if (isUploadedTrack) {
            println("[PLAYBACK_DEBUG] SUCCESS: Got playback data for uploaded track - format=${format.mimeType}, streamUrl=${streamUrl.take(100)}...")
        }
        Log.i("SpotuiStartup", "ytStream total=${android.os.SystemClock.elapsedRealtime() - streamStartedAt}ms " +
            "videoId=$videoId format=${format.itag} track=${format.audioTrack?.id ?: "default"}")
        PlaybackData(
            streamPlayerResponse?.playerConfig?.audioConfig ?: mainPlayerResponse?.playerConfig?.audioConfig,
            streamPlayerResponse?.videoDetails ?: mainPlayerResponse?.videoDetails,
            streamPlayerResponse?.playbackTracking ?: mainPlayerResponse?.playbackTracking,
            format,
            streamUrl,
            streamExpiresInSeconds,
        )
    }.onFailure { e ->
        println("[PLAYBACK_DEBUG] EXCEPTION during playback for videoId=$videoId: ${e::class.simpleName}: ${e.message}")
        e.printStackTrace()
    }
    /**
     * Simple player response intended to use for metadata only.
     * Stream URLs of this response might not work so don't use them.
     */
    suspend fun playerResponseForMetadata(
        videoId: String,
        playlistId: String? = null,
    ): Result<PlayerResponse> {
        Timber.tag(logTag).d("Fetching metadata-only player response for videoId: $videoId using MAIN_CLIENT: ${MAIN_CLIENT.clientName}")
        return YouTube.player(videoId, playlistId, client = WEB_REMIX) // ANDROID_VR does not work with history
            .onSuccess { Timber.tag(logTag).d("Successfully fetched metadata") }
            .onFailure { Timber.tag(logTag).e(it, "Failed to fetch metadata") }
    }

    fun normalizeLanguageCode(input: String?): String {
        if (input.isNullOrBlank()) return ""
        val raw = input.trim().lowercase()

        // Spanish variants
        if (raw == "es" || raw.startsWith("es-") || raw.startsWith("es_") || raw.startsWith("a.es") ||
            raw == "spa" || raw.startsWith("spa-") || raw.startsWith("spa_") ||
            "español" in raw || "espanol" in raw || "spanish" in raw
        ) return "es"

        // English variants
        if (raw == "en" || raw.startsWith("en-") || raw.startsWith("en_") || raw.startsWith("a.en") ||
            raw == "eng" || raw.startsWith("eng-") || "english" in raw || "inglés" in raw || "ingles" in raw
        ) return "en"

        // French
        if (raw == "fr" || raw.startsWith("fr-") || raw.startsWith("fr_") || raw.startsWith("a.fr") ||
            raw == "fra" || raw == "fre" || "francés" in raw || "frances" in raw || "french" in raw
        ) return "fr"

        // German
        if (raw == "de" || raw.startsWith("de-") || raw.startsWith("de_") || raw.startsWith("a.de") ||
            raw == "deu" || raw == "ger" || "alemán" in raw || "aleman" in raw || "german" in raw
        ) return "de"

        // Arabic
        if (raw == "ar" || raw.startsWith("ar-") || raw.startsWith("ar_") || raw.startsWith("a.ar") ||
            raw == "ara" || "arabic" in raw || "árabe" in raw || "arabe" in raw || "العربية" in raw
        ) return "ar"

        val clean = raw.removePrefix("a.")
        val code = Regex("""^([a-z]{2,3})(?:[._\-]|\d|$)""", RegexOption.IGNORE_CASE)
            .find(clean)?.groupValues?.getOrNull(1)?.lowercase().orEmpty()

        if (code in listOf("dub", "aud", "org", "und", "app")) return ""
        return code
    }

    private fun trackLang(f: PlayerResponse.StreamingData.Format): String {
        val track = f.audioTrack
        val idLang = normalizeLanguageCode(track?.id)
        if (idLang.isNotEmpty()) return idLang
        val nameLang = normalizeLanguageCode(track?.displayName)
        if (nameLang.isNotEmpty()) return nameLang
        return normalizeLanguageCode(audioUrlLanguageHint(f.url))
    }

    private fun isDubbedTrack(f: PlayerResponse.StreamingData.Format): Boolean {
        when (audioUrlContentHint(f.url)) {
            "original" -> return false
            "dubbed", "descriptive" -> return true
            "dubbed-auto" -> return false
        }
        if (isOriginalTrack(f)) return false
        val name = f.audioTrack?.displayName?.lowercase().orEmpty()
        val id = f.audioTrack?.id?.lowercase().orEmpty()
        return id.contains("dubbed") || name.contains("doblad") || name.contains("traducción") ||
                name.contains("traducido") || name.contains("descriptive")
    }

    private fun languageMatchesUrl(f: PlayerResponse.StreamingData.Format, language: String): Boolean {
        val urlLang = normalizeLanguageCode(audioUrlLanguageHint(f.url))
        if (urlLang.isEmpty()) return true
        val targetLang = normalizeLanguageCode(language)
        return urlLang == targetLang
    }

    private fun hasMultipleAudioTracks(response: PlayerResponse): Boolean {
        val audio = response.streamingData?.adaptiveFormats.orEmpty().filter { it.isAudio }
        val trackIds = audio.mapNotNull { it.audioTrack?.id }.distinct()
        val trackLangs = audio.mapNotNull { normalizeLanguageCode(trackLang(it)).ifEmpty { null } }.distinct()
        return trackIds.size > 1 || trackLangs.size > 1
    }

    private fun findFormat(
        playerResponse: PlayerResponse,
        audioQuality: AudioQuality,
        connectivityManager: ConnectivityManager,
        preferredLanguage: String = "es",
    ): PlayerResponse.StreamingData.Format? {
        Timber.tag(logTag).d("Finding format with audioQuality: $audioQuality, network metered: ${connectivityManager.isActiveNetworkMetered}")

        val adaptiveFormats = playerResponse.streamingData?.adaptiveFormats.orEmpty()
            .filter { it.isAudio }

        if (adaptiveFormats.isEmpty()) return null

        val targetLang = normalizeLanguageCode(preferredLanguage).ifEmpty { "es" }
        val hasMultiAudio = hasMultipleAudioTracks(playerResponse)

        val selected = adaptiveFormats.maxByOrNull { f ->
            var score = 0L

            if (hasMultiAudio) {
                val fLang = trackLang(f)
                val isTargetLang = fLang.isNotEmpty() && fLang == targetLang

                if (isTargetLang) {
                    score += 10_000_000L
                }
                if (f.audioTrack?.audioIsDefault == true) {
                    score += 5_000_000L
                }
                if (isOriginalTrack(f)) {
                    score += 2_000_000L
                }
                if (f.isAutoDubbedTrack || isDubbedTrack(f)) {
                    score -= 3_000_000L
                }
            }

            val codecBonus = when {
                f.mimeType.contains("opus", ignoreCase = true) -> 10_000L
                f.mimeType.startsWith("audio/webm") -> 5_000L
                else -> 0L
            }

            val bitrateScore = (f.bitrate / 1000L) * when (audioQuality) {
                AudioQuality.AUTO -> if (connectivityManager.isActiveNetworkMetered) 1 else 10
                AudioQuality.HIGH -> 10
                AudioQuality.LOW -> -10
            }

            score + bitrateScore + codecBonus
        }

        if (selected != null) {
            Timber.tag(logTag).d("Selected format: itag=${selected.itag}, mime=${selected.mimeType}, bitrate=${selected.bitrate}, audioTrack=${selected.audioTrack}")
        } else {
            Timber.tag(logTag).d("No suitable audio format found")
        }

        return selected
    }
    /**
     * Checks if the stream url returns a successful status.
     *
     * Why the leniency: on slow mobile networks HEAD can time out or be rejected by edge
     * CDNs (405/403/410 on HEAD while GET works). If we treat those as "failed" we skip a
     * stream that actually plays. Rules here:
     *  - 2xx → valid
     *  - 405/403/410 → treat as valid (HEAD may be restricted; ExoPlayer will GET)
     *  - IOException (timeout/reset) → treat as valid; ExoPlayer has its own retry and
     *    killing the client here just cascades us down the fallback chain for no reason
     *  - other HTTP codes (4xx/5xx) → invalid
     */
    // Only the optional URL probe has a short deadline. The playback client and
    // YouTube extraction retain their original timeouts and recovery behavior.
    private val quickProbeClient by lazy {
        httpClient.newBuilder()
            .connectTimeout(3, TimeUnit.SECONDS)
            .readTimeout(3, TimeUnit.SECONDS)
            .callTimeout(4, TimeUnit.SECONDS)
            .build()
    }

    internal fun validateStatus(url: String): Boolean {
        Timber.tag(logTag).d("Validating stream URL status via GET Range bytes=0-0")
        try {
            val requestBuilder = okhttp3.Request.Builder()
                .get()
                .addHeader("Range", "bytes=0-0")
                .url(url)

            YouTube.cookie?.let { cookie ->
                requestBuilder.addHeader("Cookie", cookie)
            }

            val response = quickProbeClient.newCall(requestBuilder.build()).execute()
            response.close()
            val code = response.code
            val accepted = (response.isSuccessful || code == 206 || code == 403 || code == 410 || code == 405 || code == 400) && code != 404
            Timber.tag(logTag).d("Stream URL validation: code=$code accepted=$accepted")
            return accepted
        } catch (e: java.io.IOException) {
            // Network timeout / reset while probing. The stream URL itself may still
            // be fine — let ExoPlayer attempt GET rather than burning a fallback client.
            Timber.tag(logTag).w(e, "Stream URL probe failed (IO); accepting optimistically")
            return true
        } catch (e: Exception) {
            // Offline or network error while probing cached URL. Accept optimistically for offline fallback.
            Timber.tag(logTag).w(e, "Stream URL validation failed (offline/error); accepting optimistically")
            return true
        }
    }
    data class SignatureTimestampResult(
        val timestamp: Int?,
        val isAgeRestricted: Boolean
    )

    private fun getSignatureTimestampOrNull(videoId: String): SignatureTimestampResult {
        Timber.tag(logTag).d("Getting signature timestamp for videoId: $videoId")
        val result = NewPipeExtractor.getSignatureTimestamp(videoId)
        return result.fold(
            onSuccess = { timestamp ->
                Timber.tag(logTag).d("Signature timestamp obtained: $timestamp")
                SignatureTimestampResult(timestamp, isAgeRestricted = false)
            },
            onFailure = { error ->
                val isAgeRestricted = error.message?.contains("age-restricted", ignoreCase = true) == true ||
                        error.cause?.message?.contains("age-restricted", ignoreCase = true) == true
                if (isAgeRestricted) {
                    Timber.tag(logTag).d("Age-restricted content detected from NewPipe")
                    Timber.tag(TAG).i("Age-restricted detected early via NewPipe: videoId=$videoId")
                } else {
                    Timber.tag(logTag).e(error, "Failed to get signature timestamp")
                    reportException(error)
                }
                SignatureTimestampResult(null, isAgeRestricted)
            }
        )
    }

    private suspend fun findUrlOrNull(
        format: PlayerResponse.StreamingData.Format,
        videoId: String,
        playerResponse: PlayerResponse,
        skipNewPipe: Boolean = false
    ): String? {
        Timber.tag(logTag).d("Finding stream URL for format: ${format.mimeType}, videoId: $videoId, skipNewPipe: $skipNewPipe")

        // First check if format already has a URL
        if (!format.url.isNullOrEmpty()) {
            Timber.tag(logTag).d("Using URL from format directly")
            return format.url
        }

        // Try custom cipher deobfuscation for signatureCipher formats
        val signatureCipher = format.signatureCipher ?: format.cipher
        if (!signatureCipher.isNullOrEmpty()) {
            Timber.tag(logTag).d("Format has signatureCipher, using custom deobfuscation")
            val customDeobfuscatedUrl = CipherDeobfuscator.deobfuscateStreamUrl(signatureCipher, videoId)
            if (customDeobfuscatedUrl != null) {
                Timber.tag(logTag).d("Stream URL obtained via custom cipher deobfuscation")
                return customDeobfuscatedUrl
            }
            Timber.tag(logTag).d("Custom cipher deobfuscation failed")
        }

        // Skip track-agnostic NewPipe URL lookup for multilingual content.
        // The exact format URL and its signatureCipher have already been tried.
        if (skipNewPipe) {
            Timber.tag(logTag).d("Skipping NewPipe methods for age-restricted content")
            return null
        }

        // Try to get URL using NewPipeExtractor signature deobfuscation
        val deobfuscatedUrl = NewPipeExtractor.getStreamUrl(format, videoId)
        if (deobfuscatedUrl != null) {
            Timber.tag(logTag).d("Stream URL obtained via NewPipe deobfuscation")
            return deobfuscatedUrl
        }

        // Fallback: try to get URL from StreamInfo
        Timber.tag(logTag).d("Trying StreamInfo fallback for URL")
        val streamUrls = YouTube.getNewPipeStreamUrls(videoId)
        if (streamUrls.isNotEmpty()) {
            val wantedLang = normalizeLanguageCode(trackLang(format))
            val sameItag = streamUrls.filter { it.first == format.itag }
            // The stream URL carries the track in its xtags param (lang%3Des); use it to
            // avoid picking another language that shares the same itag.
            val streamUrl = if (wantedLang.isNotEmpty()) {
                sameItag.find {
                    normalizeLanguageCode(audioUrlLanguageHint(it.second)) == wantedLang
                }?.second ?: sameItag.firstOrNull()?.second
            } else {
                sameItag.firstOrNull()?.second
            }
            if (streamUrl != null) {
                Timber.tag(logTag).d("Stream URL obtained from StreamInfo")
                return streamUrl
            }

            // If exact itag not found, try to find any audio stream
            val audioStream = streamUrls.find { urlPair ->
                playerResponse.streamingData?.adaptiveFormats?.any {
                    it.itag == urlPair.first && it.isAudio
                } == true
            }?.second

            if (audioStream != null) {
                Timber.tag(logTag).d("Audio stream URL obtained from StreamInfo (different itag)")
                return audioStream
            }
        }

        Timber.tag(logTag).e("Failed to get stream URL")
        return null
    }

    fun forceRefreshForVideo(videoId: String) {
        Timber.tag(logTag).d("Force refreshing for videoId: $videoId")
    }

    fun resetSession(context: Context) {
        poTokenGenerator.reset()
        com.music.spotui.data.preferences.clearAllCachedStreams(context)
        com.music.spotui.data.preferences.clearAllResolvedVideos(context)
        @OptIn(kotlinx.coroutines.DelicateCoroutinesApi::class)
        kotlinx.coroutines.GlobalScope.launch(kotlinx.coroutines.Dispatchers.IO) {
            runCatching { YouTube.visitorData = YouTube.visitorData().getOrNull() }
        }
    }
}