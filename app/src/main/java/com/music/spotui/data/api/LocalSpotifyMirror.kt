package com.music.spotui.data.api

import android.content.Context
import android.content.SharedPreferences
import com.metrolist.spotify.Spotify
import com.metrolist.spotify.models.SpotifyPlaylist
import com.metrolist.spotify.models.SpotifyPlaylistTrack
import com.music.spotui.data.entity.SongsModel
import com.music.spotui.data.preferences.LocalPlaylist
import com.music.spotui.data.preferences.LocalPlaylistPref
import kotlinx.coroutines.*
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import org.json.JSONArray
import org.json.JSONObject

/** Account-bound, recoverable synchronization. Local-only audio is never uploaded or matched by name. */
object LocalSpotifyMirror {
    private const val PREF="local_spotify_mirrors_v2"
    private val scope=CoroutineScope(SupervisorJob()+Dispatchers.IO)
    private val gate=Mutex()
    private val wake=Channel<Unit>(Channel.CONFLATED)
    private var installed=false
    private var listener: SharedPreferences.OnSharedPreferenceChangeListener?=null
    private val forced=java.util.concurrent.ConcurrentHashMap.newKeySet<String>()
    private val checked=java.util.concurrent.ConcurrentHashMap<String,Long>()
    private var identitySession=""
    private var identity=""
    private val detailsCache=mutableMapOf<String,SpotifyPlaylist>()
    enum class Phase { SYNCING, SYNCED, FAILED }
    data class SyncStatus(val phase:Phase,val details:String="")
    val statuses=MutableStateFlow<Map<String,SyncStatus>>(emptyMap())
    private fun status(id:String,phase:Phase,details:String="") {statuses.update {it+(id to SyncStatus(phase,details))}}
    private fun prefs(c:Context)=c.getSharedPreferences(PREF,0)
    private fun state(c:Context,owner:String,id:String):JSONObject = prefs(c).getString(owner+":"+id,null)?.let(::JSONObject) ?: JSONObject()
    private fun save(c:Context,owner:String,id:String,s:JSONObject) {check(prefs(c).edit().putString(owner+":"+id,s.toString()).commit()) {"No se pudo guardar el progreso de sincronización"}}
    private fun rows(s:JSONObject,key:String):List<String>? = s.optJSONArray(key)?.let {a->(0 until a.length()).map {a.getString(it)}}
    fun spotifyId(c:Context,id:String):String? = LocalPlaylistPref.getLocalPlaylist(c,id)?.spotifyPlaylistId?.takeIf {it.isNotBlank()}
    fun install(context:Context) {
        val app=context.applicationContext
        synchronized(this) {if(installed)return;installed=true}
        listener=SharedPreferences.OnSharedPreferenceChangeListener {_,_->kick(app)}.also {l->
            app.getSharedPreferences("LocalPlaylists",0).registerOnSharedPreferenceChangeListener(l)
            app.getSharedPreferences("spotify_session",0).registerOnSharedPreferenceChangeListener(l)
        }
        scope.launch {delay(8_000);kick(app);for(signal in wake) {delay(600);runCatching {drain(app)}.onFailure {if(it is CancellationException)throw it}}}
        scope.launch {while(isActive) {delay(300_000);val info=android.app.ActivityManager.RunningAppProcessInfo();android.app.ActivityManager.getMyMemoryState(info)
            if(info.importance<=android.app.ActivityManager.RunningAppProcessInfo.IMPORTANCE_FOREGROUND)kick(app)}}
    }
    fun kick(context:Context) {if(!installed)install(context);wake.trySend(Unit)}
    fun refresh(context:Context,id:String) {forced.add(id);kick(context)}
    fun retry(context:Context,id:String) {forced.add(id);kick(context)}
    fun onCreated(context:Context,id:String)=retry(context,id)
    fun onSongChanged(context:Context,id:String,song:SongsModel,added:Boolean)=retry(context,id)
    fun onRenamed(context:Context,id:String,name:String)=retry(context,id)

