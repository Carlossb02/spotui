package com.music.spotui.sync

import android.content.Context
import android.net.Uri
import com.music.spotui.data.preferences.localTrackId
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

/** Text-only reference translation. Class/store names retain compatibility with existing identities. */
internal class DriveAssets(private val context:Context) {
    private val folder=File(context.filesDir,"synced_library_files").apply { mkdirs() }
    private val disk=SyncDisk(File(context.filesDir,"device_sync_assets.json"))
    private val state=disk.read()
    private fun part(name:String)=state.optJSONObject(name)?:JSONObject().also { state.put(name,it) }
    private val uris=part("uris");private val ids=part("ids");private val owned=part("owned")
    private fun local(v:String)=v.startsWith("content://") || v.startsWith("file://") || v.startsWith("/")
    private fun file(hash:String)=File(folder,hash)
    private var batchDepth=0
    @Synchronized fun identityVersion()=uris.length()+ids.length()+owned.length()
    @Synchronized private fun save(){if(batchDepth==0)disk.write(state)}
    // Keep old reference identities, but never open, copy, hash or upload media bytes.
    private fun identify(uri:String):String {
        if(uri.startsWith("sync_pending:"))return uri.substringAfter(':')
        uris.optString(uri).takeIf {it.matches(Regex("[a-f0-9]{64}"))}?.let {return it}
        return SyncTree.digest("local-reference:$uri").also {hash->uris.put(uri,hash);save()}
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
    private fun nativeUri(hash:String):String {
        // A reference received from another device is metadata, never a playable download.
        val known=uris.keys().asSequence().firstOrNull {uris.optString(it)==hash && !it.contains("/synced_library_files/")}
        if(known!=null)return known
        return if(file(hash).isFile)Uri.fromFile(file(hash)).toString()else "sync_pending:$hash"
    }
    @Synchronized fun nativeAlias(v:String):String {
        for(p in listOf("id:","song:"))if(v.startsWith(p+"asset:"))return p+nativeId(v.removePrefix(p+"asset:"))
        if(v.startsWith("url:asset:"))return "url:"+nativeUri(v.removePrefix("url:asset:"))
        if(v.startsWith("asset:"))return nativeId(v.removePrefix("asset:"))
        return v
    }
    @Synchronized fun portable(v:Any):Any=when(v) {
        is JSONObject->JSONObject().also { o->v.keys().forEach { key->val value=v.get(key);o.put(portableAlias(key),if((key=="id" || key=="songId") && owned.has(value.toString()))owned.getString(value.toString())else portable(value)) } }
        is JSONArray->JSONArray().also { a->for(i in 0 until v.length())a.put(portable(v.get(i))) }
        is String->when {v.startsWith("data:",ignoreCase=true)->"";local(v) || v.startsWith("sync_pending:")->"asset:"+identify(v);v.startsWith("id:") || v.startsWith("song:") || v.startsWith("url:")->portableAlias(v);else->v}
        else->v
    }
    @Synchronized fun native(v:Any):Any=when(v) {
        is JSONObject->JSONObject().also { o->v.keys().forEach { key->val value=v.get(key);o.put(nativeAlias(key),if((key=="id" || key=="songId") && value is String && value.startsWith("asset:"))nativeId(value.removePrefix("asset:")).toInt()else native(value)) } }
        is JSONArray->JSONArray().also { a->for(i in 0 until v.length())a.put(native(v.get(i))) }
        is String->if(v.startsWith("asset:"))nativeUri(v.removePrefix("asset:"))else nativeAlias(v)
        else->v
    }
}
