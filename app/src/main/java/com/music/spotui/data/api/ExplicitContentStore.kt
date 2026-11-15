package com.music.spotui.data.api

import android.content.Context
import com.music.spotui.util.SharedWork
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import com.music.spotui.data.entity.SongsModel

internal fun ratingSpotifyId(song:SongsModel):String = song.spotifyTrackId.removePrefix("spotify:track:")
    .removePrefix("track:").substringBefore('|').substringBefore('?').takeIf {it.matches(Regex("[A-Za-z0-9]{22}"))}
    ?: Regex("^spotify:track:([A-Za-z0-9]{22})(?:[|]|$)").find(song.url)?.groupValues?.get(1).orEmpty()

/** Missing catalog fields are unknown, rather than evidence for a clean recording. */
internal fun publicSpotifyExplicit(html:String,id:String):Boolean? {
    val json=Regex("""(?s)<script[^>]*id=["']__NEXT_DATA__["'][^>]*>(.*?)</script>""").find(html)?.groupValues?.get(1) ?: return null
    return runCatching {
        val entity=JSONObject(json).getJSONObject("props").getJSONObject("pageProps").getJSONObject("state")
            .getJSONObject("data").getJSONObject("entity")
        if(entity.optString("id")!=id || !entity.has("isExplicit"))null else entity.getBoolean("isExplicit")
    }.getOrNull()
}

internal object ExplicitContentStore {
    private val state=MutableStateFlow<Map<String,Boolean>>(emptyMap())
    val ratings=state.asStateFlow()
    private val scope=CoroutineScope(SupervisorJob()+Dispatchers.IO)
    private val work=SharedWork<String,Boolean?>(scope)
    private val permits=Semaphore(2)
    private val failed=java.util.concurrent.ConcurrentHashMap<String,Long>()
    fun known(context:Context,id:String):Boolean? {
        state.value[id]?.let {return it}
        val prefs=context.getSharedPreferences("spotify_content_rating_v1",0)
        if(!prefs.contains(id))return null
        return prefs.getBoolean(id,false).also {value->state.update {it+(id to value)}}
    }
    fun remember(context:Context,id:String,explicit:Boolean) {
        if(id.length!=22)return
        if(known(context,id)==explicit)return
        state.update {it+(id to explicit)}
        context.getSharedPreferences("spotify_content_rating_v1",0).edit().putBoolean(id,explicit).apply()
        com.music.spotui.data.preferences.LocalPlaylistPref.repairContentRating(context,id,explicit)
    }
    suspend fun ensure(context:Context,id:String):Boolean? {
        if(!id.matches(Regex("[A-Za-z0-9]{22}")))return null
        known(context,id)?.let {return it}
        if(System.currentTimeMillis()-(failed[id] ?: 0)<60_000)return null
        return work.await(id) {
            known(context,id) ?: permits.withPermit {
                val value=withContext(Dispatchers.IO) {
                    val connection=(URL("https://open.spotify.com/embed/track/$id").openConnection() as HttpURLConnection).apply {
                        connectTimeout=3500;readTimeout=3500
                        setRequestProperty("User-Agent","Mozilla/5.0")
                    }
                    try {if(connection.responseCode==200)publicSpotifyExplicit(connection.inputStream.bufferedReader().use {it.readText()},id) else null}
                    catch(_:java.io.IOException){null} finally {connection.disconnect()}
                }
                if(value==null)failed[id]=System.currentTimeMillis() else remember(context,id,value)
                value
            }
        }
    }
}
