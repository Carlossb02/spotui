package com.music.spotui.data.api

import android.content.Context
import android.util.AtomicFile
import android.util.Log
import com.music.spotui.data.entity.ArtistsModel
import com.music.spotui.data.entity.SongsModel
import kotlinx.coroutines.*
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

/** Persistent library metadata outside cacheDir. One song record is shared by all its collections. */
internal object LibraryContentMemory {
    private val lock = Any()
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var account: String? = null
    private var tracks = mutableMapOf<String, SongsModel>()
    private var collections = mutableMapOf<String, List<String>>()
    private val projections = mutableMapOf<String, Pair<Long, List<SongsModel>>>()
    private fun projected(key: String): List<SongsModel>? {
        val ids = collections[key] ?: return null
        val revision = _revision.value
        projections[key]?.takeIf { it.first == revision }?.let { return it.second }
        return ids.mapNotNull { tracks[it] }.also { projections[key] = revision to it }
    }
    private var followed: List<ArtistsModel>? = null
    private data class LikeEdit(val saved: Boolean, val at: Long)
    private val pendingLikes = java.util.concurrent.ConcurrentHashMap<Pair<String, Int>, LikeEdit>()
    private var likeEdits = mutableMapOf<Int, LikeEdit>()
    private var recentAccount: String? = null
    private val recent = LinkedHashMap<Int, SongsModel>()
    private val diskWrites = Mutex()
    private data class Payload(val account: String, val songs: List<SongsModel>, val lists: Map<String, List<String>>,
        val artists: List<ArtistsModel>?, val edits: Map<Int, LikeEdit>)
    private var persistJob: Job? = null
    private var generation = 0L
    private val _revision = MutableStateFlow(0L)
    val revision = _revision.asStateFlow()
    private fun songKey(song: SongsModel) = song.spotifyTrackId.takeIf { it.isNotBlank() }?.let { "spotify:$it" }
        ?: song.url.takeIf { it.isNotBlank() }?.let { "url:$it" } ?: "id:${song.id}"
    private fun file(context: Context, session: String): File {
        val hash = java.security.MessageDigest.getInstance("SHA-256").digest(session.toByteArray()).joinToString("") { "%02x".format(it) }
        return File(context.filesDir, "library_contents_$hash.json")
    }
    private fun load(context: Context) {
        val session = SpotifySession.spDc(context)
        if (account == session) return
        persistJob?.cancel(); generation++; account = session
        tracks = mutableMapOf(); collections = mutableMapOf(); projections.clear(); followed = null; likeEdits = mutableMapOf()
        runCatching {
            val target = file(context, session)
            if (!target.exists() && !File(target.path + ".bak").exists()) return@runCatching
            val root = JSONObject(AtomicFile(target).openRead().bufferedReader().use { it.readText() })
            val songs = root.optJSONArray("songs") ?: JSONArray()
            for (i in 0 until songs.length()) { val song = decode(songs.getJSONObject(i)); tracks[songKey(song)] = song }
            val lists = root.optJSONObject("collections") ?: JSONObject()
            lists.keys().forEach { key -> val ids = lists.getJSONArray(key); collections[key] = (0 until ids.length()).map { ids.getString(it) } }
            root.optJSONArray("artists")?.let { a -> followed = (0 until a.length()).map { i -> val o=a.getJSONObject(i); ArtistsModel(o.getString("name"),o.optString("cover"),o.optString("id")) } }
            val edits = root.optJSONArray("likeEdits") ?: JSONArray()
            for (i in 0 until edits.length()) { val o=edits.getJSONObject(i); likeEdits[o.getInt("id")] = LikeEdit(o.getBoolean("saved"),o.getLong("at")) }
        }.onFailure { Log.w("LibraryMemory", "Could not restore library content", it) }
    }
    fun register(context: Context, song: SongsModel) = synchronized(lock) {
        val session = SpotifySession.spDc(context)
        if (recentAccount != session) { recentAccount = session; recent.clear() }
        recent.remove(song.id); recent[song.id] = song
        while (recent.size > 512) recent.remove(recent.keys.first())
    }
    fun peek(context: Context, key: String): List<SongsModel>? = synchronized(lock) {
        if (account != SpotifySession.spDc(context)) null else projected(key)
    }
    fun read(context: Context, key: String): List<SongsModel>? = synchronized(lock) { load(context); projected(key) }
    fun playlistSongs(context: Context, id: String, remote: List<SongsModel>): List<SongsModel> {
        val writes = SpotifyPendingWrites.entries(context).filter { it.kind == "playlist" && it.target == id }
        val intents=SavedTrackLibrary.playlistEdits(id,writes.associate {it.track to it.saved})
        if (intents.isEmpty()) return remote
        val models = synchronized(lock) {
            load(context)
            (tracks.values + recent.values.takeIf { recentAccount == SpotifySession.spDc(context) }.orEmpty()).associateBy { it.spotifyTrackId }
        }
        return remote.toMutableList().apply {
            intents.forEach { (track,saved) ->
                if (!saved) removeAll { it.spotifyTrackId == track && !com.music.spotui.data.preferences.PlaylistAdditionsPref.isManaged(context,it) }
                else if (none { it.spotifyTrackId == track }) models[track]?.takeUnless {com.music.spotui.data.preferences.PlaylistAdditionsPref.isManaged(context,it)}?.let(::add)
            }
        }
    }
    fun put(context: Context, key: String, songs: List<SongsModel>, expectedAccount: String) = synchronized(lock) {
        if (SpotifySession.spDc(context) != expectedAccount) return@synchronized
        load(context)
        songs.forEach { tracks[songKey(it)] = it }
        collections[key] = songs.map(::songKey)
        changed(context)
    }
    fun artists(context: Context): List<ArtistsModel>? = synchronized(lock) { load(context); followed }
    fun putArtists(context: Context, artists: List<ArtistsModel>, expectedAccount: String) = synchronized(lock) {
        if (SpotifySession.spDc(context) == expectedAccount) { load(context); followed = artists; changed(context) }
    }
    fun noteLike(context: Context, songId: Int, saved: Boolean) {
        val app = context.applicationContext
        val session = SpotifySession.spDc(app)
        val intent = LikeEdit(saved, System.currentTimeMillis())
        pendingLikes[session to songId] = intent
        scope.launch {
            synchronized(lock) {
                if (SpotifySession.spDc(app) != session) return@launch
                load(app)
                val latest = pendingLikes[session to songId] ?: return@launch
                likeEdits[songId] = latest
                val known = tracks.values.firstOrNull { it.id == songId }
                    ?: recent[songId]?.takeIf { recentAccount == session }
                if (latest.saved && known != null) com.music.spotui.data.preferences.LocalLikedSongsPref.save(app, known)
                if (!latest.saved) com.music.spotui.data.preferences.LocalLikedSongsPref.remove(app, songId)
                val liked = collections["liked"].orEmpty().toMutableList()
                if (latest.saved && known != null) { tracks[songKey(known)] = known; liked.remove(songKey(known)); liked.add(0,songKey(known)) }
                if (!latest.saved) liked.removeAll { tracks[it]?.id == songId }
                collections["liked"] = liked
                val editor = app.getSharedPreferences("LikedSongs", Context.MODE_PRIVATE).edit()
                if (latest.saved) editor.putString(songId.toString(), songId.toString()) else editor.remove(songId.toString())
                editor.apply()
                changed(app)
                com.music.spotui.data.preferences.LikedSongChanges.changed()
            }
        }
    }
    fun seedLocalLikes(context: Context) {
        val songs = synchronized(lock) {
            load(context)
            collections["liked"].orEmpty().mapNotNull { tracks[it] }.filter {
                (it.spotifyTrackId.isBlank() || it.isLocalSpotifyTrack || it.url.startsWith("yt_local:")) &&
                    com.music.spotui.data.preferences.isSongLiked(context, it.id.toString())
            }
        }
        songs.forEach { com.music.spotui.data.preferences.LocalLikedSongsPref.save(context, it) }
    }
    fun confirmLike(context: Context, spotifyId: String, saved: Boolean) = synchronized(lock) {
        load(context)
        val song = (tracks.values + recent.values.takeIf { recentAccount == account }.orEmpty())
            .firstOrNull { it.spotifyTrackId == spotifyId } ?: return@synchronized
        val key = account.orEmpty() to song.id
        // A confirmation from an older tap cannot replace a newer local intent.
        val effective = pendingLikes.compute(key) { _, old ->
            if (old != null && old.saved != saved) old else LikeEdit(saved, System.currentTimeMillis())
        } ?: return@synchronized
        likeEdits[song.id] = effective
        changed(context)
    }
    fun mergeLiked(context: Context, remote: List<SongsModel>, expectedAccount: String): List<SongsModel> = synchronized(lock) {
        if (SpotifySession.spDc(context) != expectedAccount) return@synchronized emptyList()
        load(context)
        pendingLikes.filterKeys { it.first == expectedAccount }.forEach { (key, value) -> likeEdits[key.second] = value }
        val durableLikes = SpotifyPendingWrites.entries(context).filter { it.kind == "track" }
        // Pending offline intent has no expiry until Spotify confirms the write.
        durableLikes.forEach { edit ->
            (remote + tracks.values).firstOrNull { it.spotifyTrackId == edit.target }?.let {
                likeEdits[it.id] = LikeEdit(edit.saved, System.currentTimeMillis())
            }
        }
        val remoteIds = remote.map { it.id }.toSet()
        val now = System.currentTimeMillis()
        likeEdits.entries.removeAll { (id, edit) ->
            val done = (id in remoteIds) == edit.saved || now - edit.at > 120_000L
            if (done) pendingLikes.remove(expectedAccount to id, edit)
            done
        }
        val merged = remote.filterNot { likeEdits[it.id]?.saved == false }.toMutableList()
        val local = collections["liked"].orEmpty().mapNotNull { tracks[it] }
        local.filter { ((it.spotifyTrackId.isBlank() || it.isLocalSpotifyTrack) && com.music.spotui.data.preferences.isSongLiked(context, it.id.toString())) || likeEdits[it.id]?.saved == true }.forEach { if (merged.none { song -> song.id == it.id }) merged.add(it) }
        remote.forEach { tracks[songKey(it)] = it }
        merged.forEach { tracks[songKey(it)] = it }
        collections["liked"] = merged.map(::songKey)
        val registry = context.getSharedPreferences("LikedSongs", Context.MODE_PRIVATE)
        val editor = registry.edit()
        val keepIds = merged.map { it.id }.toSet() + likeEdits.filterValues { it.saved }.keys
        local.filter { it.spotifyTrackId.isNotBlank() && !it.isLocalSpotifyTrack && it.id !in keepIds }
            .forEach { editor.remove(it.id.toString()) }
        merged.forEach { editor.putString(it.id.toString(), it.id.toString()) }
        editor.apply()
        changed(context)
        com.music.spotui.data.preferences.LikedSongChanges.changed()
        merged
    }
    private fun changed(context: Context) {
        _revision.value++
        val token = generation
        persistJob?.cancel()
        persistJob = scope.launch {
            delay(250L)
            val payload = synchronized(lock) {
                if (token != generation || account != SpotifySession.spDc(context)) return@launch
                val used = collections.values.flatten().toSet()
                tracks.keys.retainAll(used)
                Payload(account.orEmpty(), tracks.values.toList(), collections.toMap(), followed?.toList(), likeEdits.toMap())
            }
            val songs = JSONArray(); payload.songs.forEach { songs.put(encode(it)) }
            val lists = JSONObject(); payload.lists.forEach { (key, ids) -> lists.put(key, JSONArray(ids)) }
            val root = JSONObject().put("songs",songs).put("collections",lists)
            payload.artists?.let { artists -> val a=JSONArray();artists.forEach { a.put(JSONObject().put("id",it.id).put("name",it.name).put("cover",it.coverUri)) };root.put("artists",a) }
            val edits=JSONArray();payload.edits.forEach { (id,e)->edits.put(JSONObject().put("id",id).put("saved",e.saved).put("at",e.at)) };root.put("likeEdits",edits)
            diskWrites.withLock {
                currentCoroutineContext().ensureActive()
                val atomic=AtomicFile(file(context,payload.account))
                runCatching {
                    val stream=atomic.startWrite()
                    try {stream.write(root.toString().toByteArray(Charsets.UTF_8));atomic.finishWrite(stream)}
                    catch (e:Throwable){atomic.failWrite(stream);throw e}
                }.onFailure { Log.w("LibraryMemory","Could not save library content",it) }
            }
        }
    }
    private fun encode(s: SongsModel) = JSONObject().put("id",s.id).put("title",s.title).put("album",s.album).put("singer",s.singer)
        .put("cover",s.coverUri).put("url",s.url).put("spotify",s.spotifyTrackId).put("explicit",s.explicit).put("duration",s.durationMs)
        .put("artists",s.artistIds).put("description",s.description).put("releaseDate",s.releaseDate).put("local",s.isLocalSpotifyTrack)
    private fun decode(o: JSONObject) = SongsModel(o.getInt("id"),o.getString("title"),o.optString("album"),o.optString("singer"),o.optString("cover"),o.getString("url"),
        spotifyTrackId=o.optString("spotify"),explicit=o.optBoolean("explicit"),durationMs=o.optInt("duration"),artistIds=o.optString("artists"),
        description=o.optString("description"),releaseDate=o.optString("releaseDate"),isLocalSpotifyTrack=o.optBoolean("local"))
}