    private suspend fun drain(c:Context)=gate.withLock {
        val locals=LocalPlaylistPref.getLocalPlaylists(c)
        if(locals.isEmpty() && prefs(c).all.isEmpty())return@withLock
        if(!SpotifyTokenProvider.ensureToken(c)) {locals.forEach {status(it.id,Phase.FAILED,"Conecta Spotify para sincronizar esta lista")};return@withLock}
        val session=SpotifySession.spDc(c)
        com.music.spotui.di.SongPlayer.awaitAudioStartup()
        val me=Spotify.me().getOrThrow().id
        require(me.isNotBlank()) {"Spotify no devolvió la cuenta"}
        if(identitySession!=session){identitySession=session;identity=me;detailsCache.clear();checked.clear()}
        check(identity==me && SpotifySession.spDc(c)==session)
        var catalog: Set<String>? = null
        for(local in locals) {
            if(SpotifySession.spDc(c)!=session)return@withLock
            if(local.spotifyOwnerId.isNotBlank() && local.spotifyOwnerId!=me) {status(local.id,Phase.FAILED,"Esta lista está vinculada a otra cuenta de Spotify");continue}
            val st=state(c,me,local.id)
            val force = forced.remove(local.id)
            if(!force && System.currentTimeMillis()<st.optLong("retryAt"))continue
            val uris=local.songs.mapNotNull(::exportPlaylistUri)
            val unchanged=rows(st,"base")==uris && st.optString("name")==local.name
            if(unchanged && st.optJSONObject("pending")==null && st.optJSONObject("pendingName")==null && !force && System.currentTimeMillis()-(checked[local.id]?:0)<300_000)continue
            status(local.id,Phase.SYNCING)
            try {
                if (local.spotifyOwnerId == me && local.syncedSpotifyId.isNotBlank() && !st.optBoolean("initializing")) {
                    val listings = catalog ?: SpotifySync.allLibraryPlaylists(c).map { it.id }.toSet()
                        .also { catalog = it }
                    if (local.syncedSpotifyId !in listings) {
                        // One complete omission may be stale. Confirm with a fresh account scan.
                        val fresh = SpotifySync.allLibraryPlaylists(c, forceRefresh = true).map { it.id }.toSet()
                        catalog = fresh
                        guard(c, session)
                        if (local.syncedSpotifyId !in fresh) {
                            st.put("remote", local.syncedSpotifyId).put("retired", true)
                            save(c, me, local.id, st)
                            LocalPlaylistPref.archiveRemovedPlaylist(c, local)
                            Api.HomeCache.invalidatePlaylist(local.syncedSpotifyId)
                            Api.HomeCache.library = null
                            statuses.update { it - local.id }
                            continue
                        }
                    }
                }
                sync(c,me,session,local,st)
                if (st.has("retryAt")) { st.remove("retryAt"); save(c,me,local.id,st) }
                checked[local.id]=System.currentTimeMillis()
            }
            catch(cancelled:CancellationException){throw cancelled}
            catch(error:Exception){
                st.put("retryAt",System.currentTimeMillis()+maxOf(30_000L,(error as? Spotify.SpotifyException)?.retryAfterSec?.times(1000)?:0L))
                save(c,me,local.id,st)
                android.util.Log.w("LocalSpotifyMirror", "Playlist sync failed for " + local.id, error)
                val message = if (error is Spotify.SpotifyException) {
                    if (st.optBoolean("initializing")) "No se pudo añadir la lista a tu biblioteca de Spotify. Toca para reintentar."
                    else "No se pudo sincronizar con Spotify. Toca para reintentar."
                } else error.message ?: "Spotify no está disponible; se conserva la copia local"
                status(local.id, Phase.FAILED, message)
            }
        }
        // Removing the local list unfollows its linked copy, never clears its contents.
        val live=locals.map {it.id}.toSet()
        prefs(c).all.keys.filter {it.startsWith(me+":") && it.removePrefix(me+":") !in live}.forEach {key->
            val st=JSONObject(prefs(c).getString(key,"{}")!!);val remote=st.optString("remote")
            if(remote.isNotBlank() && !st.optBoolean("retired")) {
                check(SpotifySession.spDc(c)==session)
                if (LocalPlaylistPref.getLocalPlaylist(c, key.removePrefix(me + ":")) != null) return@forEach
                updateLibraryMembership(c, me, session, remote, saved = false)
                st.put("retired",true);save(c,me,key.removePrefix(me+":"),st)
            }
        }
    }
    private suspend fun guard(context: Context, session: String) {
        com.music.spotui.di.SongPlayer.awaitAudioStartup()
        currentCoroutineContext().ensureActive()
        check(SpotifySession.spDc(context) == session) { "La cuenta ha cambiado" }
    }
    private data class Remote(val playlist:SpotifyPlaylist,val items:List<SpotifyPlaylistTrack>) {
        val catalog get()=items.filter {it.track?.let {t->!t.isLocal && t.uri?.startsWith("spotify:track:")!=false && t.id.matches(Regex("[A-Za-z0-9]{22}"))}==true}
        val uris get()=catalog.map {"spotify:track:"+it.track!!.id}
    }
    private suspend fun read(c:Context,owner:String,session:String,id:String):Remote {
        guard(c,session)
        val meta=Spotify.playlist(id).getOrThrow()
        check(meta.owner?.id==owner) {"La lista ya no pertenece a esta cuenta; no se modificó"}
        val items=mutableListOf<SpotifyPlaylistTrack>();var offset=0;var total:Int?=null
        do {
            guard(c,session)
            val page=Spotify.playlistTracks(id,100,offset).getOrThrow()
            check(total==null || total==page.total) {"La lista cambió durante su lectura; se reintentará"}
            total=page.total
            check(page.items.size==minOf(page.limit,page.total-offset).coerceAtLeast(0)) {"Spotify devolvió una página incompleta; no se modificó la lista"}
            items+=page.items;offset+=page.limit.coerceAtLeast(1)
        }while(offset<(total?:0))
        val uids=items.mapNotNull {it.uid};check(uids.distinct().size==uids.size) {"Spotify cambió el orden durante la lectura"}
        return Remote(meta,items)
    }
    private suspend fun linked(c:Context,owner:String,session:String,local:LocalPlaylist,st:JSONObject):String {
        val known=local.spotifyPlaylistId.ifBlank {st.optString("remote")}
        if(known.isNotBlank()) {
            if(st.optString("remote").isNotBlank() && st.optString("remote")!=known) {
                // A newer cross-device binding wins. Never apply a journal for one
                // Spotify playlist to another; keep the displaced journal for recovery.
                check(c.getSharedPreferences("local_spotify_mirror_archives_v1",0).edit()
                    .putString(owner+":"+local.id+":"+st.optString("remote"),st.toString()).commit())
                st.keys().asSequence().toList().forEach(st::remove)
                st.put("remote",known);save(c,owner,local.id,st)
            }
            if(st.optBoolean("initializing")) {
                finishInitialization(c, owner, session, local.id, known, st)
            }
            return known
        }
        val legacy=c.getSharedPreferences("local_spotify_mirrors_v1",0).getString(local.id,null)
        if(!legacy.isNullOrBlank()) {read(c,owner,session,legacy);st.put("remote",legacy);save(c,owner,local.id,st);return legacy}
        val marker="Spotui-ID: "+local.id
        val temporary="Spotui · "+local.id.removePrefix("local_pl_")
        val found=mutableListOf<SpotifyPlaylist>()
        for(row in SpotifySync.allLibraryPlaylists(c)) {
            guard(c,session)
            if(row.owner?.id!=null && row.owner?.id!=owner)continue
            val detail=detailsCache[row.id] ?: Spotify.playlist(row.id).getOrThrow().also {detailsCache[row.id]=it}
            if(detail.owner?.id==owner && (detail.name==temporary || detail.description?.contains(marker)==true))found+=detail
        }
        check(found.size<=1) {"Hay varias copias vinculadas en Spotify; se conservan sin modificarlas"}
        val remote=found.singleOrNull() ?: run {
            check(!st.optBoolean("creating")) {"Spotify no confirmó la creación anterior. Comprueba la lista en Spotify antes de volver a crearla"}
            st.put("creating",true);save(c,owner,local.id,st)
            guard(c,session)
            val attempt=Spotify.createPlaylist(temporary,false)
            val failure=attempt.exceptionOrNull()
            if(failure is Spotify.SpotifyException && failure.statusCode in 400..499) {st.put("creating",false);save(c,owner,local.id,st)}
            attempt.getOrThrow()
        }
        check(remote.id.isNotBlank())
        st.put("remote",remote.id).put("creating",false).put("new",true).put("initializing",true);save(c,owner,local.id,st)
        finishInitialization(c, owner, session, local.id, remote.id, st)
        return remote.id
    }
    private suspend fun finishInitialization(
        context: Context,
        owner: String,
        session: String,
        localId: String,
        remoteId: String,
        state: JSONObject,
    ) {
        guard(context, session)
        val playlist = Spotify.playlist(remoteId).getOrThrow()
        check(playlist.owner?.id == owner) { "La lista no pertenece a esta cuenta de Spotify" }
        val marker = "Spotui-ID: " + localId
        if (playlist.description?.contains(marker) != true) {
            guard(context, session)
            Spotify.editPlaylistAttributes(remoteId, newDescription = marker).getOrThrow()
        }
        updateLibraryMembership(context, owner, session, remoteId, saved = true)
        state.put("initializing", false)
        save(context, owner, localId, state)
    }

