package com.music.spotui.data.api

import android.content.Context
import com.metrolist.spotify.Spotify
import com.music.spotui.data.entity.*
import com.music.spotui.data.preferences.ReleaseKind
import com.music.spotui.data.publicationTime
import com.music.spotui.data.withinLastMonth
import com.music.spotui.providers.PodcastProvider
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import org.json.JSONArray
import org.json.JSONObject
import java.time.Instant
import java.time.ZoneId

internal data class FollowedEpisode(val song: SongsModel, val showId: String, val publishedAt: Long)
internal data class FollowedMusicRelease(val album: AlbumsModel, val publishedAt: Long)
internal data class FollowingFeedState<T>(val items: List<T> = emptyList(), val loading: Boolean = false,
    val checkedAt: Long = 0, val complete: Boolean = false, val error: String? = null)

/** Cached, bounded background discovery. Only tiny read watermarks enter device sync. */
internal class FollowingReleaseFeed(private val context: Context, private val scope: CoroutineScope) {
    companion object {
        @Volatile private var shared:FollowingReleaseFeed?=null
        @Synchronized fun get(context:Context):FollowingReleaseFeed = shared ?: FollowingReleaseFeed(
            context.applicationContext,CoroutineScope(SupervisorJob()+Dispatchers.IO)).also {feed->
            shared=feed
            feed.scope.launch {
                com.music.spotui.data.preferences.SubscribedPodcastPref.revision.collect {
                    feed.refresh(com.music.spotui.data.preferences.SubscribedPodcastPref.getSubscribedPodcasts(feed.context),
                        emptyList(),kind=ReleaseKind.PODCASTS)
                }
            }
        }
    }
    private val podcastState = MutableStateFlow(FollowingFeedState<FollowedEpisode>())
    private val musicState = MutableStateFlow(FollowingFeedState<FollowedMusicRelease>())
    val podcasts = podcastState.asStateFlow()
    val music = musicState.asStateFlow()
    private val cache = context.getSharedPreferences("followed_release_cache_v2", 0)
    private val dates = context.getSharedPreferences("episode_publication_times_v1", 0)
    private val permits = Semaphore(2)
    private val datePermits = Semaphore(2)
    private var podcastJob: Job? = null
    private var musicJob: Job? = null
    private var podcastKey = ""
    private var musicKey = ""
    private var podcastAttempt = 0L
    private var musicAttempt = 0L
    private val cooldown = 2 * 60_000L

    @Synchronized
    fun refresh(shows: List<PodcastModel>, artists: List<ArtistsModel>, force: Boolean = false, kind:ReleaseKind?=null) {
        val now = System.currentTimeMillis()
        val forcePodcasts=force && (kind==null || kind==ReleaseKind.PODCASTS)
        val forceMusic=force && (kind==null || kind==ReleaseKind.MUSIC)
        val nextPodcasts = shows.map { it.id }.sorted().joinToString("|")
        if ((kind==null || kind==ReleaseKind.PODCASTS) &&
            (nextPodcasts != podcastKey || podcastJob?.isActive!=true) &&
            (nextPodcasts != podcastKey || forcePodcasts || now-podcastAttempt >= cooldown)) {
            podcastKey = nextPodcasts; podcastAttempt = now; podcastJob?.cancel()
            podcastJob = scope.launch(Dispatchers.IO) { loadPodcasts(shows, now, forcePodcasts) }
        }
        val nextMusic = artists.map { it.id }.sorted().joinToString("|")
        if ((kind==null || kind==ReleaseKind.MUSIC) &&
            (nextMusic != musicKey || musicJob?.isActive!=true) &&
            (nextMusic != musicKey || forceMusic || now-musicAttempt >= cooldown)) {
            musicKey = nextMusic; musicAttempt = now; musicJob?.cancel()
            musicJob = scope.launch(Dispatchers.IO) { loadMusic(artists, now, forceMusic) }
        }
    }

