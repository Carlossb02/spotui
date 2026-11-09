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
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.tasks.await
import org.json.JSONObject
import java.util.UUID

/** Control channel for the app's own player. No audio or resolved stream URLs enter Firebase. */
object CloudPlayback {
    data class Status(val localId:String="",val ownerId:String="",val ownerName:String="",val remote:Boolean=false,
        val connected:Boolean=false,val transferring:String="",val error:String?=null)
    private val mutable=MutableStateFlow(Status())
    val status=mutable.asStateFlow()
    @Volatile private var state:CurrentSongState?=null
    private val generation=java.util.concurrent.atomic.AtomicLong()
    @Volatile private var connection:Connection?=null
    internal fun reserved(key:String)=key.startsWith("player:")
    fun bind(player:CurrentSongState) { state=player;connection?.onBound() }
    internal fun connect(context:Context,root:DatabaseReference,actor:String,assets:DriveAssets) {
        val version=generation.incrementAndGet()
        val main=CoroutineScope(SupervisorJob()+Dispatchers.Main.immediate)
        main.launch {
            if(version!=generation.get()){main.cancel();return@launch}
            connection?.close()
            connection=Connection(context.applicationContext,root,actor,assets,main).also { it.start() }
        }
    }
    internal fun disconnect() {
        generation.incrementAndGet()
        val previous=connection;connection=null
        previous?.close();mutable.value=Status()
    }
    fun transferTo(device:String) { connection?.requestTransfer(device) }
    fun remotePlaying():Boolean?=connection?.remote()?.playing
    fun remotePosition():Long?=connection?.let { c->c.remote()?.positionAt(c.now()) }
    fun remoteDuration():Long?=connection?.remote()?.duration
    fun route(action:String,position:Long=0L):Boolean=connection?.route(action,position)==true
    fun queueEdited() { connection?.queueEdited() }

