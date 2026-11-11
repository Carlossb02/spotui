package com.music.spotui.sync

import android.app.Activity
import android.content.Context
import android.content.Intent
import com.google.android.gms.auth.api.identity.AuthorizationRequest
import com.google.android.gms.auth.api.identity.AuthorizationResult
import com.google.android.gms.auth.api.identity.ClearTokenRequest
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
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
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
    data class Device(val name:String,val online:Boolean,val lastSeen:Long,val id:String="",val kind:String="")
    data class Status(val configured:Boolean=false,val email:String="",val photoUrl:String="",val phase:String="Sin vincular",val pending:Int=0,val filesPending:Int=0,
        val progress:Float?=null,val devices:List<Device> = emptyList(),val lastSync:Long=0L,val error:String?=null,val authorized:Boolean=false,val uid:String="",val accessChecked:Boolean=false,val needsDrive:Boolean=false,val driveBlocked:Boolean=false)
    private val mutable=MutableStateFlow(Status())
    val status=mutable.asStateFlow()
    private val scope=CoroutineScope(SupervisorJob()+Dispatchers.IO)
    private lateinit var context:Context
    @Volatile private var config:GoogleSyncConfig?=null
    @Volatile private var auth:FirebaseAuth?=null
    private val setupMutex=Mutex()
    private val tokenMutex=Mutex()
    @Volatile private var session:Session?=null
    private var accessRef:DatabaseReference?=null
    private var accessListener:ValueEventListener?=null
    private var accessUid=""
    private var accessGeneration=0L
    private var accessTimeout:Job?=null
    private var accessDatabase:FirebaseDatabase?=null
    @Volatile private var accessToken:String?=null
    @Volatile private var tokenAt=0L
    @Volatile private var runtimeActive=false
    @Volatile private var uiForeground=false
    private val visibleLibraries=java.util.concurrent.ConcurrentHashMap.newKeySet<String>()
    private var installed=false
    @Synchronized fun install(app:Context) {
        if(installed)return
        installed=true;context=app.applicationContext
        GoogleSyncJobService.cancelLegacy(context) // Remove persisted polls from older versions immediately.
        SyncRuntime.install(context)
        SyncOptions.install(context)
        ConnectionState.install(context);OwnLibraryChanges.install(context)
        scope.launch {
            runCatching {ensureGoogleReady()}.onSuccess {
                // Restoring a cloud session must never disable a valid Google sign-in setup.
                runCatching {restoreAccount()}.onFailure(::fail)
            }.onFailure {
                if(it is CancellationException)throw it
                mutable.update {current->current.copy(configured=false,phase="No se pudo iniciar Google",error=GoogleSyncConfig.failureMessage(it))}
            }
            ConnectionState.online.collect { if(it)requestSync()else if(session!=null)mutable.update { current -> current.copy(phase="Estás sin conexión") } }
        }
        scope.launch { deviceListeningHistoryVersion.collect { requestSync() } }
    }
    private fun fail(t:Throwable) { if(t is CancellationException)throw t;mutable.update { current -> current.copy(error=t.message?:"No se pudo sincronizar. Se volverá a intentar.",phase="Sincronización pendiente") } }
    private suspend fun ensureGoogleReady()=withContext(Dispatchers.IO) {setupMutex.withLock {
        if(config!=null && auth!=null)return@withLock
        val cfg=GoogleSyncConfig.load(context)
        val service=FirebaseAuth.getInstance(cfg.firebase(context))
        config=cfg;auth=service
        mutable.update {it.copy(configured=true,error=null)}
    }}
    private fun restoreAccount() {
        val prefs=context.getSharedPreferences("google_device_sync_config",0)
        val user=auth?.currentUser
        if(prefs.getBoolean("enabled",false) && user!=null) {
            if(runtimeActive)watchAccess(user.uid,user.email.orEmpty(),user.photoUrl?.toString().orEmpty())
            else mutable.update {it.copy(configured=true,email=user.email.orEmpty(),photoUrl=user.photoUrl?.toString().orEmpty(),uid=user.uid,phase="Sincronización en pausa")}
        }
        else GoogleSyncJobService.cancel(context)
    }
    suspend fun signIn(activity:Activity) {
        mutable.update {it.copy(error=null)}
        try {ensureGoogleReady()}catch(t:Exception) {
            if(t is CancellationException)throw t
            mutable.update {it.copy(configured=false,phase="No se pudo iniciar Google",error=GoogleSyncConfig.failureMessage(t))}
            throw t
        }
        val cfg=checkNotNull(config)
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
        withContext(Dispatchers.IO) { context.getSharedPreferences("google_device_sync_config",0).edit().putBoolean("enabled",true).commit();watchAccess(user.uid,user.email.orEmpty(),user.photoUrl?.toString().orEmpty(),force=true) }
    }
    suspend fun requestDriveAuthorization():AuthorizationResult {
        check(status.value.authorized){"Esta cuenta todavía no está autorizada."}
        val email=auth?.currentUser?.email?:error("Inicia sesión con Google.")
        return Identity.getAuthorizationClient(context).authorize(AuthorizationRequest.builder()
            .setAccount(android.accounts.Account(email,"com.google"))
            .setRequestedScopes(listOf(Scope("https://www.googleapis.com/auth/drive.appdata"))).build()).await()
    }
    fun acceptDriveAuthorization(result:AuthorizationResult,expectedUid:String=status.value.uid) {
        check(status.value.authorized && status.value.uid==expectedUid && auth?.currentUser?.uid==expectedUid){"La cuenta de Google ha cambiado. Vuelve a autorizarla."}
        if("https://www.googleapis.com/auth/drive.appdata" !in result.grantedScopes || result.accessToken.isNullOrBlank()) {
            invalidateDriveToken()
            throw DriveConsentRequired("No se ha concedido el permiso para los datos de Spotui en Drive. Pulsa Autorizar Google Drive y acepta el permiso.")
        }
        val requested=mutable.value.needsDrive
        accessToken=result.accessToken?:throw DriveConsentRequired();tokenAt=System.currentTimeMillis()
        mutable.update {it.copy(needsDrive=false,driveBlocked=false,error=null)}
        session?.driveGranted();requestSync();if(requested)CloudPlayback.driveAvailable()
    }
    fun acceptDriveIntent(data:Intent,expectedUid:String=status.value.uid)=acceptDriveAuthorization(Identity.getAuthorizationClient(context).getAuthorizationResultFromIntent(data),expectedUid)
    internal suspend fun driveToken():String=tokenMutex.withLock {
        check(status.value.authorized){"Cuenta sin autorización."}
        if(status.value.driveBlocked)throw DriveConnectionBlocked(status.value.error.orEmpty())
        accessToken?.takeIf {System.currentTimeMillis()-tokenAt<40*60_000}?.let {return@withLock it}
        if(status.value.needsDrive)throw DriveConsentRequired()
        val expectedUid=status.value.uid
        val result=try {requestDriveAuthorization()}catch(c:CancellationException){throw c}catch(_:Exception){null}
        if(result==null || result.hasResolution()) {
            mutable.update {it.copy(needsDrive=true)};throw DriveConsentRequired()
        }
        acceptDriveAuthorization(result,expectedUid);checkNotNull(accessToken)
    }
    internal fun invalidateDriveToken(expired:String?=null) {
        if(expired!=null && accessToken!=expired)return
        val old=accessToken;accessToken=null;tokenAt=0L
        mutable.update {it.copy(needsDrive=true)}
        if(old!=null)scope.launch {runCatching {Identity.getAuthorizationClient(context).clearToken(ClearTokenRequest.builder().setToken(old).build()).await()}}
    }
    internal fun blockDrive(rejectedToken:String?,message:String) {
        // An old in-flight request must not block a newly selected account/token.
        if(rejectedToken==null || rejectedToken!=accessToken || !status.value.authorized)return
        mutable.update {it.copy(driveBlocked=true,error=message,phase="Conexión con Drive pendiente")}
        GoogleSyncJobService.cancel(context)
    }
    fun retryDriveConnection() {
        if(!status.value.authorized || !status.value.driveBlocked)return
        mutable.update {it.copy(driveBlocked=false,error=null)}
        session?.driveGranted();requestSync();CloudPlayback.driveAvailable()
    }
    suspend fun signOut()=withContext(Dispatchers.IO) {
        context.getSharedPreferences("google_device_sync_config",0).edit().putBoolean("enabled",false).commit()
        accessToken=null;tokenAt=0L
        stopAccessWatch();session?.close();session=null;disconnectDatabase();GoogleSyncJobService.cancel(context);auth?.signOut();CloudHistory.projection=null
        notifySyncedHistoryChanged();mutable.value=Status(configured=config!=null)
    }
    /** A negative answer is durable local state, never a grant of access. */
    private fun accessKey(uid:String)="access:"+SyncTree.digest(config?.projectId.orEmpty()+":"+config?.databaseUrl.orEmpty()+":"+uid)
    private fun savedAccess(uid:String)=context.getSharedPreferences("google_device_sync_config",0).getInt(accessKey(uid),0)
    private fun saveAccess(uid:String,value:Int) {
        context.getSharedPreferences("google_device_sync_config",0).edit().putInt(accessKey(uid),value).commit()
    }
    private fun disconnectDatabase() {
        // SyncDisk owns the durable outbox. Do not leave SDK writes from a revoked
        // or signed-out account waiting to replay when another account connects.
        accessDatabase?.goOffline()
        accessDatabase?.purgeOutstandingWrites()
    }
    @Synchronized private fun stopAccessWatch() {
        accessGeneration++
        accessTimeout?.cancel();accessTimeout=null
        accessListener?.let { listener->accessRef?.removeEventListener(listener) }
        accessRef=null;accessListener=null;accessUid=""
    }
    @Synchronized private fun watchAccess(uid:String,email:String,photoUrl:String,force:Boolean=false) {
        if(!runtimeActive)return
        if(accessUid==uid && accessListener!=null)return
        stopAccessWatch();session?.close();session=null;disconnectDatabase()
        accessToken=null;tokenAt=0L
        accessUid=uid
        mutable.value=Status(configured=true,email=email,photoUrl=photoUrl,uid=uid,phase="Comprobando acceso")
        // No reference, listener, connection or retry is created on this path.
        val saved=savedAccess(uid)
        if(!force && saved<0) {
            applyAccess(uid,email,photoUrl,false,checked=saved == -1)
            return
        }
        val cfg=config?:return
        val database=FirebaseDatabase.getInstance(cfg.firebase(context),cfg.databaseUrl)
        accessDatabase=database
        val ref=database.getReference("authorizedUsers").child(uid)
        val generation=accessGeneration
        val listener=object:ValueEventListener {
            override fun onDataChange(snapshot:DataSnapshot) {scope.launch {synchronized(this@GoogleDeviceSync) {
                if(generation==accessGeneration)applyAccess(uid,email,photoUrl,snapshot.getValue(Boolean::class.java)==true)
            }} }
            override fun onCancelled(error:DatabaseError) {scope.launch {synchronized(this@GoogleDeviceSync) {
                if(generation==accessGeneration)applyAccess(uid,email,photoUrl,false,checked=false)
            }} }
        }
        accessRef=ref;accessListener=listener
        accessTimeout=scope.launch {
            delay(12_000)
            synchronized(this@GoogleDeviceSync) {
                if(generation==accessGeneration && !mutable.value.accessChecked)
                    applyAccess(uid,email,photoUrl,false,checked=false)
            }
        }
        ref.addValueEventListener(listener)
        database.goOnline()
    }
    @Synchronized private fun applyAccess(uid:String,email:String,photoUrl:String,allowed:Boolean,checked:Boolean=true) {
        if(accessUid!=uid || auth?.currentUser?.uid!=uid)return
        accessTimeout?.cancel();accessTimeout=null
        if(!allowed) {
            saveAccess(uid,if(checked)-1 else -2)
            // Invalidate callbacks before closing; reconnect and foreground events
            // cannot re-open access. Only the explicit Settings action does.
            stopAccessWatch()
            session?.close();session=null;disconnectDatabase()
            accessToken=null;tokenAt=0L
            GoogleSyncJobService.cancel(context);CloudHistory.projection=null
            notifySyncedHistoryChanged()
            mutable.update {it.copy(authorized=false,accessChecked=checked,phase=if(checked)"Cuenta sin autorización" else "Acceso sin comprobar",pending=0,filesPending=0,progress=null,devices=emptyList(),needsDrive=false,error=null)}
        } else {
            saveAccess(uid,0)
            mutable.update {it.copy(authorized=true,accessChecked=true,error=null)}
            start(uid,email,photoUrl)
        }
    }
    /** Deliberate permission check, not a manual data synchronization control. */
    fun checkAccess() {
        if(!installed || mutable.value.authorized)return
        scope.launch {
            synchronized(this@GoogleDeviceSync) {
                val user=auth?.currentUser?:return@synchronized
                if(!context.getSharedPreferences("google_device_sync_config",0).getBoolean("enabled",false))return@synchronized
                watchAccess(user.uid,user.email.orEmpty(),user.photoUrl?.toString().orEmpty(),force=true)
            }
        }
    }
    private fun start(uid:String,email:String,photoUrl:String) {
        if(!runtimeActive)return
        if(session?.uid==uid){mutable.update { it.copy(email=email,photoUrl=photoUrl) };return}
        session?.close()
        mutable.value=Status(configured=true,email=email,photoUrl=photoUrl,authorized=true,accessChecked=true,uid=uid,phase="Conectando con Google")
        session=Session(uid,config!!).also { it.start() };GoogleSyncJobService.schedule(context)
    }
    internal fun runtimeChanged(active:Boolean) {scope.launch {synchronized(this@GoogleDeviceSync) {
        runtimeActive=active
        if(!installed || config==null)return@synchronized
        if(active) {
            if(session==null)restoreAccount()
        } else {
            stopAccessWatch();session?.close();session=null;disconnectDatabase()
            GoogleSyncJobService.cancel(context)
            mutable.update {it.copy(devices=it.devices.map {d->d.copy(online=false)},phase="Sincronización en pausa")}
        }
    }}}
    fun onBackground(){uiForeground=false;SyncRuntime.visible(false)}
    fun onPlaybackActivityChanged(){SyncRuntime.playbackChanged();if(!com.music.spotui.di.SongPlayer.isPreparingTrack())requestSync()}
    @Volatile private var lastScheduleAt=0L
    internal fun canSyncPlayer(actor:String)=runtimeActive && mutable.value.authorized && SyncOptions.enabled(SyncOptions.Category.PLAYER) && hasSyncPeer(mutable.value.devices,actor)
    fun hasPendingWork():Boolean=runtimeActive && mutable.value.authorized && !mutable.value.needsDrive && !mutable.value.driveBlocked && session?.hasPendingWork()==true
    fun requestSync(){
        session?.wake?.trySend(Unit)
        val now=android.os.SystemClock.elapsedRealtime()
        if(installed && hasPendingWork() && (!ConnectionState.online.value || session?.hasConnection()!=true) && now-lastScheduleAt>30_000){lastScheduleAt=now;GoogleSyncJobService.schedule(context)}
    }
    fun optionsChanged(){session?.optionsChanged();requestSync()}
    fun localDeviceId():String = if(installed)context.getSharedPreferences("google_device_sync_config",0).getString("device","").orEmpty() else ""
    fun libraryOpened(){if(runtimeActive && mutable.value.authorized)session?.libraryOpened()}
    fun libraryVisible(token:String,visible:Boolean) {
        if(visible)visibleLibraries.add(token)else visibleLibraries.remove(token)
        if(visible)libraryOpened()
    }
    fun onForeground() {uiForeground=true;SyncRuntime.visible(true);scope.launch {synchronized(this@GoogleDeviceSync) {
        if(mutable.value.authorized && session?.blockedByRules()==true) {
            val user=auth?.currentUser?:return@synchronized
            session?.close();session=null;start(user.uid,user.email.orEmpty(),user.photoUrl?.toString().orEmpty())
        }
    }}}
    fun retry(){mutable.update { current -> current.copy(error=null) };requestSync()}
    suspend fun awaitIdle():Boolean {
        requestSync()
        repeat(100){delay(500);if(!hasPendingWork() || session==null || session?.blockedByRules()==true || status.value.needsDrive || status.value.driveBlocked || status.value.let { it.phase=="Sincronizado" && it.pending==0 && it.filesPending==0 })return true}
        return false
    }
    private class Session(val uid:String,cfg:GoogleSyncConfig) {
        val wake=Channel<Unit>(Channel.CONFLATED)
        private val work=CoroutineScope(SupervisorJob()+Dispatchers.IO)
        private val firebase=FirebaseDatabase.getInstance(cfg.firebase(context),cfg.databaseUrl)
        private val root=firebase.reference.child("users").child(uid)
        private val actor=context.getSharedPreferences("google_device_sync_config",0).let { p->p.getString("device",null)?:UUID.randomUUID().toString().also { p.edit().putString("device",it).commit() } }
        private val presence=FirebaseDevicePresence(root.child("devices").child(actor),android.os.Build.MODEL,
            if(context.resources.configuration.smallestScreenWidthDp>=600)"tablet" else "phone") { !closed && connected && runtimeActive }

        private val disk=SyncDisk(File(context.filesDir,"device_sync_"+SyncTree.digest(cfg.projectId+uid).take(32)+".json"))
        private val state=disk.read()
        private val assets=DriveAssets(context)
        private val drive=DriveSyncStore.shared(context,uid)
        @Volatile private var driveJob:Job?=null
        private val remoteReplicas=drive.importedReplicas().toMutableMap()
        private val consumedReplicas=mutableMapOf<String,String>()
        private var playerConnected=false
        private var lastCapability:Boolean?=null
        private var hadPeers=false
        private var discoveryWake:Job?=null
        private var discoveryJob:Job?=null
        private var playbackSettled:Job?=null
        private var discoveryAt=0L
        private var discoveryNeeded=true
        private var discoveryRunning=false
        private var catalogueNeeded=true
        private val legacyKeys=mutableMapOf<String,String>()
        private var legacyRead=state.optBoolean("legacyRead")
        private val prefs=CloudPreferences(context,assets)
        private val docs=mutableMapOf<String,SyncTree>()
        private val observed=mutableMapOf<String,Map<String,String>>()
        private class DocumentView(val tree:SyncTree) {
            val value by lazy { tree.value() }
            val flat by lazy { value?.let(SyncTree::flatten).orEmpty() }
            val wire by lazy { tree.json() }
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
        private val listeners=mutableListOf<Pair<DatabaseReference,ValueEventListener>>()
        @Volatile private var connected=false
        @Volatile private var ready=false
        @Volatile private var closed=false
        private val reapply=AtomicBoolean(false)
        private var clock=state.optLong("clock")
        private var count=state.optLong("counter")
        private var serial=state.optLong("serial")
        @Volatile private var offset=0L
        @Volatile private var nextSendAt=0L
        @Volatile private var rulesBlocked=false
        @Volatile private var retryNeeded=false
        private val tracking=state.optJSONObject("historyTracking")?:JSONObject()
        private val history=CloudHistory(context,assets,actor,tracking)
        init {
            mutable.update { current -> current.copy(lastSync=state.optLong("lastSync")) }
            state.optJSONObject("docs")?.let { o->o.keys().forEach { docs[it]=SyncTree.parse(o.getString(it)) } }
            state.optJSONObject("observed")?.let { o->o.keys().forEach { key->val row=o.getJSONObject(key);observed[key]=row.keys().asSequence().associateWith { row.getString(it) } } }
            state.optJSONObject("pending")?.let { o->o.keys().forEach { pending[it]=o.getLong(it) } }
            state.optJSONObject("legacyKeys")?.let {rows->rows.keys().forEach {legacyKeys[it]=rows.getString(it)}}
            state.optJSONObject("replicas")?.let {rows->rows.keys().forEach {remoteReplicas[it]=rows.getJSONObject(it)}}
            state.optJSONObject("consumed")?.let {rows->rows.keys().forEach {consumedReplicas[it]=rows.getString(it)}}
            retryNeeded=pending.keys.any(SyncOptions::allows)
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
                if(!SyncOptions.allows(key) || key in prefs.failedKeys)return@forEach
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
                .put("historyTracking",tracking).put("legacyRead",legacyRead).put("legacyKeys",JSONObject(legacyKeys)).put("replicas",JSONObject(remoteReplicas)).put("consumed",JSONObject(consumedReplicas)).put("lastSync",mutable.value.lastSync))
        }
        private fun valueListener(ref:DatabaseReference,body:(DataSnapshot)->Unit) {
            val listener=object:ValueEventListener {override fun onDataChange(snapshot:DataSnapshot){post { body(snapshot) }};override fun onCancelled(error:DatabaseError){if(!closed)fail(error.toException())}}
            ref.addValueEventListener(listener);listeners+=ref to listener
        }
        fun start() {
            valueListener(firebase.getReference(".info/serverTimeOffset")){offset=(it.value as? Number)?.toLong()?:0L}
            valueListener(firebase.getReference(".info/connected")){ s->
                connected=s.getValue(Boolean::class.java)==true
                if(!connected)mutable.update {it.copy(devices=it.devices.map {d->d.copy(online=false)})}
                if(connected){
                    nextSendAt=0L
                    work.launch {
                        try {
                            presence.connect()
                        }catch(c:CancellationException){throw c}catch(t:Exception){if(!closed)fail(t)}
                    }
                }
                updatePeerConnection()
                wake.trySend(Unit)
            }
            valueListener(root.child("devices")){ s->
                val devices=s.children.map {row->Device(row.child("name").getValue(String::class.java)?:"Dispositivo",row.child("online").getValue(Boolean::class.java)==true,(row.child("lastSeen").value as? Number)?.toLong()?:0L,row.key.orEmpty(),row.child("kind").getValue(String::class.java).orEmpty())}
                mutable.update {it.copy(devices=devices)}
                updatePeerConnection()
            }
            if(legacyRead)ready=true else root.child("documents").addListenerForSingleValueEvent(object:ValueEventListener {
                override fun onDataChange(snapshot:DataSnapshot)=post {
                    snapshot.children.forEach {row->row.getValue(String::class.java)?.let {raw->runCatching {
                        val o=JSONObject(raw);val key=o.getString("key")
                        if(!CloudPlayback.reserved(key))incoming.merge(key,SyncTree.parse(o.getString("tree"))) {a,b->PodcastProgressSync.merge(key,a,b)}
                        row.key?.let {legacyKeys[key]=it}
                    }.onFailure(::fail)}}
                    legacyRead=true;ready=true
                }
                override fun onCancelled(error:DatabaseError) {if(error.code==DatabaseError.PERMISSION_DENIED)denyRules()else fail(error.toException())}
            })
            // One-time compatibility import of the old tiny replica pointers.
            // New library changes never read or write Firebase replica signals.
            if(!drive.migrated())root.child("replicas").addListenerForSingleValueEvent(object:ValueEventListener {
                override fun onDataChange(s:DataSnapshot)=post {
                    s.children.forEach {row->row.getValue(String::class.java)?.let {raw->runCatching {remoteReplicas[row.key.orEmpty()]=JSONObject(raw)}}}
                    drive.markMigrated(remoteReplicas)
                }
                override fun onCancelled(e:DatabaseError){if(!closed)fail(e.toException())}
            })
            work.launch {
                for(unit in wake) {
                    // Batch local edits, but apply received data/acknowledgments promptly.
                    // Network discovery retains its existing foreground/background cadence.
                    delay(if(events.isEmpty() && incoming.isEmpty() && acknowledgments.isEmpty())750L else 50L)
                    try {
                        while(true){val event=events.poll()?:break;event()}
                        cycle()
                    }catch(c:CancellationException){throw c}catch(t:Exception){fail(t);GoogleSyncJobService.schedule(context)}
                }
            }

            discoveryJob=work.launch {
                while(isActive) {
                    delay(5_000)
                    val interval=if(uiForeground && visibleLibraries.isNotEmpty())5_000L else 30_000L
                    if(dataSyncEnabled() && hasSyncPeer(mutable.value.devices,actor) && System.currentTimeMillis()-discoveryAt>=interval)
                        post {discoveryNeeded=true}
                }
            }
            wake.trySend(Unit)
        }
        private fun updatePeerConnection() {
            val peers=runtimeActive && connected && hasSyncPeer(mutable.value.devices,actor)
            val enabled=SyncOptions.enabled(SyncOptions.Category.PLAYER)
            if(peers!=hadPeers) {
                hadPeers=peers;discoveryWake?.cancel()
                val remaining=(15_000-(System.currentTimeMillis()-discoveryAt)).coerceAtLeast(0)
                val wait=if(peers)remaining else maxOf(15_000,remaining)
                discoveryNeeded=wait==0L
                if(wait>0)discoveryWake=work.launch {delay(wait);post {discoveryNeeded=true}}
            }
            if(!peers)lastCapability=null
            else if(lastCapability!=enabled) {
                lastCapability=enabled
                work.launch {try {
                    if(!closed && hasSyncPeer(mutable.value.devices,actor))root.child("playback").child("capabilities").child(actor).setValue(mapOf("enabled" to enabled)).await()
                }catch(c:CancellationException){throw c}catch(t:Exception){if(!closed)fail(t)}}
            }
            val wanted=peers && enabled
            if(wanted!=playerConnected) {
                playerConnected=wanted
                if(wanted)CloudPlayback.connect(context,root,actor,assets)else CloudPlayback.disconnect()
            }
        }
        private fun cycle() {
            // Foreground source selection owns CPU/disk/network until audio is prepared.
            if(com.music.spotui.di.SongPlayer.isPreparingTrack()) {
                if(playbackSettled?.isActive!=true)playbackSettled=work.launch {delay(750);wake.trySend(Unit)}
                return
            }
            playbackSettled?.cancel();playbackSettled=null
            if(incoming.isNotEmpty())PodcastResumeStore.flush(context)
            var snapshot=scan() // Export every pending native edit before accepting remote fields.
            incoming.entries.toList().forEach { (k,v)->if(incoming.remove(k,v)){docs[k]=PodcastProgressSync.merge(k,docs[k]?:SyncTree(),v);observe(v)} }
            acknowledgments.entries.toList().forEach { (k,g)->if(acknowledgments.remove(k,g) && pending[k]==g)pending.remove(k) }
            val historyEnabled=SyncOptions.enabled(SyncOptions.Category.HISTORY)
            val deleted=if(historyEnabled)history.applyDeletes(docs,::put)else emptySet()
            if(historyEnabled)history.scan(docs,::put)
            save() // Outbox + counters + deletion resets are one atomic snapshot.
            if(deleted.isNotEmpty()){val e=context.getSharedPreferences("history_sync_deletes",0).edit();deleted.forEach(e::remove);e.commit()}
            // Catch edits made while a large local file was being prepared.
            snapshot=scan()
            var applied=false
            val appliedKeys=mutableSetOf<String>()
            val restoreFiles=reapply.getAndSet(false)
            docs.filterKeys { !it.startsWith("history:") && SyncOptions.allows(it) }.forEach { (key,_)->
                val document=view(key);val value=document.value;val flat=document.flat
                if(flat!=observed[key] || restoreFiles){
                    if(prefs.applyIfUnchanged(key,value,snapshot[key])){observed[key]=flat;applied=true;appliedKeys+=key}else wake.trySend(Unit)
                }
            }
            if(applied){
                invalidateAlternativeStreamMemory()
                SubscribedPodcastPref.notifySyncedChange()
                OwnLibraryChanges.changed();com.music.spotui.data.api.SavedTrackLibrary.localChanged(context)
                if(appliedKeys.any {it.startsWith("settings_prefs:")})work.launch(Dispatchers.Main) {
                    if(!SyncOptions.enabled(SyncOptions.Category.SETTINGS))return@launch
                    if("settings_prefs:app_language" in appliedKeys)SpotuiLanguage.initialize(context)
                    if(appliedKeys.any {it.contains(":audio_normalization_")})com.music.spotui.di.SongPlayer.onNormalizationSettingChanged(context)
                    if(appliedKeys.any {it.contains(":equalizer_") || it.endsWith(":custom_equalizer_bands")})com.music.spotui.di.SongPlayer.onEqualizerSettingChanged(context)
                }
            }
            val projection=if(SyncOptions.enabled(SyncOptions.Category.HISTORY))history.project(docs,force=restoreFiles)else null
            if(CloudHistory.projection!=projection){CloudHistory.projection=projection;notifySyncedHistoryChanged()}
            save()
            val pendingCount=pending.keys.count(SyncOptions::allows)
            val missing=0
            val awaitingReplica=dataSyncEnabled() && remoteReplicas.any {it.key!=actor && consumedReplicas[it.key]!=it.value.optString("rev")}
            retryNeeded=pendingCount>0 || awaitingReplica || catalogueNeeded && dataSyncEnabled()
            mutable.update { current -> current.copy(pending=pendingCount,filesPending=missing,phase=when { rulesBlocked->"Conexión no disponible";mutable.value.driveBlocked->"Conexión con Drive pendiente";mutable.value.needsDrive->"Permiso de Drive pendiente"; !ConnectionState.online.value->"Estás sin conexión";!ready->"Conectando con Google";pendingCount>0 || missing>0 || awaitingReplica || driveJob?.isActive==true->"Sincronizando";else->"Sincronizado" },lastSync=if(!rulesBlocked && !mutable.value.driveBlocked && !mutable.value.needsDrive && !awaitingReplica && ConnectionState.online.value && ready && pendingCount==0 && missing==0 && current.phase!="Sincronizado")System.currentTimeMillis()else current.lastSync) }
            if(ConnectionState.online.value && ready){syncDrive()}else if(retryNeeded)GoogleSyncJobService.schedule(context)
        }
        private fun dataSyncEnabled()=SyncOptions.Category.entries.any {it!=SyncOptions.Category.PLAYER && SyncOptions.enabled(it)}
        private fun syncDrive() {
            if(rulesBlocked || driveJob?.isActive==true || status.value.needsDrive || status.value.driveBlocked || System.currentTimeMillis()<nextSendAt)return
            val wanted=if(dataSyncEnabled())remoteReplicas.filter {it.key!=actor && consumedReplicas[it.key]!=it.value.optString("rev")}.mapValues {JSONObject(it.value.toString())}else emptyMap()
            val outgoing=pending.filterKeys(SyncOptions::allows).toMap()
            val discover=dataSyncEnabled() && (discoveryNeeded || outgoing.isNotEmpty()) && !discoveryRunning && System.currentTimeMillis()-discoveryAt>=if(uiForeground && visibleLibraries.isNotEmpty())5_000 else 15_000
            if(wanted.isEmpty() && outgoing.isEmpty() && !(catalogueNeeded && dataSyncEnabled()) && !discover)return
            val trees=docs.filterKeys(SyncOptions::allows).toMap()
            driveJob=work.launch {
                try {
                    val token=driveToken()
                    if(discover) {
                        discoveryRunning=true;discoveryAt=System.currentTimeMillis()
                        val pointers=drive.replicas(token)
                        post {pointers.forEach {(device,pointer)->remoteReplicas[device]=pointer};discoveryNeeded=false;discoveryRunning=false}
                    }
                    for((device,pointer) in wanted) {
                        ensureActive()
                        val body=drive.get(pointer,token)
                        require(body.getString("actor")==device){"La réplica no pertenece al dispositivo anunciado."}
                        val next=body.getJSONObject("docs")
                        val decoded=next.keys().asSequence().associateWith {SyncTree.parse(next.getString(it))}
                        post {
                            decoded.forEach {(key,tree)->if(SyncOptions.allows(key))incoming.merge(key,tree) {a,b->PodcastProgressSync.merge(key,a,b)}}
                            consumedReplicas[device]=pointer.getString("rev")
                        }
                    }
                    if(outgoing.isNotEmpty() || catalogueNeeded && dataSyncEnabled()) {
                        if(wanted.isNotEmpty())return@launch // Merge the downloads before publishing on the next turn.
                        val exported=JSONObject();trees.forEach {(key,tree)->if(SyncOptions.allows(key))exported.put(key,tree.json())}
                        val pointer=drive.put("replica:$actor",JSONObject().put("actor",actor).put("docs",exported),token)
                        ensureActive()
                        post {
                            catalogueNeeded=false
                            outgoing.forEach {(key,version)->if(SyncOptions.allows(key))acknowledgments[key]=version}
                            mutable.update {it.copy(error=null)}
                        }
                        // Delete the old data only after its durable Drive replica has been published.
                        val migrated=legacyKeys.filterKeys {CloudPlayback.reserved(it) || it in trees && SyncOptions.allows(it)}
                        if(migrated.isNotEmpty()) {
                            root.child("documents").updateChildren(migrated.values.associateWith {null}).await()
                            post {migrated.keys.forEach(legacyKeys::remove)}
                        }
                    }
                }catch(c:CancellationException){throw c}
                catch(_:DriveConsentRequired){if(!closed && isActive)mutable.update {it.copy(needsDrive=true,phase="Permiso de Drive pendiente")}}
                catch(_:DriveConnectionBlocked){} // Requires a deliberate retry after correcting the problem.
                catch(t:Exception){
                    if(closed || !isActive || status.value.driveBlocked)return@launch
                    if(t.message.orEmpty().contains("Permission denied",true))denyRules()
                    else {nextSendAt=System.currentTimeMillis()+30_000;fail(t);work.launch {delay(30_000);wake.trySend(Unit)}}
                }finally {post {driveJob=null;discoveryRunning=false}}
            }
        }
        private fun denyRules() {
            rulesBlocked=true
            mutable.update {it.copy(phase="Conexión no disponible",error="El servicio de sincronización no permite esta conexión. [SYNC_RULES]")}
        }
        fun blockedByRules()=rulesBlocked
        fun hasConnection()=connected
        fun hasPendingWork()= !closed && !rulesBlocked && retryNeeded
        fun driveGranted() {nextSendAt=0L;wake.trySend(Unit);GoogleSyncJobService.schedule(context)}
        fun libraryOpened() {
            post {
                if(!dataSyncEnabled())return@post
                discoveryNeeded=true
                val wait=((if(uiForeground && visibleLibraries.isNotEmpty())5_000L else 15_000L)-(System.currentTimeMillis()-discoveryAt)).coerceAtLeast(0)
                if(wait>0) {
                    discoveryWake?.cancel()
                    discoveryWake=work.launch {delay(wait);wake.trySend(Unit)}
                }
            }
        }
        fun optionsChanged() {
            post {updatePeerConnection();discoveryNeeded=true}
            nextSendAt=0L
            post {consumedReplicas.clear()}
            driveJob?.cancel()
            if(!dataSyncEnabled() && !SyncOptions.enabled(SyncOptions.Category.PLAYER))mutable.update {it.copy(needsDrive=false)}
            reapply.set(true)
            if(!SyncOptions.enabled(SyncOptions.Category.HISTORY)) {
                CloudHistory.projection=null
                notifySyncedHistoryChanged()
            }
            wake.trySend(Unit)
        }
        fun close() {
            CloudPlayback.disconnect()
            closed=true
            presence.disconnect()
            listeners.forEach { (ref,l)->ref.removeEventListener(l) }
            work.cancel();wake.close()
        }
    }
}