    private fun episode(o: JSONObject) = FollowedEpisode(SongsModel(o.getInt("id"),o.getString("title"),o.getString("album"),
        o.getString("singer"),o.getString("cover"),o.getString("url"), durationMs=o.optInt("duration"),
        description=o.optString("description"),releaseDate=o.optString("date")),o.getString("show"),o.getLong("published"))
    private fun wire(e: FollowedEpisode) = JSONObject().put("id",e.song.id).put("title",e.song.title).put("album",e.song.album)
        .put("singer",e.song.singer).put("cover",e.song.coverUri).put("url",e.song.url).put("duration",e.song.durationMs)
        .put("description",e.song.description).put("date",e.song.releaseDate).put("show",e.showId).put("published",e.publishedAt)
    private fun cachedRows(key: String): JSONObject? = runCatching { JSONObject(cache.getString(key,null) ?: return null) }.getOrNull()
    private fun episodes(key: String, now: Long): List<FollowedEpisode> = cachedRows(key)?.optJSONArray("rows")?.let { rows ->
        (0 until rows.length()).mapNotNull { runCatching { episode(rows.getJSONObject(it)) }.getOrNull() }
            .filter { withinLastMonth(it.publishedAt,now) }
    }.orEmpty()

    private suspend fun loadPodcasts(shows: List<PodcastModel>, now: Long, force: Boolean) = coroutineScope {
        val ids = shows.map { it.id }.toSet()
        podcastState.value = FollowingFeedState((podcastState.value.items.filter {it.showId in ids && withinLastMonth(it.publishedAt,now)}+
            shows.flatMap { episodes("podcast:${it.id}",now) })
            .distinctBy {it.song.url.substringBefore('|')}.sortedByDescending {it.publishedAt}, true, now)
        val failures = java.util.concurrent.atomic.AtomicInteger()
        val checkedAt=java.util.concurrent.atomic.AtomicLong(now)
        val since = Instant.ofEpochMilli(now).atZone(ZoneId.systemDefault()).minusMonths(1).toInstant().toEpochMilli()
        shows.distinctBy { it.id }.map { show -> async {
            val key = "podcast:${show.id}"
            val stored = cachedRows(key)
            if (!force && stored!=null && now-stored.optLong("at")<cooldown) {
                checkedAt.updateAndGet {minOf(it,stored.optLong("at"))};return@async
            }
            permits.withPermit {
                try {
                    withTimeout(60_000) {
                        var finalRows=emptyList<FollowedEpisode>()
                        var receivedEpisodes=false
                        val dateAttempts=mutableSetOf<String>()
                        var missingDates=false
                        PodcastProvider.getShowEpisodesFlow(show.id,show.name,publishedSince=since,forceRefresh=true).collect { songs ->
                            if(songs.isNotEmpty())receivedEpisodes=true
                            // At most 20 missing dates per show/update; cache actual publication
                            // dates permanently so pagination and later refreshes do not refetch them.
                            val pendingDates=songs.filter {song->
                                val key=com.music.spotui.data.preferences.PodcastResumeStore.episodeKey(song.url)
                                dates.getLong(key,0)==0L && publicationTime(song.releaseDate,now)==null && key !in dateAttempts
                            }.take((20-dateAttempts.size).coerceAtLeast(0))
                            fun publishEpisodes(preservePrevious: Boolean) {
                                val published = songs.mapNotNull { song ->
                                    val identity = com.music.spotui.data.preferences.PodcastResumeStore.episodeKey(song.url)
                                    val known = dates.getLong(identity,0)
                                    val timestamp = com.music.spotui.data.stablePublicationTime(known,song.releaseDate,now)?.also {
                                        dates.edit().putLong(identity,it).apply()
                                    } ?: return@mapNotNull null
                                    FollowedEpisode(song.copy(album=song.album.ifBlank {show.name}),show.id,timestamp)
                                        .takeIf {withinLastMonth(it.publishedAt,now)}
                                }
                                finalRows=published
                                podcastState.update { state -> state.copy(items=(state.items.filterNot {it.showId==show.id}+published)
                                    .plus(if(preservePrevious)state.items.filter {it.showId==show.id} else emptyList())
                                    .filter {it.showId in ids && withinLastMonth(it.publishedAt,now)}
                                    .distinctBy {it.song.url.substringBefore('|')}.sortedByDescending {it.publishedAt}) }
                            }
                            if (pendingDates.isNotEmpty()) publishEpisodes(preservePrevious = true)
                            pendingDates.forEach {dateAttempts+=com.music.spotui.data.preferences.PodcastResumeStore.episodeKey(it.url)}
                            pendingDates.map {song->async {
                                val key=com.music.spotui.data.preferences.PodcastResumeStore.episodeKey(song.url)
                                datePermits.withPermit {
                                    val label=PodcastProvider.publicationDate(song)
                                    label?.let {publicationTime(it,now)}?.let {date->dates.edit().putLong(key,date).apply()}
                                }
                            }}.awaitAll()
                            missingDates=songs.any {song->
                                val key=com.music.spotui.data.preferences.PodcastResumeStore.episodeKey(song.url)
                                dates.getLong(key,0)==0L && publicationTime(song.releaseDate,now)==null
                            }
                            publishEpisodes(preservePrevious = missingDates)
                        }
                        check(receivedEpisodes){"Podcast discovery was unavailable"}
                        check(!missingDates || finalRows.isNotEmpty()) {"Episode publication dates were unavailable"}
                        if(missingDates)failures.incrementAndGet()
                        cache.edit().putString(key,JSONObject().put("at",now).put("rows",JSONArray(finalRows.map(::wire))).toString()).apply()
                    }
                } catch(c:CancellationException) { if(c is TimeoutCancellationException)failures.incrementAndGet() else throw c }
                catch(_:Exception) { failures.incrementAndGet() }
            }
        } }.awaitAll()
        podcastState.update { it.copy(loading=false,complete=failures.get()==0,checkedAt=checkedAt.get(),
            error=if(failures.get()>0)"Some podcasts could not be updated." else null) }
    }