    private class Connection(val context:Context,val root:DatabaseReference,val actor:String,val assets:DriveAssets,val scope:CoroutineScope) {
        private val listeners=mutableListOf<Pair<DatabaseReference,ValueEventListener>>()
        private val events=Channel<suspend ()->Unit>(Channel.UNLIMITED)
        private val operations=Mutex()
        @Volatile private var current:DevicePlaybackState?=null
        private var queue:DevicePlaybackQueue?=null
        private var queueId=""
        @Volatile private var offset=0L
        private var connected=false
        private var ready=false
        @Volatile private var closed=false
        @Volatile private var applying=false
        private var acceptedEpoch=""
        private val commandPrefs=context.getSharedPreferences("google_playback_commands",0)
        private var lastCommand=commandPrefs.getString("last","").orEmpty()
        private var lastPublishedAt=0L
        private var lastSignature=""
        private var lastQueue:DevicePlaybackQueue?=null
        private var lastQueueId=""
        private var queueEdit:Job?=null
        private var held:Pair<String,DevicePlaybackState>?=null
        fun now()=System.currentTimeMillis()+offset
        fun remote()=current?.takeIf { !applying && it.owner!=actor }
        private fun report(t:Throwable) {
            if(t is CancellationException)throw t
            if(!closed)mutable.update { it.copy(error=t.message?:"No se pudo conectar con el reproductor.",transferring="") }
        }
        private fun ref(key:String)=root.child("documents").child(SyncTree.digest("player:$key"))
        private fun wrap(key:String,value:JSONObject)=JSONObject().put("key","player:$key").put("tree",value.toString()).toString().also {
            require(it.length<=10_485_760){"La cola es demasiado grande para transferirla."}
        }
        private fun decode(raw:String?)=raw?.let { JSONObject(JSONObject(it).getString("tree")) }
        private fun listen(reference:DatabaseReference,body:suspend (DataSnapshot)->Unit) {
            val listener=object:ValueEventListener {
                override fun onDataChange(snapshot:DataSnapshot){events.trySend { body(snapshot) }}
                override fun onCancelled(error:DatabaseError){report(error.toException())}
            }
            listeners+=reference to listener;reference.addValueEventListener(listener)
        }
        fun start() {
            mutable.value=Status(localId=actor)
            scope.launch {for(event in events){try{event()}catch(c:CancellationException){throw c}catch(t:Exception){report(t)}}}
            listen(root.database.getReference(".info/serverTimeOffset")){offset=(it.value as? Number)?.toLong()?:0}
            listen(root.database.getReference(".info/connected")){connected=it.getValue(Boolean::class.java)==true;publishStatus()}
            listen(ref("queue")){ snapshot->
                val parsed=withContext(Dispatchers.IO){decode(snapshot.getValue(String::class.java))?.let { it.getString("id") to DevicePlaybackQueue.parse(assets.native(it.getJSONObject("queue")) as JSONObject) }}
                if(parsed!=null){queueId=parsed.first;queue=parsed.second;mirror();acceptTransfer()}
            }
            listen(ref("session")){ snapshot->
                val next=withContext(Dispatchers.IO){decode(snapshot.getValue(String::class.java))?.let(DevicePlaybackState::parse)}
                ready=true
                val was=current
                current=next
                if(next!=null && next.owner!=actor && (was?.owner==actor || SongPlayer.isResolvingOrPlaying())) {
                    locally { SongPlayer.pause() }
                }
                publishStatus();mirror();acceptTransfer()
            }
            listen(ref("command")){ snapshot->
                val command=withContext(Dispatchers.IO){decode(snapshot.getValue(String::class.java))}?:return@listen
                val id=command.getString("id")
                if(id==lastCommand)return@listen
                lastCommand=id
                val owner=current?:return@listen
                if(!connected || owner.owner!=actor || command.optString("epoch")!=owner.epoch ||
                    command.optString("target")!=actor || now()-command.optLong("at") !in -5_000L..10_000L)return@listen
                scope.launch {operations.withLock {runCatching {
                    withContext(Dispatchers.IO){check(commandPrefs.edit().putString("last",id).commit()){"No se pudo guardar la orden del reproductor."}}
                    execute(command)
                }.onFailure(::report)}}
            }
            scope.launch {
                while(isActive){
                    delay(1_000)
                    if(!connected || !ready || state==null || operations.isLocked)continue
                    operations.withLock {
                        try {
                            val owner=current
                            if(owner?.owner==actor && owner.startToken.isBlank())publishLocal(owner)
                            else if(owner==null && SongPlayer.isResolvingOrPlaying())claim()
                        }catch(c:CancellationException){throw c}catch(t:Exception){report(t)}
                    }
                }
            }
        }
        fun onBound() {scope.launch {mirror();acceptTransfer()}}
        private fun publishStatus() {
            mutable.update { it.copy(localId=actor,ownerId=current?.owner.orEmpty(),ownerName=current?.name.orEmpty(),
                remote=current?.owner?.let { id->id!=actor }==true,connected=connected,
                transferring=if(current?.owner==it.transferring && current?.startToken.isNullOrBlank())""else it.transferring) }
        }
        private inline fun locally(block:()->Unit) {val before=applying;applying=true;try{block()}finally{applying=before}}
        fun mirror() {
            val frame=current?:return
            val songs=queue?.takeIf { queueId==frame.queueId }?:return
            if(frame.owner!=actor)state?.applyDevicePlayback(songs,frame)
        }
        private fun capture():Pair<DevicePlaybackQueue,DevicePlaybackState>? {
            val player=state?:return null
            val q=player.devicePlaybackQueue()
            if(q.songs.isEmpty())return null
            val index=player.currentQueueIndex().coerceIn(q.songs.indices)
            val checkpoint=held?.takeIf {it.first==q.songs[index].url && !SongPlayer.isPrepared()}?.second
            if(checkpoint==null)held=null
            return q to DevicePlaybackState(actor,android.os.Build.MODEL,current?.epoch?:UUID.randomUUID().toString(),"",index,
                checkpoint?.position?:SongPlayer.getCurrentPosition().coerceAtLeast(0),
                checkpoint?.duration?:SongPlayer.getDuration().takeIf {it>0}?:q.songs[index].durationMs.toLong().coerceAtLeast(0),SongPlayer.isResolvingOrPlaying(),now(),
                player.shuffle.value,player.repeat.value.name,player.sourceName.value)
        }
        private suspend fun uploadQueue(q:DevicePlaybackQueue):String {
            if(lastQueue==q && queueId==lastQueueId && queueId.isNotBlank())return queueId
            val id=UUID.randomUUID().toString()
            val raw=withContext(Dispatchers.IO){val value=q.json();assets.discover(value);wrap("queue",JSONObject().put("id",id).put("queue",assets.portable(value)))}
            ref("queue").setValue(raw).await()
            lastQueue=q;lastQueueId=id;queue=q;queueId=id
            return id
        }
        /** The owner/generation check runs on Firebase's transaction thread as well as locally. */
        private suspend fun update(expected:String?,next:DevicePlaybackState,claim:Boolean=false):Boolean {
            val result=CompletableDeferred<Boolean>()
            val deadline=now()+10_000
            val raw=withContext(Dispatchers.IO){wrap("session",next.json())}
            ref("session").runTransaction(object:Transaction.Handler {
                override fun doTransaction(data:MutableData):Transaction.Result=runCatching {
                    val old=decode(data.value as? String)?.let(DevicePlaybackState::parse)
                    if(now()>deadline || (!claim && (old?.epoch!=expected || old?.owner!=actor)) ||
                        (claim && old!=null && old?.owner!=actor && old.fresh(now())))return@runCatching Transaction.abort()
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
            if(update(current?.epoch,next,claim=true)){if(current==null || current?.owner==actor)current=next;publishStatus();lastPublishedAt=now()}
        }
        private suspend fun publishLocal(owner:DevicePlaybackState,force:Boolean=false) {
            val (q,frame)=capture()?:return
            val signature="${frame.index}:${frame.playing}:${frame.shuffle}:${frame.repeat}:${frame.source}:${q.hashCode()}"
            val jumped=kotlin.math.abs(frame.position-owner.positionAt(now()))>1_500
            if(!force && signature==lastSignature && !jumped && now()-lastPublishedAt<5_000)return
            val next=frame.copy(epoch=owner.epoch,queueId=uploadQueue(q),updated=now())
            if(update(owner.epoch,next)){if(current?.epoch==owner.epoch)current=next;lastPublishedAt=now();lastSignature=signature;publishStatus()}
        }
        fun route(action:String,position:Long):Boolean {
            if(applying)return false
            if(current?.owner==actor || current==null) {
                if(action=="select")held=null
                else if(action=="seek")held?.let {held=it.first to it.second.copy(position=position.coerceAtLeast(0))}
                return false
            }
            if((action=="select" || action=="play") && current?.fresh(now())==false) {
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
            if(applying || remote()==null)return
            queueEdit?.cancel()
            val edit=queueEdit()?:return
            queueEdit=scope.launch {delay(120);send("queue",0,edit=edit)}
        }
        private fun send(action:String,position:Long=0L,target:String="",edit:QueueEdit?=null) {
            val owner=current?:return
            if(!connected || !owner.fresh(now())){mutable.update { it.copy(error="El dispositivo no está disponible. Abre Spotui en él para conectarlo.") };return}
            val queued=edit?:if(action=="select" || action=="queue")queueEdit() else null
            val capture=queued?.queue
            val selected=queued?.index?:owner.index
            val shuffle=queued?.shuffle?:owner.shuffle
            val repeat=queued?.repeat?:owner.repeat
            val source=queued?.source?:owner.source
            scope.launch {operations.withLock {
                runCatching {
                    val payload=JSONObject().put("id",UUID.randomUUID().toString()).put("target",owner.owner).put("epoch",owner.epoch)
                        .put("action",action).put("position",position).put("device",target).put("at",now())
                    if(capture!=null)payload.put("queue",withContext(Dispatchers.IO){val value=capture.json();assets.discover(value);assets.portable(value)})
                        .put("index",selected).put("shuffle",shuffle).put("repeat",repeat).put("source",source)
                    ref("command").setValue(withContext(Dispatchers.IO){wrap("command",payload)}).await()
                }.onFailure(::report)
            }}
        }
        private suspend fun execute(command:JSONObject) {
            val owner=current?.takeIf { it.owner==actor && it.epoch==command.getString("epoch") }?:return
            when(command.getString("action")) {
                "transfer"->handoff(command.getString("device"))
                "play"->locally {SongPlayer.play()}
                "pause"->locally {SongPlayer.pause()}
                "seek"->locally {SongPlayer.seekTo(command.optLong("position"))}
                "next"->locally {SongPlayer.next(context)}
                "previous"->locally {SongPlayer.previous(context)}
                "select","queue"->{
                    val q=withContext(Dispatchers.IO){DevicePlaybackQueue.parse(assets.native(command.getJSONObject("queue")) as JSONObject)}
                    require(q.songs.isNotEmpty()){"La cola está vacía."}
                    val selected=if(command.getString("action")=="select")command.getInt("index")
                        else q.songs.indexOfFirst {it.url==state?.songUrl?.value}.takeIf {it>=0}?:owner.index
                    val frame=owner.copy(index=selected.coerceIn(q.songs.indices),playing=SongPlayer.isResolvingOrPlaying(),shuffle=command.optBoolean("shuffle"),
                        repeat=command.optString("repeat","OFF"),source=command.optString("source"),position=if(command.getString("action")=="select")command.optLong("position") else SongPlayer.getCurrentPosition())
                    locally {state?.applyDevicePlayback(q,frame);state?.retainDevicePlayback(frame)}
                    if(command.getString("action")=="select")startAudio(q,frame.copy(playing=true))
                }
            }
            current?.takeIf { it.owner==actor }?.let {publishLocal(it,true)}
        }
        fun requestTransfer(target:String) {
            if(target.isBlank())return
            val owner=current
            if(owner==null){mutable.update { it.copy(error="Reproduce una canción o un episodio para conectar el reproductor.") };return}
            if(owner.owner==target)return
            val device=GoogleDeviceSync.status.value.devices.firstOrNull {it.id==target}
            if(!connected || device?.online!=true){mutable.update {it.copy(error="Abre Spotui en el dispositivo que quieres conectar.")};return}
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
            val (q,frame)=capture()?:return
            val name=GoogleDeviceSync.status.value.devices.firstOrNull {it.id==target && it.online}?.name
                ?:error("El dispositivo no está disponible.")
            val id=uploadQueue(q)
            // Pause BEFORE committing ownership; the receiver cannot start until this write arrives.
            locally {SongPlayer.pause()}
            val next=frame.copy(owner=target,name=name,epoch=UUID.randomUUID().toString(),queueId=id,
                updated=now(),startToken=UUID.randomUUID().toString(),from=actor)
            try {
                check(update(owner.epoch,next)){"La reproducción ha cambiado. Vuelve a seleccionar el dispositivo."}
                if(current?.epoch==owner.epoch)current=next
                publishStatus();mirror()
            }catch(t:Exception){if(frame.playing && current?.epoch==owner.epoch && current?.owner==actor)locally {SongPlayer.play()};throw t}
        }
        private fun acceptTransfer() {
            val next=current?:return
            val q=queue?.takeIf {queueId==next.queueId}?:return
            if(state==null || !connected || !next.canStart(actor,now()) || acceptedEpoch==next.epoch)return
            acceptedEpoch=next.epoch
            scope.launch {operations.withLock {
                try {
                    val resolved=withContext(Dispatchers.IO){DevicePlaybackQueue.parse(assets.native(assets.portable(q.json())) as JSONObject)}
                    val song=resolved.songs.getOrNull(next.index)?:error("No se pudo recuperar la cola.")
                    check(!song.url.startsWith("sync_pending:")){"El archivo aún se está sincronizando en este dispositivo."}
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
            val player=state?:return
            val song=q.songs.getOrNull(frame.index)?:return
            held=song.url to frame
            locally {
                SongPlayer.pause()
                SongPlayer.stop()
                player.applyDevicePlayback(q,frame)
                SongPlayer.bindState(player)
                SongPlayer.registerTracksForResolution(q.songs)
                SongPlayer.setRestorePoint(song.url,frame.position)
                player.retainDevicePlayback(frame)
                if(frame.playing)SongPlayer.playSong(song.url,context,"song/${song.id}")
                else player.setPlaying(false)
            }
        }
        fun close() {
            closed=true
            listeners.forEach { (reference,listener)->reference.removeEventListener(listener) }
            events.close();scope.cancel()
        }
    }
}