    private suspend fun updateLibraryMembership(
        context: Context,
        owner: String,
        session: String,
        playlistId: String,
        saved: Boolean,
    ) {
        suspend fun isInLibrary(): Boolean {
            guard(context, session)
            return SpotifySync.allLibraryPlaylists(context, forceRefresh = true).any { it.id == playlistId }
        }

        // Read membership before sending: an interrupted attempt may have succeeded,
        // or the playlist may already have been saved by another device.
        if (isInLibrary() == saved) return
        guard(context, session)
        Spotify.setPlaylistInLibrary(owner, playlistId, saved).getOrThrow()
        repeat(4) { attempt ->
            if (attempt > 0) delay(400L * attempt)
            if (isInLibrary() == saved) return
        }
        error("Spotify aún no confirma el cambio en tu biblioteca. La lista se conserva y se reintentará.")
    }

    private suspend fun applyLocal(c:Context,expected:LocalPlaylist,updated:LocalPlaylist) {
        LocalPlaylistPref.saveSyncedPlaylist(c,expected,updated)
    }
    private fun song(item:SpotifyPlaylistTrack):SongsModel {
        val t=item.track!!;val artist=t.artists.joinToString(", "){it.name};val key="track:"+t.id
        return SongsModel(key.hashCode() and Int.MAX_VALUE,t.name,t.album?.name.orEmpty(),artist,t.album?.images?.firstOrNull()?.url.orEmpty(),
            com.music.spotui.di.SongPlayer.buildSpotifyPlayQuery(t.id,t.name,artist),t.id,t.explicit,t.durationMs,t.artists.joinToString(","){it.id.orEmpty()})
    }
    private suspend fun rename(c:Context,owner:String,session:String,localId:String,remote:Remote,st:JSONObject,target:String):Remote {
        val journal=st.optJSONObject("pendingName") ?: JSONObject().put("before",remote.playlist.name).put("after",target).also {
            st.put("pendingName",it);save(c,owner,localId,st)
        }
        val before=journal.getString("before")
        check(remote.playlist.name==before || remote.playlist.name==target) { "El nombre cambió mientras se confirmaba. Se conservan ambos nombres" }
        var metadata=remote.playlist
        if(metadata.name!=target) {
            guard(c,session);Spotify.renamePlaylist(metadata.id,target).getOrThrow()
            var waits=0
            do {
                guard(c,session);metadata=Spotify.playlist(metadata.id).getOrThrow()
                if(metadata.name==target)break
                delay(500L*(++waits))
            }while(waits<4)
            check(metadata.name==target) { "Spotify aún no confirma el nombre; se reintentará sin perder la edición" }
        }
        st.put("name",target).put("new",false).put("nameBefore",before).put("nameAt",System.currentTimeMillis())
        st.remove("pendingName");save(c,owner,localId,st)
        return remote.copy(playlist=metadata)
    }
    private suspend fun sync(c:Context,owner:String,session:String,initial:LocalPlaylist,st:JSONObject) {
        val id=linked(c,owner,session,initial,st)
        var remote=read(c,owner,session,id)
        st.optJSONObject("pendingName")?.let { remote=rename(c,owner,session,initial.id,remote,st,it.getString("after")) }
        val pending=st.optJSONObject("pending")
        if(pending!=null) {
            val before=rows(pending,"before").orEmpty();val after=rows(pending,"after").orEmpty()
            check(pendingPlaylistResult(before,after,remote.uris)!=PendingPlaylistResult.CONCURRENT) {"La lista cambió durante un envío sin confirmar. Se conservan ambas versiones para revisar"}
            if(remote.uris==after)st.put("base",JSONArray(after))
            st.remove("pending");save(c,owner,initial.id,st)
        }
        if(rows(st,"base")==null) {
            // First import never assumes remote-only tracks were deleted locally.
            val original=initial.spotifyBaseline ?: remote.uris
            st.put("base",JSONArray(original)).put("name",initial.spotifyBaselineName.ifBlank {if(st.optBoolean("new"))remote.playlist.name else initial.name})
            if(initial.spotifyBaseline==null && !st.optBoolean("new")) {
                val union=reconcilePlaylist(emptyList(),initial.songs.mapNotNull(::exportPlaylistUri),remote.uris)
                val known=initial.songs.mapNotNull {s->exportPlaylistUri(s)?.let {it to s}}.toMap()+remote.catalog.associate {"spotify:track:"+it.track!!.id to song(it)}
                applyLocal(c,initial,initial.copy(songs=retainLocalPlaylistTracks(initial.songs,union.map {known.getValue(it)}),spotifyPlaylistId=id,spotifyOwnerId=owner))
            }
            save(c,owner,initial.id,st)
        }
        var rounds=0
        while(rounds++<60) {
            guard(c,session)
            val local=LocalPlaylistPref.getLocalPlaylist(c,initial.id) ?: return
            check(local.spotifyOwnerId.isBlank() || local.spotifyOwnerId==owner)
            check(local.spotifyPlaylistId.isBlank() || local.spotifyPlaylistId==id) { "El vínculo cambió en otro dispositivo; se reintentará con la copia más reciente" }
            val base=rows(st,"base").orEmpty();val ours=local.songs.mapNotNull(::exportPlaylistUri)
            val desired=reconcilePlaylist(base,ours,remote.uris)
            val remoteSongs=remote.catalog.associate {"spotify:track:"+it.track!!.id to song(it)}
            val known=remoteSongs+local.songs.mapNotNull {s->exportPlaylistUri(s)?.let {it to s}}.toMap()
            val projected=retainLocalPlaylistTracks(local.songs,desired.map {known.getValue(it)})
            val linked=local.copy(songs=projected,spotifyPlaylistId=id,spotifyOwnerId=owner)
            applyLocal(c,local,linked)
            val oldName=st.optString("name",local.name)
            if(remote.playlist.name!=linked.name) {
                if(linked.name==oldName && !st.optBoolean("new")) {
                    check(remote.playlist.name!=st.optString("nameBefore") || System.currentTimeMillis()-st.optLong("nameAt")>=60_000) { "Esperando a que Spotify confirme el nombre en todas sus respuestas" }
                    applyLocal(c,linked,linked.copy(name=remote.playlist.name));st.put("name",remote.playlist.name);save(c,owner,local.id,st);continue
                }
                check(remote.playlist.name==oldName || st.optBoolean("new")) {"El nombre cambió en ambos sitios. Se conservan los dos nombres; elige el mismo para continuar"}
                remote=rename(c,owner,session,local.id,remote,st,linked.name)
            }
            if(desired==remote.uris) {
                val current=LocalPlaylistPref.getLocalPlaylist(c,local.id) ?: return
                applyLocal(c,current,current.copy(spotifyBaseline=desired,spotifyBaselineName=current.name))
                st.put("base",JSONArray(desired)).put("name",current.name);save(c,owner,local.id,st)
                Api.HomeCache.invalidatePlaylist(id);Api.HomeCache.library=null
                status(local.id,Phase.SYNCED);return
            }
            val counts=desired.groupingBy {it}.eachCount();val remaining=counts.toMutableMap()
            val remove=remote.catalog.filter {item->val uri="spotify:track:"+item.track!!.id;val left=remaining[uri]?:0;if(left>0){remaining[uri]=left-1;false}else true}
            var after:List<String>
            val operation:suspend ()->Unit
            if(remove.isNotEmpty()) {
                val group=remove.take(100);check(group.all {!it.uid.isNullOrBlank()}) {"Spotify no devolvió identificadores de edición"}
                val uids=group.map {it.uid!!}.toSet();after=remote.catalog.filterNot {it.uid in uids}.map {"spotify:track:"+it.track!!.id}
                operation={Spotify.removeTracksFromPlaylist(id,group.map {Spotify.PlaylistItemRef("spotify:track:"+it.track!!.id,it.uid!!)}).getOrThrow()}
            } else {
                val available=remote.uris.groupingBy {it}.eachCount().toMutableMap();val missing=desired.filter {u->val n=available[u]?:0;if(n>0){available[u]=n-1;false}else true}.take(100)
                if(missing.isNotEmpty()) {after=remote.uris+missing;operation={Spotify.addTracksToPlaylist(id,missing).getOrThrow()}}
                else {
                    val index=desired.indices.first {desired[it]!=remote.uris[it]};val from=(index+1 until remote.catalog.size).first {"spotify:track:"+remote.catalog[it].track!!.id==desired[index]}
                    var length=1;while(from+length<remote.catalog.size && index+length<desired.size && "spotify:track:"+remote.catalog[from+length].track!!.id==desired[index+length])length++
                    val moving=remote.catalog.subList(from,from+length);val before=remote.catalog[index].uid
                    check(before!=null && moving.all {!it.uid.isNullOrBlank()}) {"Spotify no permite reordenar esta respuesta"}
                    val next=remote.uris.toMutableList();val block=next.subList(from,from+length).toList();repeat(length){next.removeAt(from)};next.addAll(index,block);after=next
                    operation={Spotify.moveItemsInPlaylist(id,moving.map {it.uid!!},before).getOrThrow()}
                }
            }
            st.put("pending",JSONObject().put("before",JSONArray(remote.uris)).put("after",JSONArray(after)));save(c,owner,local.id,st)
            guard(c,session)
            check(LocalPlaylistPref.getLocalPlaylist(c,local.id)?.spotifyPlaylistId==id) { "La lista cambió de vínculo; se conserva el envío pendiente" }
            operation()
            var observed=read(c,owner,session,id)
            var waits=0
            while(observed.uris==remote.uris && observed.uris!=after && waits++<4) {
                delay(500L*waits);observed=read(c,owner,session,id)
            }
            check(observed.uris==after) {"Spotify todavía no confirma el último cambio. Se conserva el envío para reintentarlo sin duplicar canciones"}
            st.put("base",JSONArray(after));st.remove("pending");save(c,owner,local.id,st)
            remote=observed
        }
        forced.add(initial.id);wake.trySend(Unit)
    }
}
