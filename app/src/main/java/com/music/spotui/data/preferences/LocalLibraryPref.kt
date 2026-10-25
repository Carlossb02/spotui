package com.music.spotui.data.preferences

import android.content.Context
import com.music.spotui.data.entity.SongsModel
import org.json.JSONArray
import org.json.JSONObject

/**
 * Persistent store for user-imported local audio files (folder / individual
 * songs). Each track is kept as its content URI plus the metadata read at import
 * time, and is surfaced as a [SongsModel] whose `url` is the URI itself — so it
 * plays through the normal player/queue like any other track.
 */
private const val PREF = "LocalLibrary"
private const val KEY = "tracks"

data class LocalTrack(
    val id: Int,
    val uri: String,
    val title: String,
    val artist: String,
    val album: String,
    val durationMs: Int,
    val coverUri: String,
)

/** Stable, non-negative id for a local file, matching the app's id convention. */
fun localTrackId(uri: String): Int = "local:$uri".hashCode() and 0x7fffffff

private fun prefs(context: Context) =
    context.getSharedPreferences(PREF, Context.MODE_PRIVATE)

private fun LocalTrack.toJson(): JSONObject = JSONObject().apply {
    put("id", id)
    put("uri", uri)
    put("title", title)
    put("artist", artist)
    put("album", album)
    put("durationMs", durationMs)
    put("coverUri", coverUri)
}

private fun parse(obj: JSONObject): LocalTrack? = runCatching {
    val uri = obj.optString("uri")
    if (uri.isBlank()) return@runCatching null
    LocalTrack(
        id = obj.optInt("id", localTrackId(uri)),
        uri = uri,
        title = obj.optString("title"),
        artist = obj.optString("artist"),
        album = obj.optString("album"),
        durationMs = obj.optInt("durationMs"),
        coverUri = obj.optString("coverUri"),
    )
}.getOrNull()

fun getLocalTracks(context: Context): List<LocalTrack> {
    val raw = prefs(context).getString(KEY, null) ?: return emptyList()
    return runCatching {
        val arr = JSONArray(raw)
        (0 until arr.length()).mapNotNull { parse(arr.getJSONObject(it)) }
    }.getOrDefault(emptyList())
}

/**
 * Ownership check for the metadata editor.
 * Spotify local-library records may have no regular Spotify track ID: the
 * importer stores spotify:local:... or a local_ fallback in spotifyTrackId.
 * These identities remain unchanged when the displayed artist/title changes.
 * A real Spotify catalogue track is NEVER editable merely because it had an
 * "Unknown Artist" value or a user-provided metadata override.
 */
fun isUserEditableLocalTrack(context: Context, song: SongsModel): Boolean {
    val spotifyId = song.spotifyTrackId.trim()
    val url = song.url.trim()

    // A genuine Spotify track ID is exactly 22 base-62 characters. Editing its
    // metadata in Spotui must remain impossible even if it says "Unknown Artist".
    val catalogId = spotifyId
        .removePrefix("spotify:track:")
        .removePrefix("track:")
    val isCatalogTrack = Regex("^[A-Za-z0-9]{22}$").matches(catalogId) ||
        Regex("""^spotify:track:[A-Za-z0-9]{22}(?:\||$)""").containsMatchIn(url)
    // The first edit was only authorized after checking the original local
    // source. Retain that authorization across versions where the track loses
    // its local flag or Spotify reserializes the URI after changing the artist.
    val previouslyVerifiedLocal = CustomMetadataPref.wasVerifiedLocalEdit(context, song)
    if (isCatalogTrack && !previouslyVerifiedLocal) return false

    val spotifyLocal = song.isLocalSpotifyTrack ||
        spotifyId.startsWith("spotify:local:", ignoreCase = true) ||
        spotifyId.startsWith("local_", ignoreCase = true) ||
        url.startsWith("spotify:local:", ignoreCase = true) ||
        url.startsWith("spotify:track:local_", ignoreCase = true)

    if (spotifyLocal || url.startsWith("yt_local:")) return true

    // An earlier, verified local edit is retained across changes to the artist,
    // title, image or search query.
    if (previouslyVerifiedLocal) return true

    // Backward compatibility: previous versions stored a metadata override but
    // did not save provenance. Never migrate an official Spotify catalogue ID.
    if (!isCatalogTrack && CustomMetadataPref.hasExistingEdit(context, song)) return true

    if (spotifyId.isNotBlank()) return false
    val fileBased = url.startsWith("content://") ||
        url.startsWith("file://") || url.startsWith("/")
    if (!fileBased) return false
    return getLocalTracks(context).any { track ->
        track.uri == url || (track.id == song.id && track.uri.isNotBlank())
    }
}

