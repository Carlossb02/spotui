package com.music.spotui.sync

import com.music.spotui.data.entity.SongsModel
import org.json.JSONArray
import org.json.JSONObject

/** One queue per revision; frequent checkpoints contain only the clock and controls. */
internal data class DevicePlaybackQueue(
    val songs:List<SongsModel>, val manual:Set<Int> = emptySet(), val original:List<Int> = emptyList(),
) {
    fun json()=JSONObject().put("songs",JSONArray().also { a->songs.forEach { a.put(songJson(it)) } })
        .put("manual",JSONArray(manual.sorted())).put("original",JSONArray(original))
    companion object {
        fun parse(o:JSONObject):DevicePlaybackQueue {
            val a=o.getJSONArray("songs")
            fun indices(key:String):List<Int> {val v=o.optJSONArray(key)?:return emptyList();return (0 until v.length()).map { v.getInt(it) }.filter { it in 0 until a.length() }}
            return DevicePlaybackQueue((0 until a.length()).map { song(o=a.getJSONObject(it)) },indices("manual").toSet(),indices("original"))
        }
        private fun songJson(s:SongsModel)=JSONObject().put("id",s.id).put("title",s.title).put("album",s.album).put("singer",s.singer)
            .put("coverUri",s.coverUri).put("url",s.url).put("spotifyTrackId",s.spotifyTrackId).put("explicit",s.explicit).put("durationMs",s.durationMs)
            .put("artistIds",s.artistIds).put("description",s.description).put("releaseDate",s.releaseDate).put("isLocalSpotifyTrack",s.isLocalSpotifyTrack)
        private fun song(o:JSONObject)=SongsModel(o.getInt("id"),o.optString("title"),o.optString("album"),o.optString("singer"),o.optString("coverUri"),o.getString("url"),
            o.optString("spotifyTrackId"),o.optBoolean("explicit"),o.optInt("durationMs"),o.optString("artistIds"),o.optString("description"),o.optString("releaseDate"),o.optBoolean("isLocalSpotifyTrack"))
    }
}

internal data class DevicePlaybackState(
    val owner:String, val name:String, val epoch:String, val queueId:String, val index:Int,
    val position:Long, val duration:Long, val playing:Boolean, val updated:Long,
    val shuffle:Boolean=false, val repeat:String="OFF", val source:String="",
    val startToken:String="", val from:String="",
) {
    fun positionAt(now:Long):Long {
        val elapsed=if(playing)(now-updated).coerceIn(0,15_000)else 0L
        val target=(position.coerceAtLeast(0)+elapsed).coerceAtLeast(0)
        return if(duration>0)target.coerceAtMost(duration)else target
    }
    fun fresh(now:Long)=now-updated in -5_000L..15_000L
    fun canStart(device:String,now:Long)=owner==device && startToken.isNotBlank() && fresh(now)
    fun json()=JSONObject().put("owner",owner).put("name",name).put("epoch",epoch).put("queueId",queueId).put("index",index)
        .put("position",position).put("duration",duration).put("playing",playing).put("updated",updated)
        .put("shuffle",shuffle).put("repeat",repeat).put("source",source).put("startToken",startToken).put("from",from)
    companion object {
        fun parse(o:JSONObject)=DevicePlaybackState(o.getString("owner"),o.getString("name"),o.getString("epoch"),o.getString("queueId"),o.getInt("index"),
            o.optLong("position").coerceAtLeast(0),o.optLong("duration").coerceAtLeast(0),o.optBoolean("playing"),o.getLong("updated"),
            o.optBoolean("shuffle"),o.optString("repeat","OFF"),o.optString("source"),o.optString("startToken"),o.optString("from"))
    }
}
