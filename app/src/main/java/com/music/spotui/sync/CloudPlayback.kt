package com.music.spotui.sync

import android.content.Context
import com.google.firebase.database.*
import com.music.spotui.di.CurrentSongState
import com.music.spotui.di.SongPlayer
import kotlinx.coroutines.*
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import androidx.compose.runtime.snapshotFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.tasks.await
import org.json.JSONObject
import java.util.UUID

/** Control channel for the app's own player. No audio or resolved stream URLs enter Firebase. */
object CloudPlayback {
    data class Status(val localId:String="",val ownerId:String="",val ownerName:String="",val remote:Boolean=false,
        val connected:Boolean=false,val transferring:String="",val error:String?=null,val disabledDevices:Set<String> = emptySet(),val sleepEnd:Long=0L,val volume:Float=-1f)
    private val mutable=MutableStateFlow(Status())
    val status=mutable.asStateFlow()
    @Volatile private var state:CurrentSongState?=null
    private val generation=java.util.concurrent.atomic.AtomicLong()
    @Volatile private var connection:Connection?=null
    private var viewObserver:Job?=null
    private val viewScope=CoroutineScope(SupervisorJob()+Dispatchers.Main.immediate)
    internal fun reserved(key:String)=key.startsWith("player:")
    fun bind(player:CurrentSongState) {
        if(state===player)return
        state=player;connection?.onBound()
        viewObserver?.cancel()
        viewObserver=viewScope.launch {
            snapshotFlow { listOf(player.devicePlaybackQueue(),player.currentQueueIndex(),player.songUrl.value,
                player.title.value,player.singer.value,player.album.value,player.coverUri.value,
                player.playingState.value,player.shuffle.value,player.repeat.value,player.sourceName.value) }
                .distinctUntilChanged().collect { connection?.visibleStateChanged() }
        }
    }
    internal fun connect(context:Context,root:DatabaseReference,actor:String,assets:DriveAssets) {
        val version=generation.incrementAndGet()
        val main=CoroutineScope(SupervisorJob()+Dispatchers.Main.immediate)
        main.launch {
            if(version!=generation.get() || !GoogleDeviceSync.canSyncPlayer(actor)){main.cancel();return@launch}
            connection?.close()
            connection=Connection(context.applicationContext,root,actor,assets,main).also { it.start() }
            if(state==null)runCatching {
                val shared=dagger.hilt.android.EntryPointAccessors.fromApplication(context.applicationContext,CloudPlayerEntryPoint::class.java).currentSongState()
                SongPlayer.bindState(shared)
            }.onFailure {mutable.update {it.copy(error="No se pudo preparar el reproductor. Abre Spotui en este dispositivo.")}}
        }
    }
    internal fun disconnect() {
        generation.incrementAndGet()
        val previous=connection;connection=null
        previous?.close();mutable.value=Status()
    }
    fun optionsChanged(){connection?.onOptionsChanged()}
    fun playbackChanged(){connection?.playbackChanged()}
    internal fun driveAvailable(){connection?.onDriveAvailable()}
    fun setRemoteVolume(value:Float){if(value.isFinite())connection?.changeVolume(value.coerceIn(0f,1f))}
    fun transferTo(device:String) { connection?.requestTransfer(device) }
    fun remotePlaying():Boolean?=connection?.remote()?.playing
    fun remotePosition():Long?=connection?.let { c->c.remote()?.positionAt(c.now()) }
    fun remoteDuration():Long?=connection?.remote()?.duration
    fun route(action:String,position:Long=0L):Boolean=connection?.route(action,position)==true
    fun queueEdited() { connection?.queueEdited() }

