package com.music.spotui.sync

import android.content.Context
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.MultipartBody
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.io.File
import java.util.zip.GZIPInputStream
import java.util.zip.GZIPOutputStream
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/** Bounded, replaceable text files per device. Never uploads media or authentication data. */
internal class DriveSyncStore(context:Context,private val uid:String,private val http:OkHttpClient=OkHttpClient.Builder().connectTimeout(15,java.util.concurrent.TimeUnit.SECONDS).readTimeout(30,java.util.concurrent.TimeUnit.SECONDS).build()) {
    companion object {
        private val stores=mutableMapOf<String,DriveSyncStore>()
        @Synchronized fun shared(context:Context,uid:String):DriveSyncStore {
            val app=context.applicationContext
            return stores.getOrPut(app.filesDir.absolutePath+":"+uid) {DriveSyncStore(app,uid)}
        }
    }
    private val directory=File(context.filesDir,"google_text_sync").apply {mkdirs()}
    private val index=SyncDisk(File(directory,"index-${SyncTree.digest(uid)}.json"))
    private val files=index.read()
    private val lock=Mutex()
    private val migration=context.getSharedPreferences("google_device_sync_config",0)
    private val importKey="drive_imported_"+SyncTree.digest(uid)
    fun migrated()=migration.contains(importKey)
    fun importedReplicas():Map<String,JSONObject> = runCatching {
        val data=JSONObject(migration.getString(importKey,"{}").orEmpty())
        data.keys().asSequence().associateWith {data.getJSONObject(it)}
    }.getOrDefault(emptyMap())
    fun markMigrated(pointers:Map<String,JSONObject>) {
        check(migration.edit().putString(importKey,JSONObject(pointers).toString()).commit())
    }
    suspend fun replicas(token:String):Map<String,JSONObject> = lock.withLock {
        val result=mutableMapOf<String,JSONObject>();var page=""
        do {
            val url="https://www.googleapis.com/drive/v3/files".toHttpUrl().newBuilder()
                .addQueryParameter("spaces","appDataFolder")
                .addQueryParameter("q","trashed = false and appProperties has { key='spotuiKind' and value='replica' }")
                .addQueryParameter("fields","nextPageToken,files(id,appProperties)").addQueryParameter("pageSize","100")
                .apply {if(page.isNotBlank())addQueryParameter("pageToken",page)}.build()
            val body=JSONObject(String(execute(request(url.toString(),token).get().build()),Charsets.UTF_8))
            val rows=body.getJSONArray("files")
            for(i in 0 until rows.length()) {
                val row=rows.getJSONObject(i);val props=row.optJSONObject("appProperties")?:continue
                val actor=props.optString("spotuiActor");val revision=props.optString("spotuiRevision")
                val id=row.getString("id")
                if(actor.isNotBlank() && revision.isNotBlank() && id.matches(Regex("[A-Za-z0-9_-]+")))result[actor]=JSONObject().put("id",id).put("rev",revision)
            }
            page=body.optString("nextPageToken")
        }while(page.isNotBlank())
        result
    }
    private fun request(url:String,token:String)=Request.Builder().url(url).header("Authorization","Bearer $token")
    private fun execute(request:Request):ByteArray=http.newCall(request).execute().use {response->
        if(!response.isSuccessful) {
            val raw=runCatching {response.body?.byteStream()?.use {readLimited(it,64*1024)}?.toString(Charsets.UTF_8)}.getOrNull().orEmpty()
            val failure=DriveRequestFailure.parse(response.code,raw)
            val token=request.header("Authorization")?.removePrefix("Bearer ")
            if(failure.requiresConsent) {
                GoogleDeviceSync.invalidateDriveToken(token)
                throw DriveConsentRequired(failure.message ?: "Autoriza Google Drive para sincronizar tus datos.")
            }
            if(failure.stopsRetries)GoogleDeviceSync.blockDrive(token,failure.message.orEmpty())
            throw failure
        }
        val body=response.body?:error("Drive devolvió una respuesta vacía.")
        body.byteStream().use {readLimited(it)}
    }
    private fun readLimited(input:java.io.InputStream,limit:Int=64*1024*1024):ByteArray {
        val output=ByteArrayOutputStream();val buffer=ByteArray(16*1024);var size=0
        while(true) {
            val n=input.read(buffer);if(n<0)break
            size+=n;check(size<=limit){"Los datos de sincronización exceden el límite."}
            output.write(buffer,0,n)
        }
        return output.toByteArray()
    }
    suspend fun put(purpose:String,value:JSONObject,token:String):JSONObject=lock.withLock {
        val content=JSONObject(value.toString())
        val key=SyncTree.digest("$uid:$purpose")
        val cached=files.optJSONObject(key)
        var id=cached?.optString("id").orEmpty()
        val name="spotui-text-$key.json.gz"
        if(id.isBlank()) {
            val url="https://www.googleapis.com/drive/v3/files".toHttpUrl().newBuilder()
                .addQueryParameter("spaces","appDataFolder").addQueryParameter("q","name = '$name' and trashed = false")
                .addQueryParameter("fields","files(id)").build()
            val listed=JSONObject(String(execute(request(url.toString(),token).get().build()),Charsets.UTF_8)).getJSONArray("files")
            if(listed.length()>0)id=listed.getJSONObject(0).getString("id")
        }
        if(id.isNotBlank())require(id.matches(Regex("[A-Za-z0-9_-]+")))
        // Turning off a category preserves the already published data, without publishing local edits.
        if(purpose.startsWith("replica:") && id.isNotBlank()) {
            val file=File(directory,"$id.json")
            val previous=(if(file.isFile)runCatching {JSONObject(file.readText())}.getOrNull()else null)
                ?:JSONObject(readRemote(id,token))
            val oldDocs=previous.optJSONObject("docs");val docs=content.getJSONObject("docs")
            oldDocs?.keys()?.forEach {key->if(!SyncOptions.allows(key))docs.put(key,oldDocs.get(key))}
        }
        val raw=content.toString();val revision=SyncTree.digest(raw)
        if(cached?.optString("rev")==revision && (!purpose.startsWith("replica:") || cached.optBoolean("catalogued")))return@withLock JSONObject(cached.toString())
        val bytes=ByteArrayOutputStream().also {out->GZIPOutputStream(out).use {it.write(raw.toByteArray(Charsets.UTF_8))}}.toByteArray()
        val creating=id.isBlank()
        val metadata=JSONObject()
        if(creating)metadata.put("name",name).put("parents",org.json.JSONArray().put("appDataFolder"))
        if(purpose.startsWith("replica:"))metadata.put("appProperties",JSONObject().put("spotuiKind","replica")
            .put("spotuiActor",purpose.removePrefix("replica:")).put("spotuiRevision",revision))
        val body=MultipartBody.Builder().setType("multipart/related".toMediaType())
            .addPart(metadata.toString().toRequestBody("application/json; charset=UTF-8".toMediaType()))
            .addPart(bytes.toRequestBody("application/gzip".toMediaType())).build()
        val url=if(creating)"https://www.googleapis.com/upload/drive/v3/files?uploadType=multipart&fields=id"
            else "https://www.googleapis.com/upload/drive/v3/files/$id?uploadType=multipart&fields=id"
        val uploaded=execute(request(url,token).apply {if(creating)post(body)else patch(body)}.build())
        if(creating)id=JSONObject(String(uploaded,Charsets.UTF_8)).getString("id")
        require(id.matches(Regex("[A-Za-z0-9_-]+")))
        val pointer=JSONObject().put("id",id).put("rev",revision).also {if(purpose.startsWith("replica:"))it.put("catalogued",true)}
        files.put(key,pointer);index.write(files)
        File(directory,"$id.json").writeText(raw)
        pointer
    }
    private fun readRemote(id:String,token:String):String {
        val bytes=execute(request("https://www.googleapis.com/drive/v3/files/$id?alt=media",token).get().build())
        val input=if(bytes.size>1 && bytes[0]==0x1f.toByte() && bytes[1]==0x8b.toByte())GZIPInputStream(bytes.inputStream())else bytes.inputStream()
        return input.use {String(readLimited(it),Charsets.UTF_8)}
    }
    suspend fun get(pointer:JSONObject,token:String):JSONObject=lock.withLock {
        val id=pointer.getString("id");require(id.matches(Regex("[A-Za-z0-9_-]+")))
        val cached=File(directory,"$id.json")
        if(cached.isFile) {
            val raw=cached.readText()
            if(SyncTree.digest(raw)==pointer.optString("rev"))return@withLock JSONObject(raw)
        }
        val raw=readRemote(id,token);val parsed=JSONObject(raw)
        // A newer complete replica is also valid: only its owning device writes this file.
        cached.writeText(raw);parsed
    }
}
internal class DriveConsentRequired(message:String="Autoriza Google Drive para sincronizar tus datos."):IllegalStateException(message)
internal class DriveConnectionBlocked(message:String):IllegalStateException(message)
