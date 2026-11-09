package com.music.spotui.sync

import android.content.Context
import com.music.spotui.data.preferences.*
import org.json.JSONObject
import java.time.Instant
import java.time.YearMonth
import java.time.ZoneId

/** One monotonic counter per device/recording/month, never one cloud event per play. */
internal class CloudHistory(private val context:Context,private val assets:DriveAssets,private val actor:String,private val tracking:JSONObject) {
    private var nativeVersion=-1L
    private var projectedTrees:Map<String,SyncTree>?=null
    private var projectedIdentities=-1
    private var projectedEntries:List<HistoryEntry> = emptyList()
    private fun encode(e:HistoryEntry)=JSONObject().put("ts",e.ts).put("songId",e.songId).put("title",e.title).put("singer",e.singer).put("album",e.album)
        .put("image",e.image).put("url",e.url).put("durationMs",e.durationMs).put("spotifyTrackId",e.spotifyTrackId).put("qualified",e.qualified)
    private fun key(e:HistoryEntry):String {
        val o=assets.portable(encode(e)) as JSONObject
        val identity=e.historySpotifyId().takeIf { it.isNotBlank() }?.let { "spotify:$it" }?:o.optString("url").substringBefore('|').ifBlank { o.get("songId").toString() }
        val period=YearMonth.from(Instant.ofEpochMilli(e.ts).atZone(ZoneId.systemDefault())).toString()
        return "history:"+SyncTree.digest("$period:$identity:${e.qualified}")
    }
    fun scan(docs:Map<String,SyncTree>,put:(String,Any,Boolean)->Unit) {
        val version=deviceListeningHistoryVersion.value
        if(version==nativeVersion)return
        val native=getDeviceListeningHistory(context,limit=Int.MAX_VALUE)
        val seedHistorical=!tracking.optBoolean("_initialized")
        val seen=mutableSetOf<String>()
        native.forEach { e->
            val local=e.storageKey();seen+=local
            val previous=tracking.optJSONObject(local)
            val logical=previous?.getString("key")?:key(e)
            val old=(docs[logical]?.value() as? JSONObject)?:JSONObject()
            val value=JSONObject(old.toString())
            val metadata=assets.portable(encode(e)) as JSONObject
            val metadataOnly=JSONObject(metadata.toString()).apply { remove("ts") }
            val metaHash=SyncTree.digest(SyncTree.flatten(metadataOnly).toSortedMap().toString())
            if(previous==null && docs[logical]==null || previous!=null && previous.optString("metadata")!=metaHash) {
                metadata.keys().forEach { value.put(it,metadata.get(it)) }
            }
            value.put("ts",maxOf(value.optLong("ts"),e.ts))
            val counts=value.optJSONObject("counts")?:JSONObject().also { value.put("counts",it) }
            val track=previous?:JSONObject().put("key",logical).put("current",0).put("total",0L)
            if(previous==null && seedHistorical) {
                // Identical pre-sync historical aggregates have one seed identity, even on restored devices.
                val seed="seed-"+SyncTree.digest("$logical:${e.ts}:${e.playCount}").take(32)
                counts.put(seed,maxOf(counts.optLong(seed),e.playCount.toLong()))
            }else {
                val delta=(e.playCount-track.optInt("current")).coerceAtLeast(0)
                val total=track.optLong("total")+delta
                if(total>0)counts.put(actor,maxOf(counts.optLong(actor),total))
                track.put("total",total)
            }
            track.put("current",e.playCount).put("metadata",metaHash);tracking.put(local,track)
            put(logical,value,true)
        }
        tracking.put("_initialized",true)
        tracking.keys().asSequence().toList().filter { it != "_initialized" && it !in seen }.forEach { tracking.getJSONObject(it).put("current",0) }
        nativeVersion=version
    }
    fun applyDeletes(docs:Map<String,SyncTree>,put:(String,Any,Boolean)->Unit):Set<String> {
        val prefs=context.getSharedPreferences("history_sync_deletes",0);val deletes=prefs.all.keys
        val all="all" in deletes
        val wanted=deletes.mapNotNull { k->prefs.getString(k,null)?.takeIf { it!="all" }?.let { raw->runCatching {
            val o=JSONObject(raw);key(HistoryEntry(o.getLong("ts"),o.getInt("songId"),o.getString("title"),o.getString("singer"),url=o.getString("url"),spotifyTrackId=o.optString("spotifyTrackId"),qualified=o.getBoolean("qualified")))
        }.getOrNull() } }.toSet()
        docs.filterKeys { it.startsWith("history:") && (all || it in wanted) }.forEach { (k,tree)->
            val value=tree.value() as? JSONObject?:return@forEach
            val counts=value.optJSONObject("counts")?:return@forEach
            val reset=value.optJSONObject("reset")?:JSONObject().also { value.put("reset",it) }
            counts.keys().forEach { reset.put(it,maxOf(reset.optLong(it),counts.optLong(it))) };put(k,value,true)
        }
        return deletes
    }
    fun project(docs:Map<String,SyncTree>,force:Boolean=false):List<HistoryEntry> {
        val current=docs.filterKeys { it.startsWith("history:") }
        val previous=projectedTrees
        if(!force && previous!=null && previous.size==current.size &&
            current.all { (key,tree)->previous[key] === tree } && projectedIdentities==assets.identityVersion())return projectedEntries
        val entries=current.values.mapNotNull { tree->
        val p=tree.value() as? JSONObject?:return@mapNotNull null
        val counts=p.optJSONObject("counts")?:return@mapNotNull null;val reset=p.optJSONObject("reset")?:JSONObject()
        val count=counts.keys().asSequence().sumOf { (counts.optLong(it)-reset.optLong(it)).coerceAtLeast(0) }.coerceAtMost(Int.MAX_VALUE.toLong()).toInt()
        if(count==0)return@mapNotNull null
        val o=assets.native(p) as JSONObject
        HistoryEntry(o.getLong("ts"),o.optInt("songId"),o.optString("title"),o.optString("singer"),o.optString("album"),o.optString("image"),o.optString("url"),o.optLong("durationMs"),o.optString("spotifyTrackId"),o.optBoolean("qualified"),playCount=count)
        }.sortedByDescending { it.ts }
        projectedTrees=current
        projectedIdentities=assets.identityVersion()
        projectedEntries=entries
        return entries
    }
    companion object {
        @Volatile var projection:List<HistoryEntry>?=null
        fun requestDelete(context:Context,e:HistoryEntry?=null) {
            val value=if(e==null)"all"else JSONObject().put("ts",e.ts).put("songId",e.songId).put("title",e.title).put("singer",e.singer).put("url",e.url).put("spotifyTrackId",e.spotifyTrackId).put("qualified",e.qualified).toString()
            context.getSharedPreferences("history_sync_deletes",0).edit().putString(if(e==null)"all"else SyncTree.digest(value),value).commit()
            GoogleDeviceSync.requestSync()
        }
    }
}
