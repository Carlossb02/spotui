package com.music.spotui.data.preferences

import android.content.Context
import com.music.spotui.data.entity.SongsModel
import org.json.JSONObject

/** Metadata only for local liked recordings that Spotify cannot restore on another device. */
internal object LocalLikedSongsPref {
    private data class Memory(val prefs:android.content.SharedPreferences,val raw:Map<String,*>,val songs:List<SongsModel>)
    @Volatile private var memory:Memory?=null
    fun save(context:Context,song:SongsModel) {
        if(!(song.spotifyTrackId.isBlank() || song.isLocalSpotifyTrack || song.url.startsWith("yt_local:")))return
        val o=JSONObject().put("id",song.id).put("title",song.title).put("album",song.album).put("singer",song.singer).put("coverUri",song.coverUri)
            .put("url",song.url).put("spotifyTrackId",song.spotifyTrackId).put("isLocalSpotifyTrack",song.isLocalSpotifyTrack).put("explicit",song.explicit).put("durationMs",song.durationMs).put("artistIds",song.artistIds)
        context.getSharedPreferences("OwnedLikedSongs",0).edit().putString(song.id.toString(),o.toString()).apply()
    }
    fun remove(context:Context,id:Int){context.getSharedPreferences("OwnedLikedSongs",0).edit().remove(id.toString()).apply()}
    @Synchronized fun songs(context:Context):List<SongsModel> {
        val prefs=context.getSharedPreferences("OwnedLikedSongs",0);val raw=prefs.all
        memory?.takeIf { it.prefs===prefs && it.raw==raw }?.let { return it.songs }
        val songs=raw.values.mapNotNull { value->runCatching {
            val o=JSONObject(value as String)
            SongsModel(o.getInt("id"),o.getString("title"),o.optString("album"),o.optString("singer"),o.optString("coverUri"),o.getString("url"),
                spotifyTrackId=o.optString("spotifyTrackId"),isLocalSpotifyTrack=o.optBoolean("isLocalSpotifyTrack"),explicit=o.optBoolean("explicit"),durationMs=o.optInt("durationMs"),artistIds=o.optString("artistIds"))
        }.getOrNull() }
        memory=Memory(prefs,raw,songs);return songs
    }
}
