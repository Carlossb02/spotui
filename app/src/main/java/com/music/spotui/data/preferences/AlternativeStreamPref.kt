package com.music.spotui.data.preferences

import android.content.Context
import android.net.Uri
import com.music.spotui.data.entity.SongsModel
import org.json.JSONObject

data class AlternativeStream(
    val type: String,
    val value: String,
    val label: String = "",
) {
    val isYouTube: Boolean get() = type == TYPE_YOUTUBE
    val isLocal: Boolean get() = type == TYPE_LOCAL

    companion object {
        const val TYPE_YOUTUBE = "youtube"
        const val TYPE_LOCAL = "local"
    }
}

private const val PREF = "AlternativeStreams"
// Entries are checked against preference bytes so remote edits and removals are visible immediately.
private data class AlternativeMemo(val raw:String?,val stream:AlternativeStream?)
private val hotStreams = object : LinkedHashMap<String, AlternativeMemo>(128, .75f, true) {
    override fun removeEldestEntry(eldest:MutableMap.MutableEntry<String,AlternativeMemo>?):Boolean = size>200
}
private val hotLock=Any()

private fun cleanAlternativeSpotifyId(id:String)=id.removePrefix("spotify:track:").removePrefix("track:")
    .substringBefore('|').substringBefore('?').trim()
internal fun canonicalAlternativeStreamKey(key:String):String =
    if(key.startsWith("spotify:"))"spotify:"+cleanAlternativeSpotifyId(key.removePrefix("spotify:")) else key

fun alternativeStreamKey(song:SongsModel):String = song.spotifyTrackId.takeIf {it.isNotBlank()}
    ?.let {alternativeStreamKeyForSpotifyId(it)} ?: "song:${song.id}"
fun alternativeStreamKeyForSpotifyId(spotifyTrackId:String):String = "spotify:"+cleanAlternativeSpotifyId(spotifyTrackId)

private fun AlternativeStream.toJson(): String = JSONObject().apply {
    put("type", type)
    put("value", value)
    put("label", label)
}.toString()

private fun parseAlternativeStream(raw: String): AlternativeStream? = runCatching {
    val obj = JSONObject(raw)
    AlternativeStream(
        type = obj.optString("type"),
        value = obj.optString("value"),
        label = obj.optString("label"),
    ).takeIf { it.value.isNotBlank() && (it.isYouTube || it.isLocal) }
}.getOrNull()

fun getAlternativeStream(context:Context,key:String):AlternativeStream? {
    val canonical=canonicalAlternativeStreamKey(key)
    val prefs=context.getSharedPreferences(PREF,Context.MODE_PRIVATE)
    val raw=prefs.getString(canonical,null) ?: prefs.all.keys.firstOrNull {canonicalAlternativeStreamKey(it)==canonical}?.let {prefs.getString(it,null)}
    synchronized(hotLock) {hotStreams[canonical]?.takeIf {it.raw==raw}?.let {return it.stream}}
    val stream=raw?.let(::parseAlternativeStream)
    synchronized(hotLock) {hotStreams[canonical]=AlternativeMemo(raw,stream)}
    return stream
}

private fun writeAlternativeStream(context:Context,key:String,stream:AlternativeStream?) {
    val canonical=canonicalAlternativeStreamKey(key)
    val prefs=context.getSharedPreferences(PREF,Context.MODE_PRIVATE)
    val editor=prefs.edit()
    if(stream==null)editor.remove(canonical)else editor.putString(canonical,stream.toJson())
    prefs.all.keys.filter {it!=canonical && canonicalAlternativeStreamKey(it)==canonical}.forEach(editor::remove)
    synchronized(hotLock) {hotStreams.remove(canonical)}
    editor.apply()
}

fun setYouTubeAlternativeStream(context:Context,key:String,videoId:String) =
    writeAlternativeStream(context,key,AlternativeStream(AlternativeStream.TYPE_YOUTUBE,videoId,"YouTube video"))

fun setLocalAlternativeStream(context:Context,key:String,uri:Uri,label:String="") =
    writeAlternativeStream(context,key,AlternativeStream(AlternativeStream.TYPE_LOCAL,uri.toString(),label.ifBlank {uri.lastPathSegment.orEmpty()}))

fun clearAlternativeStream(context:Context,key:String) = writeAlternativeStream(context,key,null)

fun clearAllAlternativeStreams(context: Context) {
    synchronized(hotLock) { hotStreams.clear() }
    context.getSharedPreferences(PREF, Context.MODE_PRIVATE)
        .edit()
        .clear()
        .apply()
}

fun getAllAlternativeStreams(context: Context): Map<String, AlternativeStream> {
    val prefs = context.getSharedPreferences(PREF, Context.MODE_PRIVATE)
    val map = mutableMapOf<String, AlternativeStream>()
    for ((key, value) in prefs.all) {
        if (value is String) {
            parseAlternativeStream(value)?.let { stream ->
                val canonical=canonicalAlternativeStreamKey(key)
                if(canonical !in map || canonical==key)map[canonical] = stream
            }
        }
    }
    return map
}

fun restoreAlternativeStreams(context: Context, streams: Map<String, AlternativeStream>) {
    synchronized(hotLock) { hotStreams.clear() }
    val editor = context.getSharedPreferences(PREF, Context.MODE_PRIVATE).edit()
    for ((key, stream) in streams) {
        editor.putString(canonicalAlternativeStreamKey(key), stream.toJson())
    }
    editor.apply()
}


/** Remote preference changes invalidate the bounded playback lookup. */
internal fun invalidateAlternativeStreamMemory() = synchronized(hotLock) { hotStreams.clear() }