    private class Connection(val context:Context,val root:DatabaseReference,val actor:String,val assets:DriveAssets,val scope:CoroutineScope) {
        private val listeners=mutableListOf<Pair<DatabaseReference,ValueEventListener>>()
        private val drive=DriveSyncStore.shared(context,GoogleDeviceSync.status.value.uid)
        private var commandListener:ChildEventListener?=null
        private val commands=linkedMapOf<String,JSONObject>()
        private var waitingQueueCommand:Triple<String,Long,QueueEdit?>?=null
        private var needsPublication=false
        private var forcePublication=false
        private var restoreOwner=true
        private val events=Channel<suspend ()->Unit>(Channel.UNLIMITED)
        private val operations=Mutex()
        @Volatile private var current:DevicePlaybackState?=null
        private var queue:DevicePlaybackQueue?=null
        private var queueId=""
        @Volatile private var offset=0L
        private var connected=false
        private var ready=false
        @Volatile private var closed=false
        private var rulesBlocked=false
        @Volatile private var applying=false
        private var acceptedEpoch=""
        private val commandPrefs=context.getSharedPreferences("google_playback_commands",0)
        private var publishJob:Job?=null
        private var lastCommand=commandPrefs.getString("last","").orEmpty()
        private val recentCommands=commandPrefs.getStringSet("recent",emptySet()).orEmpty().toMutableSet()
        private var lastSignature=""
        private var lastQueue:DevicePlaybackQueue?=null
        private var lastQueueId=""
        private var queueEdit:Job?=null
        private var queueLoadJob:Job?=null
        private var queuePointer:JSONObject?=null
        private var queueLoadingId=""
        private var held:Pair<String,DevicePlaybackState>?=null
        private val controls=PlaybackControlCoalescer()
        fun now()=System.currentTimeMillis()+offset
        fun remote()=current?.takeIf { GoogleDeviceSync.canSyncPlayer(actor) && connected && !rulesBlocked && SyncOptions.enabled(SyncOptions.Category.PLAYER) && it.owner !in mutable.value.disabledDevices && !applying && it.owner!=actor }
        private fun report(t:Throwable) {
            if(t is CancellationException)throw t
            if(t is DriveConsentRequired)return
            if(!closed)mutable.update { it.copy(error=t.message?:"No se pudo conectar con el reproductor.",transferring="",remote=if(rulesBlocked)false else it.remote) }
        }
        private fun ref(key:String)=root.child("playback").child(key)
        private fun decode(raw:String?)=raw?.let {val o=JSONObject(it);if(o.has("tree"))JSONObject(o.getString("tree"))else o}
        private fun listen(reference:DatabaseReference,body:suspend (DataSnapshot)->Unit) {
            val listener=object:ValueEventListener {
                override fun onDataChange(snapshot:DataSnapshot){if(!closed)events.trySend {if(!closed && GoogleDeviceSync.canSyncPlayer(actor))body(snapshot)}}
                override fun onCancelled(error:DatabaseError){if(error.code==DatabaseError.PERMISSION_DENIED)rulesBlocked=true;report(error.toException())}
            }
            listeners+=reference to listener;reference.addValueEventListener(listener)
        }
        fun start() {
            mutable.value=Status(localId=actor)
            scope.launch {for(event in events){try{event()}catch(c:CancellationException){throw c}catch(t:Exception){report(t)}}}
            listen(root.database.getReference(".info/serverTimeOffset")){offset=(it.value as? Number)?.toLong()?:0}
            listen(root.database.getReference(".info/connected")){
                connected=it.getValue(Boolean::class.java)==true
                if(connected) {
                    if(current?.owner?.let {it!=actor}==true && SongPlayer.isResolvingOrPlaying())locally {SongPlayer.pause()}
                    mirror();queuePointer?.let(::loadQueue)
                }
                publishStatus();drainCommand()
            }
            listen(ref("queue")){snapshot->decode(snapshot.getValue(String::class.java))?.let(::loadQueue)}
            listen(ref("session")){snapshot->
                val decoded=withContext(Dispatchers.IO){decode(snapshot.getValue(String::class.java))?.let(DevicePlaybackState::parse)}
                val next=decoded?.takeIf {it.owner==actor || playbackOwnerLive(it,GoogleDeviceSync.status.value.devices)}
                ready=true
                val was=current
                if(next!=null && was?.epoch==next.epoch && next.updated<was.updated)return@listen
                current=next
                if(connected && SyncOptions.enabled(SyncOptions.Category.PLAYER) && next!=null && next.owner!=actor && (was?.owner==actor || SongPlayer.isResolvingOrPlaying()))locally {SongPlayer.pause()}
                publishStatus();mirror();acceptTransfer();drainCommand()
                if(next==null || next.owner==actor)playbackChanged()
            }
            commandListener=object:ChildEventListener {
                private fun receive(snapshot:DataSnapshot) {
                    val key=snapshot.key?:return
                    events.trySend {
                        if(!SyncOptions.enabled(SyncOptions.Category.PLAYER))return@trySend
                        decode(snapshot.getValue(String::class.java))?.let {commands[key]=it}
                        drainCommand()
                    }
                }
                override fun onChildAdded(s:DataSnapshot,p:String?)=receive(s)
                override fun onChildChanged(s:DataSnapshot,p:String?)=receive(s)
                override fun onChildRemoved(s:DataSnapshot){}
                override fun onChildMoved(s:DataSnapshot,p:String?){}
                override fun onCancelled(error:DatabaseError){if(error.code==DatabaseError.PERMISSION_DENIED)rulesBlocked=true;report(error.toException())}
            }.also {ref("commands").child(actor).addChildEventListener(it)}
            listen(ref("capabilities")){snapshot->
                val disabled=snapshot.children.filter {it.child("enabled").getValue(Boolean::class.java)==false}.mapNotNull {it.key}.toSet()
                mutable.update {it.copy(disabledDevices=disabled)};publishStatus()
            }
        }
        private fun loadQueue(pointer:JSONObject) {
            queuePointer=pointer
            if(!connected || !SyncOptions.enabled(SyncOptions.Category.PLAYER))return
            val id=pointer.getString("id")
            if(queueId==id && queue!=null)return
            if(queueLoadingId==id && queueLoadJob?.isActive==true)return
            queueLoadingId=id;queueLoadJob?.cancel()
            queueLoadJob=scope.launch {runCatching {
                val parsed=withContext(Dispatchers.IO) {
                    val body=drive.get(pointer.getJSONObject("blob"),GoogleDeviceSync.driveToken())
                    if(body.optString("id")!=id)null else DevicePlaybackQueue.parse(assets.native(body.getJSONObject("queue")) as JSONObject)
                }?:return@runCatching
                if(queueLoadingId!=id || !SyncOptions.enabled(SyncOptions.Category.PLAYER))return@runCatching
                queueId=id;queue=parsed;lastQueue=parsed;lastQueueId=id
                mirror();acceptTransfer();drainCommand()
            }.onFailure(::report)}
        }
        fun onDriveAvailable() {
            if(!SyncOptions.enabled(SyncOptions.Category.PLAYER))return
            scope.launch {runCatching {
                decode(ref("queue").get().await().getValue(String::class.java))?.let(::loadQueue)
                mirror();acceptTransfer();drainCommand();playbackChanged()
                waitingQueueCommand?.let {(action,position,edit)->waitingQueueCommand=null;send(action,position,edit=edit)}
            }.onFailure(::report)}
        }
        fun onBound() {if(!applying)scope.launch {yield();mirror();acceptTransfer();drainCommand();playbackChanged()}}
        fun onOptionsChanged() {scope.launch {
            publishJob?.cancel();queueEdit?.cancel();if(!SyncOptions.enabled(SyncOptions.Category.PLAYER))queueLoadJob?.cancel()
            if(SyncOptions.enabled(SyncOptions.Category.PLAYER)) {
                if(current?.owner!=actor && SongPlayer.isResolvingOrPlaying())locally {SongPlayer.pause()}
                mirror();acceptTransfer();drainCommand();onDriveAvailable()
            } else {
                commands.clear()
                current?.takeIf {it.owner!=actor}?.let {frame->state?.songUrl?.value?.takeIf {it.isNotBlank()}?.let {SongPlayer.setRestorePoint(it,frame.positionAt(now()))}}
                state?.syncWithPlayer()
            }
            publishStatus()
        }}
        fun visibleStateChanged() {
            if(applying || remote()!=null)return
            playbackChanged()
        }
        fun playbackChanged(force:Boolean=false) {
            if(applying || !GoogleDeviceSync.canSyncPlayer(actor))return
            needsPublication=true;forcePublication=forcePublication || force
            if(publishJob?.isActive==true)return
            publishJob=scope.launch {
                do {
                    delay(180)
                    needsPublication=false
                    val force=forcePublication;forcePublication=false
                    operations.withLock {
                        if(closed || rulesBlocked || !connected || !ready || !GoogleDeviceSync.canSyncPlayer(actor))return@withLock
                        runCatching {
                            val owner=current
                            if(owner?.owner==actor && owner.startToken.isBlank())publishLocal(owner,force)
                            else if(owner==null && state!=null && SongPlayer.isResolvingOrPlaying())claim()
                        }.onFailure(::report)
                    }
                }while(needsPublication)
            }
        }
        private fun drainCommand() {
            if(!connected || !ready || state==null || !SyncOptions.enabled(SyncOptions.Category.PLAYER))return
            val owner=current?:return
            val entry=commands.entries.firstOrNull()?:return
            val key=entry.key;val command=entry.value
            if(command.optString("action") in setOf("play","next","previous","transfer") && (queue==null || queueId!=owner.queueId))return
            commands.remove(key)
            val id=command.optString("id",key)
            if(id==lastCommand || id in recentCommands || owner.owner!=actor || command.optString("epoch")!=owner.epoch || command.optString("target")!=actor || now()-command.optLong("at") !in -5_000L..20_000L) {
                if(!key.startsWith("local:"))ref("commands").child(actor).child(key).removeValue()
                drainCommand();return
            }
            scope.launch {operations.withLock {
                try {
                    if(!SyncOptions.enabled(SyncOptions.Category.PLAYER))return@withLock
                    lastCommand=id;recentCommands+=id
                    while(recentCommands.size>64)recentCommands.remove(recentCommands.first())
                    withContext(Dispatchers.IO){check(commandPrefs.edit().putString("last",id).putStringSet("recent",recentCommands.toSet()).commit())}
                    execute(command)
                }catch(t:Exception){report(t)}
                finally {if(!key.startsWith("local:"))ref("commands").child(actor).child(key).removeValue();drainCommand()}
            }}
        }
        private fun publishStatus() {
            mutable.update { it.copy(localId=actor,ownerId=current?.owner.orEmpty(),ownerName=current?.name.orEmpty(),
                remote=connected && !rulesBlocked && SyncOptions.enabled(SyncOptions.Category.PLAYER) && current?.owner?.let { id->id!=actor && id !in it.disabledDevices }==true,connected=connected,
                volume=current?.volume?:-1f,
                sleepEnd=current?.sleepEnd?.takeIf {end->end>0}?.minus(offset)?:0L,
                transferring=if(current?.owner==it.transferring && current?.startToken.isNullOrBlank())""else it.transferring) }
        }
        private inline fun locally(block:()->Unit) {val before=applying;applying=true;try{block()}finally{applying=before}}
        fun mirror() {
            if(!connected || !SyncOptions.enabled(SyncOptions.Category.PLAYER))return
            val frame=current?:return
            val songs=queue?.takeIf {queueId==frame.queueId}
            if(frame.owner!=actor) {
                if(songs!=null)locally {state?.applyDevicePlayback(playbackMirrorQueue(songs,frame),frame)}
                else frame.track?.let {song->locally {state?.applyDevicePlayback(DevicePlaybackQueue(listOf(song)),frame.copy(index=0))}}
            } else if(restoreOwner && frame.startToken.isBlank() && !SongPlayer.isResolvingOrPlaying() && state?.devicePlaybackQueue()?.songs.isNullOrEmpty() && songs!=null) {
                restoreOwner=false
                val song=songs.songs.getOrNull(frame.index)?:return
                held=song.url to frame.copy(playing=false)
                locally {state?.applyDevicePlayback(songs,frame.copy(playing=false))}
            }
        }
        private fun capture():Pair<DevicePlaybackQueue,DevicePlaybackState>? {
            val player=state?:return null
            val q=player.devicePlaybackQueue()
            if(q.songs.isEmpty())return null
            val index=player.currentQueueIndex().coerceIn(q.songs.indices)
            val selected=q.songs[index].url
            val prepared=SongPlayer.hasPreparedTrack(q.songs[index])
            val released=current?.takeIf {frame->frame.owner==actor && frame.queueId==queueId &&
                queue?.songs?.getOrNull(frame.index)?.url==selected && !SongPlayer.isResolvingOrPlaying() && !prepared}
            val checkpoint=held?.takeIf {it.first==selected && !prepared}?.second?:released
            if(checkpoint==null)held=null
            val changing=current?.track?.url?.let {it!=selected}==true
            return q to DevicePlaybackState(actor,android.os.Build.MODEL,current?.epoch?:UUID.randomUUID().toString(),"",index,
                checkpoint?.position?:if(changing && !prepared)0L else SongPlayer.getCurrentPosition().coerceAtLeast(0),
                checkpoint?.duration?:if(!prepared)q.songs[index].durationMs.toLong().coerceAtLeast(0) else SongPlayer.getDuration().coerceAtLeast(0),SongPlayer.isResolvingOrPlaying(),now(),
                player.shuffle.value,player.repeat.value.name,player.sourceName.value,
                sleepEnd=SongPlayer.sleepTimerEndAt.takeIf {it>System.currentTimeMillis()}?.plus(offset)?:0L,
                track=q.songs[index].copy(title=player.title.value.ifBlank {q.songs[index].title},singer=player.singer.value.ifBlank {q.songs[index].singer},album=player.album.value.ifBlank {q.songs[index].album},coverUri=player.coverUri.value.ifBlank {q.songs[index].coverUri}),volume=com.music.spotui.ui.utils.PlaybackDeviceVolume.read(context))
        }
        private suspend fun uploadQueue(q:DevicePlaybackQueue):String {
            check(!closed && GoogleDeviceSync.canSyncPlayer(actor)){"No hay otro dispositivo disponible."}
            if(lastQueue==q && queueId==lastQueueId && queueId.isNotBlank())return queueId
            val id=UUID.randomUUID().toString()
            val blob=withContext(Dispatchers.IO){val value=q.json();assets.discover(value)
                drive.put("queue:$actor",JSONObject().put("id",id).put("queue",assets.portable(value)),GoogleDeviceSync.driveToken())}
            currentCoroutineContext().ensureActive()
            check(!closed && GoogleDeviceSync.canSyncPlayer(actor)){"No hay otro dispositivo disponible."}
            ref("queue").setValue(JSONObject().put("id",id).put("blob",blob).toString()).await()
            lastQueue=q;lastQueueId=id;queue=q;queueId=id
            return id
        }
        /** The owner/generation check runs on Firebase's transaction thread as well as locally. */
        private suspend fun update(expected:String?,next:DevicePlaybackState,claim:Boolean=false):Boolean {
            val result=CompletableDeferred<Boolean>()
            val deadline=now()+10_000
            val raw=withContext(Dispatchers.IO){next.json().toString()}
            ref("session").runTransaction(object:Transaction.Handler {
                override fun doTransaction(data:MutableData):Transaction.Result=runCatching {
                    val old=decode(data.value as? String)?.let(DevicePlaybackState::parse)
                    if(closed || !GoogleDeviceSync.canSyncPlayer(actor) || now()>deadline || (!claim && (old?.epoch!=expected || old?.owner!=actor)) ||
                        (claim && old!=null && old?.owner!=actor && old.owner !in mutable.value.disabledDevices && playbackOwnerLive(old,GoogleDeviceSync.status.value.devices)))return@runCatching Transaction.abort()
                    data.value=raw;Transaction.success(data)
                }.getOrElse {Transaction.abort()}
                override fun onComplete(error:DatabaseError?,committed:Boolean,snapshot:DataSnapshot?){
                    if(error!=null)result.completeExceptionally(error.toException())else result.complete(committed)
                }
            },false)
            return withTimeout(15_000){result.await()}
        }
        private suspend fun claim() {
            val (q,frame)=capture()?:return
            val next=frame.copy(epoch=UUID.randomUUID().toString(),queueId=uploadQueue(q),updated=now())
            if(update(current?.epoch,next,claim=true)){lastSignature=playbackWireSignature(next,q.hashCode());if(current==null || current?.owner==actor)current=next;publishStatus();GoogleDeviceSync.requestSync()}
        }
        private suspend fun publishLocal(owner:DevicePlaybackState,force:Boolean=false) {
            val (q,frame)=capture()?:return
            val signature=playbackWireSignature(frame,q.hashCode())
            if(!shouldSendPlayback(owner,frame,lastSignature,signature,force))return
            val next=frame.copy(epoch=owner.epoch,queueId=uploadQueue(q),updated=now())
            if(update(owner.epoch,next)){if(current?.epoch==owner.epoch)current=next;lastSignature=signature;publishStatus();GoogleDeviceSync.requestSync()}
        }
        fun route(action:String,position:Long):Boolean {
            if(applying || !GoogleDeviceSync.canSyncPlayer(actor))return false
            if(rulesBlocked){current=null;publishStatus();return false}
            if(current?.owner==actor || current==null) {
                if(action=="select")held=null
                else if(action=="seek") {
                    val url=state?.songUrl?.value.orEmpty()
                    current?.takeIf {url.isNotBlank()}?.let {held=url to it.copy(position=position.coerceAtLeast(0))}
                }
                return false
            }
            if((action=="select" || action=="play") && (!connected || current?.owner in mutable.value.disabledDevices || current?.fresh(now())==false && GoogleDeviceSync.status.value.devices.none {it.id==current?.owner && it.online})) {
                // An expired owner must not prevent ordinary local/offline playback.
                current=null;held=null;publishStatus();mutable.update {it.copy(error=null,transferring="")}
                return false
            }
            if(action=="select")queueEdit?.cancel()
            send(action,position)
            return true
        }
        private data class QueueEdit(val queue:DevicePlaybackQueue,val index:Int,val shuffle:Boolean,val repeat:String,val source:String)
        private fun queueEdit():QueueEdit?=state?.let {QueueEdit(it.devicePlaybackQueue(),it.currentQueueIndex(),it.shuffle.value,it.repeat.value.name,it.sourceName.value)}
        fun queueEdited() {
            if(applying || !GoogleDeviceSync.canSyncPlayer(actor))return
            if(current?.owner==actor){playbackChanged();return}
            if(remote()==null)return
            queueEdit?.cancel()
            val edit=queueEdit()?:return
            queueEdit=scope.launch {delay(2_500);send("queue",0,edit=edit)}
        }
        private fun send(action:String,position:Long=0L,target:String="",edit:QueueEdit?=null) {
            val owner=current?:return
            if(closed || !GoogleDeviceSync.canSyncPlayer(actor))return
            if(!SyncOptions.enabled(SyncOptions.Category.PLAYER) || !connected || (!owner.fresh(now()) && GoogleDeviceSync.status.value.devices.none {it.id==owner.owner && it.online})){mutable.update { it.copy(error="El dispositivo no está disponible. Abre Spotui en él para conectarlo.") };return}
            if(!controls.accept(action,position,target,owner.epoch,android.os.SystemClock.elapsedRealtime()))return
            val queued=edit?:if(action=="select" || action=="queue")queueEdit() else null
            val capture=queued?.queue
            val selected=queued?.index?:owner.index
            val shuffle=queued?.shuffle?:owner.shuffle
            val repeat=queued?.repeat?:owner.repeat
            val source=queued?.source?:owner.source
            scope.launch {operations.withLock {
                runCatching {
                    if(!SyncOptions.enabled(SyncOptions.Category.PLAYER))return@runCatching
                    val payload=JSONObject().put("id",UUID.randomUUID().toString()).put("target",owner.owner).put("epoch",owner.epoch)
                        .put("action",action).put("position",position).put("device",target).put("at",now())
                    if(capture!=null)payload.put("queueBlob",withContext(Dispatchers.IO){val value=capture.json();assets.discover(value);drive.put("command-queue:$actor",JSONObject().put("queue",assets.portable(value)),GoogleDeviceSync.driveToken())})
                        .put("index",selected).put("shuffle",shuffle).put("repeat",repeat).put("source",source)
                    ref("commands").child(owner.owner).push().setValue(payload.toString()).await()
                }.onFailure {error->
                    if(error is DriveConsentRequired && (action=="select" || action=="queue"))waitingQueueCommand=Triple(action,position,queued)
                    report(error)
                }
            }}
        }
        private suspend fun execute(command:JSONObject) {
            if(!SyncOptions.enabled(SyncOptions.Category.PLAYER))return
            val owner=current?.takeIf { it.owner==actor && it.epoch==command.getString("epoch") }?:return
            when(command.getString("action")) {
                "transfer"->handoff(command.getString("device"))
                "play"->{
                    val activeQueue=queue?.takeIf {queueId==owner.queueId}?:error("La cola del dispositivo aún se está recuperando.")
                    val song=activeQueue.songs.getOrNull(owner.index)?:error("No hay una canción seleccionada.")
                    if(state?.songUrl?.value==song.url && SongPlayer.hasPreparedTrack(song))locally {SongPlayer.play()}
                    else {
                        val resolved=withContext(Dispatchers.IO){DevicePlaybackQueue.parse(assets.native(assets.portable(activeQueue.json())) as JSONObject)}
                        check(resolved.songs.getOrNull(owner.index)?.url?.startsWith("sync_pending:")!=true){"El archivo aún se está sincronizando."}
                        if(SyncOptions.enabled(SyncOptions.Category.PLAYER))startAudio(resolved,owner.copy(playing=true,position=owner.positionAt(now()),updated=now()))
                    }
                }
                "pause"->locally {SongPlayer.pause()}
                "sleep"->locally {SongPlayer.setSleepTimer(command.optLong("position").coerceAtLeast(0))}
                "seek"->locally {SongPlayer.seekTo(command.optLong("position"))}
                "volume"->com.music.spotui.ui.utils.PlaybackDeviceVolume.set(context,command.optLong("position").coerceIn(0,1000)/1000f)
                "next"->locally {SongPlayer.next(context,immediate=true)}
                "previous"->locally {SongPlayer.previous(context,immediate=true)}
                "select","queue"->{
                    val q=withContext(Dispatchers.IO){val pointer=command.getJSONObject("queueBlob");val body=drive.get(pointer,GoogleDeviceSync.driveToken());check(SyncTree.digest(body.toString())==pointer.getString("rev")){"La cola ha cambiado; se aplicará la orden más reciente."};DevicePlaybackQueue.parse(assets.native(body.getJSONObject("queue")) as JSONObject)}
                    require(q.songs.isNotEmpty()){"La cola está vacía."}
                    val selected=if(command.getString("action")=="select")command.getInt("index")
                        else command.optInt("index",-1).takeIf {q.songs.getOrNull(it)?.url==state?.songUrl?.value}
                            ?:owner.index.takeIf {q.songs.getOrNull(it)?.url==state?.songUrl?.value}
                            ?:q.songs.indexOfFirst {it.url==state?.songUrl?.value}.takeIf {it>=0}?:owner.index
                    val frame=owner.copy(index=selected.coerceIn(q.songs.indices),playing=SongPlayer.isResolvingOrPlaying(),shuffle=command.optBoolean("shuffle"),
                        repeat=command.optString("repeat","OFF"),source=command.optString("source"),position=if(command.getString("action")=="select")command.optLong("position") else SongPlayer.getCurrentPosition())
                    locally {state?.applyDevicePlayback(q,frame);state?.retainDevicePlayback(frame)}
                    if(command.getString("action")=="select")startAudio(q,frame.copy(playing=true))
                }
            }
            if(current?.owner==actor)playbackChanged(force=command.getString("action")=="seek")
        }
        fun changeVolume(value:Float) {
            val owner=remote()?:return
            if(GoogleDeviceSync.status.value.devices.none {it.id==owner.owner && it.online})return
            send("volume",(value*1000).toLong())
        }
        fun requestTransfer(target:String) {
            if(target.isBlank() || !SyncOptions.enabled(SyncOptions.Category.PLAYER))return
            val owner=current
            if(owner==null){mutable.update { it.copy(error="Reproduce una canción o un episodio para conectar el reproductor.") };return}
            if(owner.owner==target) {
                if(!owner.playing || owner.startToken.isNotBlank()) {
                    if(target==actor) {
                        val key="local:"+UUID.randomUUID()
                        commands[key]=JSONObject().put("id",key).put("target",actor).put("epoch",owner.epoch).put("action","play").put("at",now())
                        drainCommand()
                    }else send("play")
                }
                return
            }
            val device=GoogleDeviceSync.status.value.devices.firstOrNull {it.id==target}
            if(!connected || device?.online!=true || target in mutable.value.disabledDevices){mutable.update {it.copy(error="Abre Spotui en el dispositivo que quieres conectar.")};return}
            mutable.update {it.copy(transferring=target,error=null)}
            if(owner.owner==actor)scope.launch {operations.withLock {runCatching {handoff(target)}.onFailure(::report)}}
            else send("transfer",target=target)
            scope.launch {
                delay(12_000)
                if(mutable.value.transferring==target)mutable.update {it.copy(transferring="",error="El dispositivo no ha respondido. Vuelve a intentarlo.")}
            }
        }
        private suspend fun handoff(target:String) {
            val owner=current?.takeIf {it.owner==actor}?:return
            val shared=queue?.takeIf {queueId==owner.queueId}
            val selected=shared?.songs?.getOrNull(owner.index)
            val snapshot=if(selected!=null && state?.songUrl?.value!=selected.url)shared to owner else capture()
            val (q,frame)=snapshot?:return
            val name=GoogleDeviceSync.status.value.devices.firstOrNull {it.id==target && it.online}?.name
                ?:error("El dispositivo no está disponible.")
            val id=uploadQueue(q)
            // Pause BEFORE committing ownership; the receiver cannot start until this write arrives.
            locally {SongPlayer.pause();SongPlayer.cancelSleepTimer()}
            val next=frame.copy(owner=target,name=name,epoch=UUID.randomUUID().toString(),queueId=id,
                updated=now(),startToken=UUID.randomUUID().toString(),from=actor)
            try {
                check(update(owner.epoch,next)){"La reproducción ha cambiado. Vuelve a seleccionar el dispositivo."}
                if(current?.epoch==owner.epoch)current=next
                publishStatus();mirror()
            }catch(t:Exception){if(current?.epoch==owner.epoch && current?.owner==actor)locally {
                    if(frame.playing)SongPlayer.play()
                    if(frame.sleepEnd>now())SongPlayer.setSleepTimer(frame.sleepEnd-now())
                };throw t}
        }
        private fun acceptTransfer() {
            val next=current?:return
            val q=queue?.takeIf {queueId==next.queueId}?:return
            if(!SyncOptions.enabled(SyncOptions.Category.PLAYER) || state==null || !connected || !next.canStart(actor,now()) || acceptedEpoch==next.epoch)return
            acceptedEpoch=next.epoch
            scope.launch {operations.withLock {
                try {
                    val resolved=withContext(Dispatchers.IO){DevicePlaybackQueue.parse(assets.native(assets.portable(q.json())) as JSONObject)}
                    val song=resolved.songs.getOrNull(next.index)?:error("No se pudo recuperar la cola.")
                    check(!song.url.startsWith("sync_pending:")){"Este archivo solo está disponible en el otro dispositivo."}
                    val accepted=next.copy(startToken="",updated=now())
                    check(update(next.epoch,accepted)){"La reproducción ha cambiado en otro dispositivo."}
                    if(current?.epoch!=next.epoch || current?.owner!=actor)return@withLock
                    current=accepted;queue=resolved;startAudio(resolved,next);publishStatus()
                    mutable.update {it.copy(transferring="",error=null)}
                }catch(c:CancellationException){throw c}catch(t:Exception){
                    report(t)
                    // Restore the previous owner if the target cannot open the selected local file.
                    val name=GoogleDeviceSync.status.value.devices.firstOrNull {it.id==next.from && it.online}?.name
                    if(name!=null)update(next.epoch,next.copy(owner=next.from,name=name,epoch=UUID.randomUUID().toString(),
                        startToken=UUID.randomUUID().toString(),from="",updated=now()))
                }
            }}
        }
        private fun startAudio(q:DevicePlaybackQueue,frame:DevicePlaybackState) {
            if(!SyncOptions.enabled(SyncOptions.Category.PLAYER) || current?.owner!=actor || current?.epoch!=frame.epoch)return
            val player=state?:return
            val song=q.songs.getOrNull(frame.index)?:return
            held=song.url to frame
            restoreOwner=false
            locally {
                SongPlayer.pause()
                SongPlayer.stop()
                player.applyDevicePlayback(q,frame)
                SongPlayer.bindState(player)
                SongPlayer.setNowPlayingMeta(song.title,song.singer,song.coverUri)
                SongPlayer.registerTracksForResolution(q.songs)
                SongPlayer.setRestorePoint(song.url,frame.position)
                player.retainDevicePlayback(frame)
                if(frame.sleepEnd>now())SongPlayer.setSleepTimer(frame.sleepEnd-now())else SongPlayer.cancelSleepTimer()
                if(frame.playing)SongPlayer.playSong(song.url,context,"song/${song.id}")
                else player.setPlaying(false)
            }
        }
        fun close() {
            closed=true
            listeners.forEach { (reference,listener)->reference.removeEventListener(listener) }
            commandListener?.let {ref("commands").child(actor).removeEventListener(it)}
            events.close();scope.cancel()
        }
    }
}
