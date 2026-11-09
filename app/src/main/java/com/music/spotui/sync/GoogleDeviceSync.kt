package com.music.spotui.sync

import android.app.Activity
import android.content.Context
import android.content.Intent
import com.google.android.gms.auth.api.identity.ClearTokenRequest
import com.google.android.gms.auth.api.identity.AuthorizationRequest
import com.google.android.gms.auth.api.identity.AuthorizationResult
import com.google.android.gms.auth.api.identity.Identity
import com.google.android.gms.common.api.Scope
import androidx.credentials.CredentialManager
import androidx.credentials.GetCredentialRequest
import androidx.credentials.CustomCredential
import com.google.android.libraries.identity.googleid.GetSignInWithGoogleOption
import com.google.android.libraries.identity.googleid.GoogleIdTokenCredential
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.auth.GoogleAuthProvider
import com.google.firebase.database.*
import com.music.spotui.data.preferences.*
import kotlinx.coroutines.*
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.tasks.await
import org.json.JSONObject
import java.io.File
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.atomic.AtomicBoolean

/** Local edits are durable before network writes. The player never waits for this coordinator. */
object GoogleDeviceSync {
    data class Device(val name:String,val online:Boolean,val lastSeen:Long,val id:String="")
    data class Status(val configured:Boolean=false,val email:String="",val photoUrl:String="",val phase:String="Sin vincular",val pending:Int=0,val filesPending:Int=0,
        val progress:Float?=null,val devices:List<Device> = emptyList(),val lastSync:Long=0L,val error:String?=null,val needsDrive:Boolean=false)
    private val mutable=MutableStateFlow(Status())
    val status=mutable.asStateFlow()
    private val scope=CoroutineScope(SupervisorJob()+Dispatchers.IO)
    private lateinit var context:Context
    private var config:GoogleSyncConfig?=null
    private var auth:FirebaseAuth?=null
    @Volatile private var session:Session?=null
    @Volatile private var driveToken:String?=null
    @Volatile private var driveTokenAt=0L
    private var installed=false
    @Synchronized fun install(app:Context) {
        if(installed)return
        installed=true;context=app.applicationContext
        ConnectionState.install(context);OwnLibraryChanges.install(context)
        scope.launch {
            runCatching {
                config=GoogleSyncConfig.load(context)
                attachAuth()
                mutable.update { current -> current.copy(configured=true,error=null) }
            }.onFailure {
                if(it is CancellationException)throw it
                mutable.update { current -> current.copy(
                    configured=false,
                    phase="No disponible",
                    error="El inicio de sesión con Google no está disponible en esta versión. Actualiza la app.",
                ) }
            }
            ConnectionState.online.collect { if(it)requestSync()else mutable.update { current -> current.copy(phase="Estás sin conexión") } }
        }
        scope.launch { deviceListeningHistoryVersion.collect { requestSync() } }
    }
    private fun fail(t:Throwable) { if(t is CancellationException)throw t;mutable.update { current -> current.copy(error=t.message?:"No se pudo sincronizar. Se volverá a intentar.",phase="Sincronización pendiente") } }
    private fun attachAuth() {
        val cfg=config?:return
        val next=FirebaseAuth.getInstance(cfg.firebase(context))
        auth=next
        val prefs=context.getSharedPreferences("google_device_sync_config",0)
        if(prefs.getBoolean("enabled",false)) next.currentUser?.let { user->start(user.uid,user.email.orEmpty(),user.photoUrl?.toString().orEmpty()) }
    }
    suspend fun signIn(activity:Activity) {
        val cfg=config?:error("El inicio de sesión con Google aún no está disponible. Inténtalo de nuevo.")
        val option=GetSignInWithGoogleOption.Builder(cfg.webClientId).build()
        val response=withContext(Dispatchers.Main.immediate) {
            CredentialManager.create(activity).getCredential(context=activity,
                request=GetCredentialRequest.Builder().addCredentialOption(option).build())
        }
        val credential=response.credential as? CustomCredential?:error("No se pudo obtener la cuenta de Google.")
        require(credential.type==GoogleIdTokenCredential.TYPE_GOOGLE_ID_TOKEN_CREDENTIAL)
        val id=GoogleIdTokenCredential.createFrom(credential.data).idToken
        val service=auth?:FirebaseAuth.getInstance(cfg.firebase(context)).also { auth=it }
        val user=service.signInWithCredential(GoogleAuthProvider.getCredential(id,null)).await().user?:error("No se pudo vincular Google.")
        withContext(Dispatchers.IO) { context.getSharedPreferences("google_device_sync_config",0).edit().putBoolean("enabled",true).commit();start(user.uid,user.email.orEmpty(),user.photoUrl?.toString().orEmpty()) }
    }
    suspend fun requestDriveAuthorization():AuthorizationResult {
        val email=auth?.currentUser?.email?:error("Vincula primero la cuenta de Google.")
        return Identity.getAuthorizationClient(context).authorize(AuthorizationRequest.builder()
            .setAccount(android.accounts.Account(email,"com.google")).setRequestedScopes(listOf(Scope("https://www.googleapis.com/auth/drive.appdata"))).build()).await()
    }
    fun requireDriveConsent() { mutable.update { current -> current.copy(needsDrive=true) } }
    fun acceptDriveAuthorization(result:AuthorizationResult) {
        check(auth?.currentUser!=null){"Vincula primero tu cuenta de Google."}
        driveToken=result.accessToken?:error("No se ha autorizado Google Drive.");driveTokenAt=System.currentTimeMillis()
        mutable.update { current -> current.copy(needsDrive=false,error=null) };requestSync()
    }
    fun acceptDriveIntent(data:Intent) = acceptDriveAuthorization(Identity.getAuthorizationClient(context).getAuthorizationResultFromIntent(data))
    suspend fun signOut()=withContext(Dispatchers.IO) {
        context.getSharedPreferences("google_device_sync_config",0).edit().putBoolean("enabled",false).commit()
        session?.close();session=null;GoogleSyncJobService.cancel(context);auth?.signOut();driveToken=null;driveTokenAt=0L;CloudHistory.projection=null
        notifySyncedHistoryChanged();mutable.value=Status(configured=config!=null)
    }
    private fun start(uid:String,email:String,photoUrl:String) {
        if(session?.uid==uid){mutable.update { it.copy(email=email,photoUrl=photoUrl) };return}
        session?.close()
        mutable.value=Status(configured=true,email=email,photoUrl=photoUrl,phase="Conectando con Google")
        session=Session(uid,config!!).also { it.start() };GoogleSyncJobService.schedule(context)
    }
    @Volatile private var lastScheduleAt=0L
    fun requestSync(){
        session?.wake?.trySend(Unit)
        val now=android.os.SystemClock.elapsedRealtime()
        if(installed && session!=null && now-lastScheduleAt>5_000){lastScheduleAt=now;GoogleSyncJobService.schedule(context)}
    }
    fun retry(){mutable.update { current -> current.copy(error=null) };requestSync()}
    suspend fun awaitIdle():Boolean {
        requestSync()
        repeat(100){delay(500);if(session==null || status.value.let { it.phase=="Sincronizado" && it.pending==0 && it.filesPending==0 })return true}
        return false
    }
    private suspend fun token():String? {
        driveToken?.takeIf { System.currentTimeMillis()-driveTokenAt<40*60_000 }?.let { return it }
        val authorization=runCatching { requestDriveAuthorization() }.getOrNull()
        if(authorization==null || authorization.hasResolution()){mutable.update { current -> current.copy(needsDrive=true) };return null}
        acceptDriveAuthorization(authorization);return driveToken
    }
    private class Session(val uid:String,cfg:GoogleSyncConfig) {
        val wake=Channel<Unit>(Channel.CONFLATED)
        private val work=CoroutineScope(SupervisorJob()+Dispatchers.IO)
        private val firebase=FirebaseDatabase.getInstance(cfg.firebase(context),cfg.databaseUrl)
        private val root=firebase.reference.child("users").child(uid)
        private val actor=context.getSharedPreferences("google_device_sync_config",0).let { p->p.getString("device",null)?:UUID.randomUUID().toString().also { p.edit().putString("device",it).commit() } }
        private val disk=SyncDisk(File(context.filesDir,"device_sync_"+SyncTree.digest(cfg.projectId+uid).take(32)+".json"))
        private val state=disk.read()
        private val assets=DriveAssets(context)
        private val prefs=CloudPreferences(context,assets)
        private val docs=mutableMapOf<String,SyncTree>()
        private val observed=mutableMapOf<String,Map<String,String>>()
        private class DocumentView(val tree:SyncTree) {
            val value by lazy { tree.value() }
            val flat by lazy { value?.let(SyncTree::flatten).orEmpty() }
            val wire by lazy { tree.json() }
            val references by lazy {
                Regex("asset:([a-f0-9]{64})").findAll(value?.toString().orEmpty()).map { it.groupValues[1] }.toSet()
            }
        }
        private val views=mutableMapOf<String,DocumentView>()
        private fun view(key:String):DocumentView {
            val tree=docs.getValue(key)
            return views[key]?.takeIf { it.tree === tree } ?: DocumentView(tree).also { views[key]=it }
        }
        // SDK callbacks arrive on Main. Only enqueue there; decode and merge on
        // the same IO coordinator as native edits, preserving event order.
        private val events=ConcurrentLinkedQueue<()->Unit>()
        private fun post(event:()->Unit) { if(!closed){events.add(event);wake.trySend(Unit)} }
        private val nativeFlats=mutableMapOf<String,Pair<Any,Map<String,String>>>()
        private fun flattenNative(key:String,value:Any):Map<String,String> {
            nativeFlats[key]?.takeIf { it.first === value }?.let { return it.second }
            return SyncTree.flatten(value).also { nativeFlats[key]=value to it }
        }
        private val pending=mutableMapOf<String,Long>()
        private val incoming=ConcurrentHashMap<String,SyncTree>()
        private val acknowledgments=ConcurrentHashMap<String,Long>()
        private val sending=ConcurrentHashMap.newKeySet<String>()
        private val manifests=ConcurrentHashMap<String,JSONObject>()
        private val listeners=mutableListOf<Pair<DatabaseReference,ValueEventListener>>()
        private var childListener:ChildEventListener?=null
        private var transfer:Job?=null
        @Volatile private var connected=false
        @Volatile private var ready=false
        @Volatile private var closed=false
        private val reapply=AtomicBoolean(false)
        private var clock=state.optLong("clock")
        private var count=state.optLong("counter")
        private var serial=state.optLong("serial")
        @Volatile private var offset=0L
        @Volatile private var nextTransferAt=0L
        @Volatile private var nextSendAt=0L
        private val tracking=state.optJSONObject("historyTracking")?:JSONObject()
        private val history=CloudHistory(context,assets,actor,tracking)
        init {
            mutable.update { current -> current.copy(lastSync=state.optLong("lastSync")) }
            state.optJSONObject("docs")?.let { o->o.keys().forEach { docs[it]=SyncTree.parse(o.getString(it)) } }
            state.optJSONObject("observed")?.let { o->o.keys().forEach { key->val row=o.getJSONObject(key);observed[key]=row.keys().asSequence().associateWith { row.getString(it) } } }
            state.optJSONObject("pending")?.let { o->o.keys().forEach { pending[it]=o.getLong(it) } }
            state.optJSONObject("assets")?.let { o->o.keys().forEach { manifests[it]=o.getJSONObject(it) } }
            state.keys().asSequence().toList().forEach(state::remove)
        }
        private fun stamp():SyncStamp {
            val now=System.currentTimeMillis()+offset
            if(now>clock){clock=now;count=0}else count++
            return SyncStamp(clock,count,actor)
        }
        private fun observe(tree:SyncTree){tree.fields.values.forEach { if(it.stamp.time>clock){clock=it.stamp.time;count=it.stamp.counter}else if(it.stamp.time==clock)count=maxOf(count,it.stamp.counter) }}
        private fun put(key:String,value:Any,history:Boolean=false) {
            val before=if(key in docs)view(key).flat else emptyMap()
            val after=SyncTree.flatten(value)
            if(before==after)return
            val delta=SyncTree.changed(before,after,stamp(),history)
            if(delta.fields.isEmpty())return
            docs[key]=PodcastProgressSync.merge(key,docs[key]?:SyncTree(),delta);pending[key]=++serial
        }
        private fun scan():Map<String,Any> {
            val snapshot=prefs.capture { mutable.update { current -> current.copy(error=it) } }
            (observed.keys+snapshot.keys).toList().forEach { key->
                if(key in prefs.failedKeys)return@forEach
                val before=observed[key].orEmpty();val after=snapshot[key]?.let { flattenNative(key,it) }.orEmpty()
                if(before==after)return@forEach
                val delta=SyncTree.changed(before,after,stamp())
                if(delta.fields.isNotEmpty() && after != (if(key in docs)view(key).flat else emptyMap())){docs[key]=PodcastProgressSync.merge(key,docs[key]?:SyncTree(),delta);pending[key]=++serial}
                observed[key]=after
            }
            nativeFlats.keys.retainAll(snapshot.keys)
            return snapshot
        }
        private fun save(){
            val d=JSONObject();docs.keys.forEach { k->d.put(k,view(k).wire) };val o=JSONObject();observed.forEach { (k,v)->o.put(k,JSONObject(v)) }
            disk.write(JSONObject().put("docs",d).put("observed",o).put("pending",JSONObject(pending)).put("clock",clock).put("counter",count).put("serial",serial)
                .put("historyTracking",tracking).put("assets",JSONObject(manifests.toMap())).put("lastSync",mutable.value.lastSync))
        }
        private fun valueListener(ref:DatabaseReference,body:(DataSnapshot)->Unit) {
            val listener=object:ValueEventListener {override fun onDataChange(snapshot:DataSnapshot){post { body(snapshot) }};override fun onCancelled(error:DatabaseError){fail(error.toException())}}
            ref.addValueEventListener(listener);listeners+=ref to listener
        }
        fun start() {
            CloudPlayback.connect(context,root,actor,assets)
            valueListener(firebase.getReference(".info/serverTimeOffset")){offset=(it.value as? Number)?.toLong()?:0L}
            valueListener(firebase.getReference(".info/connected")){ s->
                connected=s.getValue(Boolean::class.java)==true
                if(connected){
                    val presence=root.child("devices").child(actor)
                    presence.onDisconnect().updateChildren(mapOf("online" to false,"lastSeen" to ServerValue.TIMESTAMP))
                    presence.updateChildren(mapOf("name" to android.os.Build.MODEL,"online" to true,"lastSeen" to ServerValue.TIMESTAMP))
                }
                wake.trySend(Unit)
            }
            valueListener(root.child("devices")){ s->mutable.update { current -> current.copy(devices=s.children.map { row->Device(row.child("name").getValue(String::class.java)?:"Dispositivo",row.child("online").getValue(Boolean::class.java)==true,(row.child("lastSeen").value as? Number)?.toLong()?:0L,row.key.orEmpty()) }) } }
            valueListener(root.child("assets")){ s->s.children.forEach { row->row.getValue(String::class.java)?.let { raw->runCatching { manifests[row.key!!]=JSONObject(raw) }.onFailure(::fail) } };wake.trySend(Unit) }
            childListener=object:ChildEventListener {
                private fun receive(s:DataSnapshot)=post { s.getValue(String::class.java)?.let { raw->runCatching {
                    val o=JSONObject(raw);val key=o.getString("key");require(s.key==SyncTree.digest(key));if(CloudPlayback.reserved(key))return@runCatching;incoming.merge(key,SyncTree.parse(o.getString("tree"))) {a,b->PodcastProgressSync.merge(key,a,b)};wake.trySend(Unit)
                }.onFailure(::fail) }}
                override fun onChildAdded(s:DataSnapshot,previous:String?)=receive(s)
                override fun onChildChanged(s:DataSnapshot,previous:String?)=receive(s)
                override fun onChildRemoved(s:DataSnapshot){} // Logical deletion uses durable tombstones, not Firebase node removal.
                override fun onChildMoved(s:DataSnapshot,previous:String?){}
                override fun onCancelled(error:DatabaseError)=fail(error.toException())
            }.also { root.child("documents").addChildEventListener(it) }
            root.child("documents").addListenerForSingleValueEvent(object:ValueEventListener {
                override fun onDataChange(s:DataSnapshot)=post { ready=true }
                override fun onCancelled(error:DatabaseError)=fail(error.toException())
            })
            work.launch {
                for(unit in wake) {
                    delay(150)
                    try {
                        while(true){val event=events.poll()?:break;event()}
                        cycle()
                    }catch(c:CancellationException){throw c}catch(t:Exception){fail(t);GoogleSyncJobService.schedule(context)}
                }
            }
            work.launch { while(isActive){delay(30_000);if(connected)wake.trySend(Unit)} }
            wake.trySend(Unit)
        }
        private fun cycle() {
            if(incoming.isNotEmpty())PodcastResumeStore.flush(context)
            var snapshot=scan() // Export every pending native edit before accepting remote fields.
            incoming.entries.toList().forEach { (k,v)->if(incoming.remove(k,v)){docs[k]=PodcastProgressSync.merge(k,docs[k]?:SyncTree(),v);observe(v)} }
            acknowledgments.entries.toList().forEach { (k,g)->if(acknowledgments.remove(k,g) && pending[k]==g)pending.remove(k) }
            val deleted=history.applyDeletes(docs,::put)
            history.scan(docs,::put)
            save() // Outbox + counters + deletion resets are one atomic snapshot.
            if(deleted.isNotEmpty()){val e=context.getSharedPreferences("history_sync_deletes",0).edit();deleted.forEach(e::remove);e.commit()}
            // Catch edits made while a large local file was being prepared.
            snapshot=scan()
            var applied=false
            val restoreFiles=reapply.getAndSet(false)
            docs.filterKeys { !it.startsWith("history:") }.forEach { (key,_)->
                val document=view(key);val value=document.value;val flat=document.flat
                if(flat!=observed[key] || restoreFiles){
                    if(prefs.applyIfUnchanged(key,value,snapshot[key])){observed[key]=flat;applied=true}else wake.trySend(Unit)
                }
            }
            if(applied){
                invalidateAlternativeStreamMemory()
                SubscribedPodcastPref.notifySyncedChange()
                OwnLibraryChanges.changed();com.music.spotui.data.api.SavedTrackLibrary.localChanged(context)
            }
            val projection=history.project(docs,force=restoreFiles)
            if(CloudHistory.projection!=projection){CloudHistory.projection=projection;notifySyncedHistoryChanged()}
            save()
            val references=docs.keys.flatMap { view(it).references }.toSet()
            val missing=references.count { !assets.has(it) || !manifests.containsKey(it) }
            mutable.update { current -> current.copy(pending=pending.size,filesPending=missing,phase=when { !ConnectionState.online.value->"Estás sin conexión";!connected || !ready->"Conectando con Google";pending.isNotEmpty() || missing>0->"Sincronizando";else->"Sincronizado" },lastSync=if(connected && ready && pending.isEmpty() && missing==0 && current.phase!="Sincronizado")System.currentTimeMillis()else current.lastSync) }
            if(connected && ready){sendDocuments();sendAssets(references)}
        }
        private fun sendDocuments() {
            if(System.currentTimeMillis()<nextSendAt)return
            pending.toMap().forEach { (key,generation)->
                if(sending.size>=4 || !sending.add(key))return@forEach
                val sent=docs.getValue(key)
                root.child("documents").child(SyncTree.digest(key)).runTransaction(object:Transaction.Handler {
                    override fun doTransaction(data:MutableData):Transaction.Result {
                        return runCatching {
                            val previous=(data.value as? String)?.let { JSONObject(it) }
                            val server=previous?.let { SyncTree.parse(it.getString("tree")) }?:SyncTree()
                            data.value=JSONObject().put("key",key).put("tree",PodcastProgressSync.merge(key,server,sent).json()).toString()
                            Transaction.success(data)
                        }.getOrElse { Transaction.abort() }
                    }
                    override fun onComplete(error:DatabaseError?,committed:Boolean,s:DataSnapshot?)=post {
                        sending.remove(key)
                        if(error!=null){nextSendAt=System.currentTimeMillis()+30_000;fail(error.toException())}else if(committed){
                            acknowledgments[key]=generation
                            s?.getValue(String::class.java)?.let { raw->runCatching { incoming.merge(key,SyncTree.parse(JSONObject(raw).getString("tree"))) {a,b->PodcastProgressSync.merge(key,a,b)} }.onFailure(::fail) }
                        }
                        if(error==null && !committed){nextSendAt=System.currentTimeMillis()+30_000;mutable.update { current -> current.copy(error="No se pudieron sincronizar algunos cambios. Vuelve a intentarlo.") }}
                        wake.trySend(Unit)
                    }
                },false)
            }
        }
        private fun sendAssets(references:Set<String>) {
            if(transfer?.isActive==true || System.currentTimeMillis()<nextTransferAt || mutable.value.needsDrive && driveToken==null)return
            val wanted=references.filter { !assets.has(it) || !manifests.containsKey(it) }
            if(wanted.isEmpty())return
            transfer=work.launch {
                try {
                    val token=token()?:return@launch
                    val transferContext=currentCoroutineContext()
                    var lastProgressAt=0L
                    for(hash in wanted){
                        ensureActive();if(!ConnectionState.online.value)break
                        val progress:(Long,Long)->Unit={ n,total->
                            transferContext.ensureActive()
                            val now=android.os.SystemClock.elapsedRealtime()
                            if(!closed && (now-lastProgressAt>=250 || total>0 && n>=total)) {
                                lastProgressAt=now
                                mutable.update { current -> current.copy(progress=if(total>0)(n.toDouble()/total).toFloat().coerceIn(0f,1f)else null) }
                            }
                        }
                        val remote=manifests[hash]
                        if(remote!=null && !assets.has(hash)){assets.download(remote,token,progress);reapply.set(true)}
                        else if(remote==null && assets.has(hash)){
                            val manifest=assets.upload(hash,token,progress)
                            root.child("assets").child(hash).setValue(manifest.toString()).await();manifests[hash]=manifest
                        }
                        wake.trySend(Unit)
                    }
                }catch(c:CancellationException){throw c}catch(t:Exception){
                    val oldToken=driveToken;driveToken=null
                    if(oldToken!=null && t.message.orEmpty().contains("Autoriza"))runCatching {
                        Identity.getAuthorizationClient(context).clearToken(ClearTokenRequest.builder().setToken(oldToken).build()).await()
                    }
                    nextTransferAt=System.currentTimeMillis()+30_000;if(!closed)fail(t)
                }finally{if(!closed){mutable.update { current -> current.copy(progress=null) };wake.trySend(Unit)}}
            }
        }
        fun close() {
            CloudPlayback.disconnect()
            closed=true
            root.child("devices").child(actor).updateChildren(mapOf("online" to false,"lastSeen" to ServerValue.TIMESTAMP))
            childListener?.let { root.child("documents").removeEventListener(it) };listeners.forEach { (ref,l)->ref.removeEventListener(l) }
            work.cancel();wake.close()
        }
    }
}
