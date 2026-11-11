package com.music.spotui.sync

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import org.json.JSONTokener

/** Explicit app-owned allowlist. Spotify credentials, network caches and device grants are excluded. */
internal class CloudPreferences(private val context:Context,private val assets:DriveAssets) {
    companion object {
        val names=listOf("LocalPlaylists","LocalLibrary","OwnedLikedSongs","CustomMetadata","AlternativeStreams","playlist_youtube_additions_v1",
            "SubscribedPodcasts","spotui_pinned_library_v1","favorite_external_playlists_v1","spotui_podcast_resume_v1","LikedSongs","settings_prefs","app_sort_preferences","translation_prefs","spotui_podcast_mode_prefs","RecentItems","tablet_player_layout")
        private val settings=setOf("stream_quality_wifi","stream_quality_cellular","download_quality","preload_enabled","crossfade_duration_ms","crossfade_dj_mode","web_playback_enabled","video_fallback_enabled","library_grid_view","auto_play_startup","app_language","audio_provider_order","disabled_audio_providers","custom_equalizer_bands","update_repo_url")
        fun allowed(name:String,key:String):Boolean {
            return name!="settings_prefs" || key in settings || key.startsWith("audio_normalization_") || key.startsWith("equalizer_")
        }
    }
    val failedKeys=mutableSetOf<String>()
    private data class Memo(val raw:Any,val identities:Int,val portable:Any)
    private val portableMemory=mutableMapOf<String,Memo>()
    private val discovered=mutableMapOf<String,String>()
    private val rawSnapshots=mutableMapOf<String, Any>()
    private fun encode(value:Any):JSONObject {
        val type=when(value){is String->"string";is Boolean->"boolean";is Int->"int";is Long->"long";is Float->"float";is Set<*>->"set";else->error("Unsupported preference")}
        val json=if(value is Set<*>)JSONArray(value.filterIsInstance<String>().sorted())else if(value is String)
            runCatching { JSONTokener(value).nextValue() }.getOrNull().takeIf { it is JSONObject || it is JSONArray }?:value else value
        return JSONObject().put("type",type).put("value",json)
    }
    fun capture(onFailure:(String)->Unit):Map<String,Any> {
        val out=linkedMapOf<String,Any>()
        rawSnapshots.clear();failedKeys.clear()
        val discoveryKeys=mutableSetOf<String>()
        listOf("LocalLibrary","LocalPlaylists","OwnedLikedSongs","playlist_youtube_additions_v1").filter {SyncOptions.allows("$it:")}.forEach { name ->
            context.getSharedPreferences(name,0).all.forEach discovery@{ (key,raw) ->
                discoveryKeys+="$name:$key"
                if(raw !is String || discovered["$name:$key"]==raw)return@discovery
                runCatching { JSONTokener(raw).nextValue() }.getOrNull()?.let { value ->
                    runCatching { assets.discover(value);discovered["$name:$key"]=raw }.onFailure { onFailure(it.message?:"No se pudo leer un archivo local.") }
                }
            }
        }
        discovered.keys.retainAll(discoveryKeys)
        names.forEach { name->context.getSharedPreferences(name,0).all.forEach entry@{(key,v)->
            if(!SyncOptions.allows("$name:$key") || v==null || !allowed(name,key) || name=="LikedSongs" && !assets.isOwned(key))return@entry
            var logical="$name:$key"
            runCatching {
                val global=assets.portableAlias(key)
                logical="$name:$global"
                val identities=assets.identityVersion()
                val value=portableMemory[logical]?.takeIf { it.raw==v && it.identities==identities }?.portable ?:
                    (if(name=="LikedSongs")JSONObject().put("type","string").put("value",global) else assets.portable(encode(v))).also {
                        portableMemory[logical]=Memo(v,assets.identityVersion(),it)
                    }
                out["$name:$global"]=value
                rawSnapshots["$name:$global"]=v
            }.onFailure { failedKeys+=logical;onFailure(it.message?:"No se pudo leer un archivo local.") }
        } }
        portableMemory.keys.retainAll(out.keys+failedKeys)
        return out
    }
    /** A fresh snapshot prevents a remote update from replacing a pending local edit unnoticed. */
    fun applyIfUnchanged(logical:String,value:Any?,expected:Any?):Boolean =
        if(logical.startsWith("spotui_podcast_resume_v1:"))
            com.music.spotui.data.preferences.PodcastResumeStore.applySync(context) {applyNative(logical,value,expected)}
        else applyNative(logical,value,expected)

    private fun applyNative(logical:String,value:Any?,expected:Any?):Boolean {
        if(!SyncOptions.allows(logical))return false
        val name=logical.substringBefore(':');val key=assets.nativeAlias(logical.substringAfter(':'))
        require(name in names && allowed(name,key))
        val prefs=context.getSharedPreferences(name,0)
        val rawNow=prefs.all[key]
        if(rawNow != rawSnapshots[logical] && !(rawNow == null && expected == null))return false
        val editor=prefs.edit()
        val o=value?.let { assets.native(it) } as? JSONObject
        if(o==null) {
            editor.remove(key)
        } else {
            val v=o.get("value")
            when(o.getString("type")) {
                "string"->editor.putString(key,if(name=="LikedSongs")key else v.toString())
                "boolean"->editor.putBoolean(key,v as Boolean)
                "int"->editor.putInt(key,(v as Number).toInt())
                "long"->editor.putLong(key,(v as Number).toLong())
                "float"->editor.putFloat(key,(v as Number).toFloat())
                "set"->{val a=v as JSONArray;editor.putStringSet(key,(0 until a.length()).map { a.getString(it) }.toSet())}
                else->error("Unsupported preference")
            }
        }
        if(!SyncOptions.allows(logical))return false
        check(editor.commit()){ "No se pudieron guardar los cambios." }
        if(value==null)rawSnapshots.remove(logical)else prefs.all[key]?.let { rawSnapshots[logical]=it }
        return true
    }
}