/** Add tracks, de-duplicating by URI (keeps insertion order, new ones appended). */
fun addLocalTracks(context: Context, tracks: List<LocalTrack>) {
    if (tracks.isEmpty()) return
    val existing = getLocalTracks(context)
    val seen = existing.mapTo(HashSet()) { it.uri }
    val merged = existing + tracks.filter { seen.add(it.uri) }
    save(context, merged)
}

fun removeLocalTrack(context: Context, uri: String) {
    save(context, getLocalTracks(context).filterNot { it.uri == uri })
}

fun updateLocalTrackMetadata(
    context: Context,
    song: SongsModel,
    newTitle: String,
    newArtist: String,
    newAlbum: String,
    newCoverUri: String
) {
    // Defensive second check. UI controls alone are not a permission boundary.
    if (!isUserEditableLocalTrack(context, song)) return
    CustomMetadataPref.markVerifiedLocalEdit(context, song)
    CustomMetadataPref.saveOverride(context, song, newTitle, newArtist, newAlbum, newCoverUri)

    // An edited Spotify-local track's playback search string includes the
    // editable title/artist. Save the *post-edit* alias as well as the original
    // stable ID so the menu remains editable even for old queue snapshots.
    val editedSong = song.copy(
        title = newTitle, singer = newArtist, album = newAlbum, coverUri = newCoverUri
    )
    val editedQuery = if (song.url.startsWith("spotify:track:") && song.spotifyTrackId.isNotBlank()) {
        com.music.spotui.di.SongPlayer.buildSpotifyPlayQuery(
            song.spotifyTrackId, newTitle, newArtist
        )
    } else song.url
    val updatedIdentity = editedSong.copy(url = editedQuery)
    CustomMetadataPref.markVerifiedLocalEdit(context, updatedIdentity)
    CustomMetadataPref.saveOverride(
        context, updatedIdentity, newTitle, newArtist, newAlbum, newCoverUri
    )

    val tracks = getLocalTracks(context)
    val songUri = song.url
    val songId = song.id
    val updatedTracks = tracks.map { track ->
        if ((songUri.isNotBlank() && track.uri == songUri) || track.id == songId) {
            track.copy(
                title = newTitle,
                artist = newArtist,
                album = newAlbum,
                coverUri = newCoverUri
            )
        } else track
    }
    save(context, updatedTracks)

    LocalPlaylistPref.updateSongInPlaylists(context, song, newTitle, newArtist, newAlbum, newCoverUri)
    PlaylistAdditionsPref.updateSong(context, song, newTitle, newArtist, newAlbum, newCoverUri)
    com.music.spotui.di.SongPlayer.updateTrackInQueue(song, newTitle, newArtist, newAlbum, newCoverUri)
    com.music.spotui.data.api.Api.HomeCache.clear()
}

fun clearLocalTracks(context: Context) {
    prefs(context).edit().remove(KEY).apply()
}

private fun save(context: Context, tracks: List<LocalTrack>) {
    val arr = JSONArray()
    tracks.forEach { arr.put(it.toJson()) }
    prefs(context).edit().putString(KEY, arr.toString()).apply()
}

fun LocalTrack.toSong(context: Context? = null): SongsModel {
    val base = SongsModel(
        id = id,
        title = title.ifBlank { "Unknown title" },
        album = album,
        singer = artist.ifBlank { "Unknown artist" },
        coverUri = coverUri,
        url = uri,
        spotifyTrackId = "",
        explicit = false,
        durationMs = durationMs,
    )
    return context?.let { CustomMetadataPref.applyOverride(it, base) } ?: base
}

fun getLocalSongs(context: Context): List<SongsModel> =
    getLocalTracks(context).map { it.toSong(context) }