    private fun musicRows(key:String):List<FollowedMusicRelease> = cachedRows(key)?.optJSONArray("rows")?.let { rows ->
        (0 until rows.length()).mapNotNull { runCatching {
            val o=rows.getJSONObject(it)
            FollowedMusicRelease(AlbumsModel(o.getInt("id"),o.getString("artist"),o.getString("cover"),o.getString("name"),
                o.getString("date"),o.optString("type"),o.getString("spotifyId")),o.getLong("published"))
        }.getOrNull() }
    }.orEmpty()
    private suspend fun loadMusic(artists:List<ArtistsModel>,now:Long,force:Boolean) = coroutineScope {
        val results=java.util.concurrent.ConcurrentHashMap<String,List<FollowedMusicRelease>>()
        artists.forEach {results[it.id]=musicRows("music:${it.id}")}
        fun publish() { musicState.update {it.copy(items=results.values.flatten().filter {it.publishedAt<=now}
            .distinctBy {it.album.spotifyAlbumId}.sortedByDescending {it.publishedAt})} }
        musicState.update { it.copy(loading = true, checkedAt = now, complete = false, error = null) }
        publish()
        val failures=java.util.concurrent.atomic.AtomicInteger()
        val checkedAt=java.util.concurrent.atomic.AtomicLong(now)
        if (artists.isNotEmpty()) SpotifyTokenProvider.ensureToken(context)
        artists.filter {it.id.isNotBlank()}.distinctBy {it.id}.map {artist->async {
            val key="music:${artist.id}";val stored=cachedRows(key)
            if(!force && stored!=null && now-stored.optLong("at")<cooldown) {
                checkedAt.updateAndGet {minOf(it,stored.optLong("at"))};return@async
            }
            permits.withPermit {
                try {
                    val overview=Spotify.artistOverview(artist.id).getOrThrow()
                    val rows=overview.popularReleases.mapNotNull { release ->
                        var releaseDate=release.releaseDate.orEmpty()
                        if(releaseDate.length==4 && releaseDate.toIntOrNull()==java.time.Year.now().value) {
                            releaseDate=cache.getString("album-date:${release.id}",null) ?: run {
                                Spotify.album(release.id).getOrNull()?.releaseDate.orEmpty().also {date->
                                    if(date.length>4)cache.edit().putString("album-date:${release.id}",date).apply()
                                }
                            }
                        }
                        val timestamp=publicationTime(releaseDate,now) ?: return@mapNotNull null
                        if(timestamp>now)return@mapNotNull null
                        FollowedMusicRelease(AlbumsModel(release.id.hashCode(),artist.name,release.images.firstOrNull()?.url.orEmpty(),release.name,
                            releaseDate,release.albumType.orEmpty(),release.id),timestamp)
                    }
                    results[artist.id]=rows
                    val wire=rows.map {JSONObject().put("id",it.album.id).put("artist",it.album.artists).put("cover",it.album.coverUri)
                        .put("name",it.album.name).put("date",it.album.time).put("type",it.album.type).put("spotifyId",it.album.spotifyAlbumId).put("published",it.publishedAt)}
                    cache.edit().putString(key,JSONObject().put("at",now).put("rows",JSONArray(wire)).toString()).apply();publish()
                }catch(c:CancellationException){throw c}catch(_:Exception){failures.incrementAndGet()}
            }
        }}.awaitAll()
        musicState.update {it.copy(loading=false,complete=failures.get()==0,checkedAt=checkedAt.get(),error=if(failures.get()>0)"Some artists could not be updated." else null)}
    }
}
