package com.music.spotui.sync

import android.content.Context
import android.net.Uri
import com.music.spotui.data.preferences.localTrackId
import okhttp3.*
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.RequestBody.Companion.toRequestBody
import okio.BufferedSink
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.io.RandomAccessFile
import java.security.MessageDigest
import java.util.concurrent.TimeUnit

/** Only imported files/covers are copied; YouTube/Spotify streaming caches are never uploaded. */
internal class DriveAssets(private val context:Context) {
    private val folder=File(context.filesDir,"synced_library_files").apply { mkdirs() }
    private val disk=SyncDisk(File(context.filesDir,"device_sync_assets.json"))
    private val state=disk.read()
    private fun part(name:String)=state.optJSONObject(name)?:JSONObject().also { state.put(name,it) }
    private val uris=part("uris");private val ids=part("ids");private val owned=part("owned");private val sessions=part("uploads")
    private val http=OkHttpClient.Builder().connectTimeout(20,TimeUnit.SECONDS).readTimeout(60,TimeUnit.SECONDS).writeTimeout(60,TimeUnit.SECONDS).build()
    private fun local(v:String)=v.startsWith("content://") || v.startsWith("file://") || v.startsWith("/")
    private fun file(hash:String)=File(folder,hash)
    private var batchDepth=0
    @Synchronized fun identityVersion()=uris.length()+ids.length()+owned.length()
    @Synchronized private fun save(){if(batchDepth==0)disk.write(state)}
    private fun identify(uri:String):String {
        if(uri.startsWith("sync_pending:"))return uri.substringAfter(':')
        uris.optString(uri).takeIf { it.matches(Regex("[a-f0-9]{64}")) }?.let { return it }
        val temp=File.createTempFile("import-",".part",folder)
        try {
            val digest=MessageDigest.getInstance("SHA-256")
            val input=if(uri.startsWith("/"))File(uri).inputStream()else context.contentResolver.openInputStream(Uri.parse(uri))
            requireNotNull(input){"No se puede leer un archivo local. Vuelve a seleccionarlo en Archivos locales."}
            input.use { source->temp.outputStream().use { out->val b=ByteArray(128*1024);while(true){val n=source.read(b);if(n<0)break;out.write(b,0,n);digest.update(b,0,n)} } }
            val hash=digest.digest().joinToString(""){"%02x".format(it)}
            if(!file(hash).exists())check(temp.renameTo(file(hash))){"No se pudo preparar el archivo local."}
            uris.put(uri,hash);synchronized(this){uris.put(Uri.fromFile(file(hash)).toString(),hash);save()};return hash
        }finally{temp.delete()}
    }
    @Synchronized fun discover(v:Any) {
        batchDepth++
        try { when(v) {
            is JSONArray->for(i in 0 until v.length())discover(v.get(i))
            is JSONObject->{
                val uri=v.optString("uri").ifBlank { v.optString("url") };val id=v.opt("id")?.toString().orEmpty()
                if(id.isNotBlank() && (local(uri) || uri.startsWith("sync_pending:"))) {
                    val hash=identify(uri);if(!ids.has(hash))ids.put(hash,id);owned.put(id,"asset:$hash");save()
                }else if(id.isNotBlank() && (uri.startsWith("yt_local:") || v.optBoolean("isLocalSpotifyTrack"))){owned.put(id,id);save()}
                v.keys().forEach { val child=v.get(it);if(child is JSONObject || child is JSONArray)discover(child) }
            }
        }
        } finally { batchDepth--;if(batchDepth==0)save() }
    }
    @Synchronized fun isOwned(id:String)=owned.has(id)
    @Synchronized fun portableAlias(v:String):String {
        for(p in listOf("id:","song:"))if(v.startsWith(p))return p+owned.optString(v.removePrefix(p),v.removePrefix(p))
        if(v.startsWith("url:") && (local(v.removePrefix("url:")) || v.startsWith("url:sync_pending:")))return "url:asset:"+identify(v.removePrefix("url:"))
        return owned.optString(v,v)
    }
    private fun nativeId(hash:String):String {
        if(!ids.has(hash)){val id=localTrackId("asset:$hash").toString();ids.put(hash,id);owned.put(id,"asset:$hash");save()}
        return ids.getString(hash)
    }
    private fun nativeUri(hash:String)=if(file(hash).isFile)Uri.fromFile(file(hash)).toString()else "sync_pending:$hash"
    @Synchronized fun nativeAlias(v:String):String {
        for(p in listOf("id:","song:"))if(v.startsWith(p+"asset:"))return p+nativeId(v.removePrefix(p+"asset:"))
        if(v.startsWith("url:asset:"))return "url:"+nativeUri(v.removePrefix("url:asset:"))
        if(v.startsWith("asset:"))return nativeId(v.removePrefix("asset:"))
        return v
    }
    @Synchronized fun portable(v:Any):Any=when(v) {
        is JSONObject->JSONObject().also { o->v.keys().forEach { key->val value=v.get(key);o.put(portableAlias(key),if((key=="id" || key=="songId") && owned.has(value.toString()))owned.getString(value.toString())else portable(value)) } }
        is JSONArray->JSONArray().also { a->for(i in 0 until v.length())a.put(portable(v.get(i))) }
        is String->when {local(v) || v.startsWith("sync_pending:")->"asset:"+identify(v);v.startsWith("id:") || v.startsWith("song:") || v.startsWith("url:")->portableAlias(v);else->v}
        else->v
    }
    @Synchronized fun native(v:Any):Any=when(v) {
        is JSONObject->JSONObject().also { o->v.keys().forEach { key->val value=v.get(key);o.put(nativeAlias(key),if((key=="id" || key=="songId") && value is String && value.startsWith("asset:"))nativeId(value.removePrefix("asset:")).toInt()else native(value)) } }
        is JSONArray->JSONArray().also { a->for(i in 0 until v.length())a.put(native(v.get(i))) }
        is String->if(v.startsWith("asset:"))nativeUri(v.removePrefix("asset:"))else nativeAlias(v)
        else->v
    }
    @Synchronized fun hashes()=folder.listFiles().orEmpty().map { it.name }.filter { it.matches(Regex("[a-f0-9]{64}")) }.toSet()
    @Synchronized fun has(hash:String)=file(hash).isFile
    private fun request(url:String,token:String)=Request.Builder().url(url).header("Authorization","Bearer $token")
    private fun execute(req:Request):Response=http.newCall(req).execute().also { res->if(!res.isSuccessful && res.code!=308){val code=res.code;res.close();error(if(code==401)"Autoriza de nuevo Google Drive."else if(code==403 || code==429)"Drive ha limitado la transferencia. Revisa los permisos y el espacio disponible."else "No se pudo completar la transferencia de Drive ($code).") } }
    fun upload(hash:String,token:String,progress:(Long,Long)->Unit):JSONObject {
        require(hash.matches(Regex("[a-f0-9]{64}")))
        val src=file(hash);val size=src.length();val name="spotui-asset-$hash"
        val url=HttpUrl.Builder().scheme("https").host("www.googleapis.com").addPathSegments("drive/v3/files").addQueryParameter("spaces","appDataFolder")
            .addQueryParameter("q","name = '$name' and trashed = false").addQueryParameter("fields","files(id,size)").build()
        val files=execute(request(url.toString(),token).get().build()).use { JSONObject(it.body!!.string()).getJSONArray("files") }
        if(files.length()>0)return JSONObject().put("hash",hash).put("fileId",files.getJSONObject(0).getString("id")).put("size",size)
        var session=synchronized(this){sessions.optString(hash)}
        if(session.isBlank()) {
            val meta=JSONObject().put("name",name).put("parents",JSONArray().put("appDataFolder")).put("appProperties",JSONObject().put("sha256",hash))
            session=execute(request("https://www.googleapis.com/upload/drive/v3/files?uploadType=resumable&fields=id,size",token)
                .header("X-Upload-Content-Type","application/octet-stream").header("X-Upload-Content-Length",size.toString())
                .post(meta.toString().toRequestBody("application/json; charset=utf-8".toMediaType())).build()).use { it.header("Location")?:error("Drive no ha iniciado la transferencia.") }
            require(java.net.URI(session).let { it.scheme=="https" && (it.host=="www.googleapis.com" || it.host?.endsWith(".googleapis.com")==true) })
            synchronized(this){sessions.put(hash,session);save()}
        }
        var start=0L
        http.newCall(request(session,token).header("Content-Range","bytes */$size").put(ByteArray(0).toRequestBody()).build()).execute().use { res->
            if(res.code==404 || res.code==410){synchronized(this){sessions.remove(hash);save()};error("Se reanudará la transferencia de Drive.")}
            if(res.isSuccessful){synchronized(this){sessions.remove(hash);save()};return JSONObject().put("hash",hash).put("fileId",JSONObject(res.body!!.string()).getString("id")).put("size",size)}
            check(res.code==308){"No se pudo reanudar la transferencia de Drive."};start=(res.header("Range")?.substringAfterLast('-')?.toLongOrNull()?.plus(1))?:0L
        }
        while(start<size) {
            val offset=start;val length=minOf(4L*1024*1024,size-offset)
            val body=object:RequestBody(){
                override fun contentType()="application/octet-stream".toMediaType()
                override fun contentLength()=length
                override fun writeTo(sink:BufferedSink){RandomAccessFile(src,"r").use { input->input.seek(offset);val b=ByteArray(128*1024);var remaining=length;while(remaining>0){val n=input.read(b,0,minOf(b.size.toLong(),remaining).toInt());check(n>0);sink.write(b,0,n);remaining-=n;progress(offset+length-remaining,size)} }}
            }
            execute(request(session,token).header("Content-Range","bytes $offset-${offset+length-1}/$size").put(body).build()).use { res->
                if(res.isSuccessful){synchronized(this){sessions.remove(hash);save()};return JSONObject().put("hash",hash).put("fileId",JSONObject(res.body!!.string()).getString("id")).put("size",size)}
                val next=(res.header("Range")?.substringAfterLast('-')?.toLongOrNull()?.plus(1))?:0L
                check(next>start){"Drive no ha confirmado la transferencia."};start=next
            }
        }
        error("Drive no ha confirmado el archivo completo.")
    }
    fun download(manifest:JSONObject,token:String,progress:(Long,Long)->Unit) {
        val hash=manifest.getString("hash");require(hash.matches(Regex("[a-f0-9]{64}")));if(has(hash))return
        val id=manifest.getString("fileId");require(id.matches(Regex("[A-Za-z0-9_-]+")))
        val temp=File(folder,"$hash.part")
        try {
            val digest=MessageDigest.getInstance("SHA-256")
            execute(request("https://www.googleapis.com/drive/v3/files/$id?alt=media",token).get().build()).use { res->res.body!!.byteStream().use { input->temp.outputStream().use { out->
                val b=ByteArray(128*1024);var done=0L;while(true){val n=input.read(b);if(n<0)break;out.write(b,0,n);digest.update(b,0,n);done+=n;progress(done,manifest.optLong("size"))}
            } } }
            check(digest.digest().joinToString(""){"%02x".format(it)}==hash){"El archivo está incompleto. Se volverá a descargar."}
            check(temp.renameTo(file(hash)));synchronized(this){uris.put(Uri.fromFile(file(hash)).toString(),hash);save()}
        }finally{temp.delete()}
    }
}
