package com.music.spotui.data.api

import android.content.Context
import android.content.SharedPreferences
import android.net.Uri
import com.music.spotui.data.entity.SongsModel
import com.music.spotui.data.entity.libraryIdentity
import com.music.spotui.di.SongPlayer
import com.music.spotui.ui.notification.PlaybackArtwork
import com.music.spotui.util.SharedWork
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import java.io.File

internal fun videoArtworkUrl(id:String):String? = id.takeIf {it.matches(Regex("[A-Za-z0-9_-]{11}"))}
    ?.let {"https://i.ytimg.com/vi/$it/hqdefault.jpg"}

/** Only video IDs enter sync. Image bytes stay in the durable local artwork cache. */
internal object ResolvedSongArtwork {
    const val PREF="resolved_song_artwork_v1"
    private val scope=CoroutineScope(SupervisorJob()+Dispatchers.IO)
    private val work=SharedWork<String,Unit>(scope)
    private val permits=Semaphore(2)
    private val changes=MutableStateFlow(0L)
    val revision=changes.asStateFlow()
    private val attempted=java.util.concurrent.ConcurrentHashMap<String,Long>()
    private var observed:SharedPreferences?=null
    private val listener=SharedPreferences.OnSharedPreferenceChangeListener {_,_->changes.update {it+1}}
    @Synchronized private fun prefs(context:Context):SharedPreferences {
        val prefs=context.applicationContext.getSharedPreferences(PREF,0)
        if(observed!==prefs){observed?.unregisterOnSharedPreferenceChangeListener(listener);observed=prefs;prefs.registerOnSharedPreferenceChangeListener(listener)}
        return prefs
    }
    private fun artworkKey(song:SongsModel,id:String)="yt_art_"+java.security.MessageDigest.getInstance("SHA-256")
        .digest((song.libraryIdentity()+"|"+id).toByteArray()).joinToString(""){"%02x".format(it)}
    private fun file(context:Context,song:SongsModel,id:String)=File(File(context.filesDir,"downloads"),"${artworkKey(song,id)}_cover.jpg")
    fun preferred(context:Context,song:SongsModel):String {
        val id=prefs(context).getString(song.libraryIdentity(),null)
        val fallback=id?.let(::videoArtworkUrl)
        if(song.coverUri.isNotBlank() && song.coverUri!=fallback)return song.coverUri
        if(id==null)return ""
        val saved=file(context,song,id)
        return if(saved.isFile && saved.length()>0)Uri.fromFile(saved).toString() else ""
    }
    suspend fun ensure(context:Context,song:SongsModel) {
        if(song.url.startsWith("yt_ep:"))return
        val key=song.libraryIdentity()
        val existing=prefs(context).getString(key,null)?.let(::videoArtworkUrl)
        if(song.coverUri.isNotBlank() && song.coverUri!=existing)return
        work.await(key) {
            SongPlayer.awaitAudioStartup()
            val prefs=prefs(context)
            val saved=prefs.getString(key,null)?.takeIf {videoArtworkUrl(it)!=null}
            if(saved==null && System.currentTimeMillis()-(attempted[key] ?: 0)<60_000)return@await
            val id=saved ?: permits.withPermit {
                attempted[key]=System.currentTimeMillis()
                SongPlayer.artworkVideoId(song,context)
            } ?: return@await
            val url=videoArtworkUrl(id) ?: return@await
            if(saved!=id)prefs.edit().putString(key,id).apply()
            val ready={
                changes.update {it+1}
                SongPlayer.applyResolvedArtwork(song.url,url)
            }
            if(file(context,song,id).isFile)withContext(Dispatchers.Main){SongPlayer.applyResolvedArtwork(song.url,url)}
            else PlaybackArtwork.ensure(context.applicationContext,artworkKey(song,id),url,ready)
        }
    }
}
