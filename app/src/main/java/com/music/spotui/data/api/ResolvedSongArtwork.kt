package com.music.spotui.data.api

import android.content.Context
import android.content.SharedPreferences
import android.net.Uri
import com.music.spotui.data.entity.SongsModel
import com.music.spotui.data.entity.libraryIdentity
import com.music.spotui.data.preferences.CustomMetadataPref
import com.music.spotui.di.SongPlayer
import com.music.spotui.di.StreamSelections
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
    const val PREF="resolved_song_artwork_v2"
    private val scope=CoroutineScope(SupervisorJob()+Dispatchers.IO)
    private val work=SharedWork<String,Unit>(scope)
    private val permits=Semaphore(2)
    private val changes=MutableStateFlow(0L)
    val revision=changes.asStateFlow()
    private val attempted=java.util.concurrent.ConcurrentHashMap<String,Long>()
    private val songs=java.util.concurrent.ConcurrentHashMap<String,SongsModel>()
    private val generations=java.util.concurrent.ConcurrentHashMap<String,java.util.concurrent.atomic.AtomicLong>()
    private val oldCovers=java.util.concurrent.ConcurrentHashMap<String,MutableSet<String>>()
    private val refreshImages=java.util.concurrent.ConcurrentHashMap.newKeySet<String>()
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
    private fun selectionKey(context: Context, song: SongsModel): String {
        val identity = song.libraryIdentity()
        val edit = CustomMetadataPref.getOverride(context,song) ?: return identity
        return "$identity|recording:${edit.updatedAt}"
    }
    fun isGenerated(context:Context,song:SongsModel):Boolean {
        if(CustomMetadataPref.getOverride(context,song)?.coverUri?.isNotBlank()==true)return false
        if(song.coverUri.isBlank())return true
        val key=song.libraryIdentity()
        val current=prefs(context).getString(key,null)?.let(::videoArtworkUrl)
        val verified = prefs(context).getString(selectionKey(context,song),null)?.let(::videoArtworkUrl)
        val legacy=context.getSharedPreferences("resolved_song_artwork_v1",0).getString(key,null)?.let(::videoArtworkUrl)
        return song.coverUri==current || song.coverUri==verified || song.coverUri==legacy || oldCovers[key]?.contains(song.coverUri)==true
    }
    fun invalidate(context:Context,song:SongsModel) {
        val key=song.libraryIdentity()
        generations.getOrPut(key){java.util.concurrent.atomic.AtomicLong()}.incrementAndGet()
        work.cancel(key);attempted.remove(key);refreshImages.add(key)
        val urls=oldCovers.getOrPut(key){java.util.concurrent.ConcurrentHashMap.newKeySet()}
        prefs(context).getString(key,null)?.let(::videoArtworkUrl)?.let(urls::add)
        songs.entries.removeIf {it.value.libraryIdentity()==key}
        val records = prefs(context)
        val editor = records.edit().remove(key)
        records.all.filterKeys { it.startsWith("$key|recording:") }.forEach { (storedKey, value) ->
            (value as? String)?.let(::videoArtworkUrl)?.let(urls::add)
            editor.remove(storedKey)
        }
        editor.apply()
        changes.update {it+1}
    }
    fun invalidateQuery(context:Context,query:String) {songs[query]?.let {invalidate(context,it)}}
    fun invalidateAll(context:Context) {
        prefs(context).all.forEach { (key,value)->
            (value as? String)?.let(::videoArtworkUrl)?.let {url->
                oldCovers.getOrPut(key){java.util.concurrent.ConcurrentHashMap.newKeySet()}.add(url)
                refreshImages.add(key)
                generations.getOrPut(key){java.util.concurrent.atomic.AtomicLong()}.incrementAndGet()
            }
        }
        songs.values.toList().distinctBy {it.libraryIdentity()}.forEach {invalidate(context,it)}
        work.cancelAll()
        prefs(context).edit().clear().apply()
        changes.update {it+1}
    }
    fun resolved(context:Context,query:String,videoId:String) {
        val song=songs[query] ?: return
        if(isGenerated(context,song))rememberVideo(context,song,videoId)
    }
    private fun rememberVideo(context:Context,song:SongsModel,id:String) {
        val url=videoArtworkUrl(id) ?: return
        val key=song.libraryIdentity()
        val selectionKey = selectionKey(context,song)
        val generation=generations.getOrPut(key){java.util.concurrent.atomic.AtomicLong()}.get()
        val prefs=prefs(context)
        prefs.getString(selectionKey,null)?.let(::videoArtworkUrl)?.let {oldCovers.getOrPut(key){java.util.concurrent.ConcurrentHashMap.newKeySet()}.add(it)}
        if(prefs.getString(selectionKey,null)!=id)prefs.edit().putString(selectionKey,id).apply()
        val ready={
            if (generations[key]?.get() == generation && prefs.getString(selectionKey,null) == id) {
                changes.update {it+1}
                SongPlayer.applyResolvedArtwork(song.url,url)
            }
        }
        if(refreshImages.remove(key))PlaybackArtwork.refresh(context.applicationContext,artworkKey(song,id),url,ready)
        else if(file(context,song,id).isFile)SongPlayer.applyResolvedArtwork(song.url,url)
        else PlaybackArtwork.ensure(context.applicationContext,artworkKey(song,id),url,ready)
    }
    fun preferred(context:Context,song:SongsModel):String {
        val id=prefs(context).getString(selectionKey(context,song),null)
        if(!isGenerated(context,song))return song.coverUri
        if(id==null)return ""
        val saved=file(context,song,id)
        return if(saved.isFile && saved.length()>0)Uri.fromFile(saved).buildUpon()
            .appendQueryParameter("v",saved.lastModified().toString()).build().toString() else ""
    }
    suspend fun ensure(context:Context,song:SongsModel) {
        if(song.url.startsWith("yt_ep:"))return
        val key=song.libraryIdentity()
        if(!isGenerated(context,song))return
        songs[song.url]=song
        val generation=generations.getOrPut(key){java.util.concurrent.atomic.AtomicLong()}.get()
        work.await(key) {
            SongPlayer.awaitAudioStartup()
            val prefs=prefs(context)
            val saved=prefs.getString(selectionKey(context,song),null)?.takeIf {videoArtworkUrl(it)!=null}
            val edit = CustomMetadataPref.getOverride(context,song)
            if (edit != null) {
                // Edited rows must never seed the metadata, audio or candidate caches.
                val alternative = com.music.spotui.data.preferences.getAlternativeStream(
                    context, com.music.spotui.data.preferences.alternativeStreamKey(song),
                )
                val selected = when {
                    alternative?.isLocal == true -> null
                    alternative?.isYouTube == true -> alternative.value
                    else -> SongPlayer.videoIdFromYouTubeLink(song.url.removePrefix("yt_local:")) ?: saved
                        ?: StreamSelections.get(context,song.url)
                            ?.takeIf { it.recordingMatch && it.selectedAt >= edit.updatedAt }?.videoId
                }
                if (!selected.isNullOrBlank() && generations[key]?.get()==generation) rememberVideo(context,song,selected)
                return@await
            }
            if(saved==null && System.currentTimeMillis()-(attempted[key] ?: 0)<60_000)return@await
            val id=saved ?: permits.withPermit {
                attempted[key]=System.currentTimeMillis()
                SongPlayer.artworkVideoId(song,context)
            } ?: return@await
            if(generations[key]?.get()==generation)rememberVideo(context,song,id)
        }
    }
}
