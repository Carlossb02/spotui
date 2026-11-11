package com.music.spotui.data.preferences

import android.content.Context
import com.music.spotui.data.entity.SongsModel
import com.music.spotui.data.entity.libraryIdentity
import org.json.JSONArray
import org.json.JSONObject

/** App-owned membership for local/YouTube tracks; synchronized as text through Drive. */
object PlaylistAdditionsPref {
    private const val PREF = "playlist_youtube_additions_v1"

    fun isManaged(song: SongsModel): Boolean = song.isLocalSpotifyTrack ||
        song.spotifyTrackId.startsWith("spotify:local:") || song.spotifyTrackId.startsWith("local_") ||
        song.url.startsWith("spotify:local:") || song.url.startsWith("spotify:track:local_") ||
        song.url.startsWith("yt_local:") || song.url.startsWith("content://") ||
        song.url.startsWith("file://") || song.url.startsWith("/") || song.url.startsWith("sync_pending:")

    fun isManaged(context:Context,song:SongsModel):Boolean = isManaged(song) ||
        CustomMetadataPref.wasVerifiedLocalEdit(context,song) ||
        (!isCatalogTrack(song) && CustomMetadataPref.hasExistingEdit(context,song))

    private fun isCatalogTrack(song:SongsModel):Boolean {
        val id=song.spotifyTrackId.removePrefix("spotify:track:").removePrefix("track:").substringBefore('|').substringBefore('?')
        return Regex("^[A-Za-z0-9]{22}$").matches(id) ||
            Regex("^spotify:track:[A-Za-z0-9]{22}(?:[|]|$)").containsMatchIn(song.url)
    }

    private fun canonicalId(value:String):String = value.trim().substringBefore('?')
        .removePrefix("https://open.spotify.com/playlist/").removePrefix("spotify:playlist:").removePrefix("playlist:")

    private fun document(context:Context, playlistId:String):JSONObject {
        val prefs=context.getSharedPreferences(PREF,0)
        val id=canonicalId(playlistId)
        val raw=prefs.getString(id,null) ?: prefs.all.keys.firstOrNull {canonicalId(it)==id}?.let {prefs.getString(it,null)} ?: return JSONObject()
        return runCatching {
            if(raw.trimStart().startsWith("["))JSONObject().put("songs",JSONArray(raw)) else JSONObject(raw)
        }.getOrDefault(JSONObject())
    }
    fun removedKeys(context:Context,playlistId:String):Set<String> {
        val rows=document(context,playlistId).optJSONArray("removed") ?: return emptySet()
        return (0 until rows.length()).mapNotNull {rows.optString(it).takeIf(String::isNotBlank)}.toSet()
    }
    fun tracks(context: Context, playlistId: String): List<SongsModel> {
        val arr=document(context,playlistId).optJSONArray("songs") ?: return emptyList()
        return (0 until arr.length()).mapNotNull { i -> runCatching {
            val obj=arr.getJSONObject(i);val url=obj.getString("url")
            SongsModel(id=obj.optInt("id",url.hashCode() and 0x7fffffff),title=obj.optString("title"),
                album=obj.optString("album"),singer=obj.optString("artist"),coverUri=obj.optString("cover"),url=url,
                spotifyTrackId=obj.optString("spotifyTrackId"),isLocalSpotifyTrack=obj.optBoolean("isLocalSpotifyTrack"),
                explicit=obj.optBoolean("explicit"),durationMs=obj.optInt("durationMs"),artistIds=obj.optString("artistIds"))
        }.getOrNull() }.map {CustomMetadataPref.applyOverride(context,it)}.distinctBy {it.libraryIdentity()}
    }
    @Synchronized fun add(context: Context, playlistId: String, song: SongsModel) {
        val key=song.libraryIdentity()
        val merged=tracks(context,playlistId).filterNot {it.libraryIdentity()==key}+song
        write(context,playlistId,merged,removedKeys(context,playlistId)-key)
    }
    @Synchronized fun remove(context:Context,playlistId:String,song:SongsModel) {
        val key=song.libraryIdentity()
        write(context,playlistId,tracks(context,playlistId).filterNot {it.libraryIdentity()==key},removedKeys(context,playlistId)+key)
    }
    @Synchronized fun remove(context: Context, playlistId: String, url: String) {
        val song=tracks(context,playlistId).firstOrNull {it.url==url}
        if(song!=null)remove(context,playlistId,song)
    }

    @Synchronized fun updateSong(context: Context, song: SongsModel, title: String, artist: String, album: String, cover: String) {
        val prefs = context.getSharedPreferences(PREF, Context.MODE_PRIVATE)
        prefs.all.keys.forEach { id ->
            val existing = tracks(context, id)
            if (existing.any { it.libraryIdentity() == song.libraryIdentity() }) {
                write(context, id, existing.map {
                    if (it.libraryIdentity() == song.libraryIdentity()) it.copy(title = title, singer = artist, album = album, coverUri = cover) else it
                })
            }
        }
    }

    private fun write(context: Context, playlistId: String, songs: List<SongsModel>, removed:Set<String> = removedKeys(context,playlistId)) {
        val arr = JSONArray()
        songs.distinctBy {it.libraryIdentity()}.forEach { song ->
            arr.put(JSONObject().apply {
                put("id",song.id)
                put("spotifyTrackId",song.spotifyTrackId)
                put("isLocalSpotifyTrack",song.isLocalSpotifyTrack)
                put("durationMs",song.durationMs)
                put("explicit",song.explicit)
                put("artistIds",song.artistIds)
                put("url", song.url)
                put("title", song.title)
                put("artist", song.singer)
                put("album", song.album)
                put("cover", song.coverUri)
            })
        }
        val prefs=context.getSharedPreferences(PREF,Context.MODE_PRIVATE)
        val id=canonicalId(playlistId)
        val editor=prefs.edit().putString(id,JSONObject().put("songs",arr).put("removed",JSONArray(removed.sorted())).toString())
        prefs.all.keys.filter {it!=id && canonicalId(it)==id}.forEach(editor::remove)
        editor.apply()
        com.music.spotui.data.api.SavedTrackLibrary.localChanged(context)
    }
}
