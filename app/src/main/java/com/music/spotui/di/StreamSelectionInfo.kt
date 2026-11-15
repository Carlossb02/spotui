package com.music.spotui.di

import android.content.Context
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import org.json.JSONObject

internal data class StreamSelectionInfo(val videoId:String,val title:String="",val artist:String="",
    val score:Int?=null,val stage:Int?=null,val explicit:Boolean?=null,val criteria:String="",
    val mode:String="Automatic",val selectedAt:Long=System.currentTimeMillis()) {
    val youtubeUrl get()="https://www.youtube.com/watch?v=$videoId"
    fun json()=JSONObject().put("video",videoId).put("title",title).put("artist",artist)
        .put("score",score).put("stage",stage).put("explicit",explicit).put("criteria",criteria)
        .put("mode",mode).put("at",selectedAt)
}

/** Persist only the successful candidate, after ordered concurrent resolution chooses its result. */
internal object StreamSelections {
    private val candidates=java.util.concurrent.ConcurrentHashMap<String,StreamSelectionInfo>()
    private val selected=java.util.concurrent.ConcurrentHashMap<String,StreamSelectionInfo>()
    private val changes=MutableStateFlow(0L)
    val revision=changes.asStateFlow()
    fun local(context:Context,query:String) {
        val info=StreamSelectionInfo("",criteria="Local file; no YouTube candidate was selected.",mode="Local file")
        selected[query]=info
        context.getSharedPreferences("stream_selection_info_v1",0).edit().putString(query,info.json().toString()).apply()
        changes.update {it+1}
    }
    fun candidate(query:String,info:StreamSelectionInfo) {candidates["$query|${info.videoId}"]=info}
    fun get(context:Context,query:String):StreamSelectionInfo? = selected[query] ?: runCatching {
        val raw=context.getSharedPreferences("stream_selection_info_v1",0).getString(query,null) ?: return null
        val o=JSONObject(raw)
        StreamSelectionInfo(o.getString("video"),o.optString("title"),o.optString("artist"),
            if(o.has("score"))o.getInt("score") else null,if(o.has("stage"))o.getInt("stage") else null,
            if(o.has("explicit"))o.getBoolean("explicit") else null,o.optString("criteria"),o.optString("mode"),o.optLong("at"))
            .also {selected[query]=it}
    }.getOrNull()
    fun resolved(context:Context,query:String,videoId:String,mode:String="Automatic") {
        val info=candidates["$query|$videoId"] ?: get(context,query)?.takeIf {it.videoId==videoId}
            ?: StreamSelectionInfo(videoId,criteria="Selection details were not retained for this stream.",mode=mode)
        val result=info.copy(mode=mode,selectedAt=System.currentTimeMillis())
        selected[query]=result
        context.getSharedPreferences("stream_selection_info_v1",0).edit().putString(query,result.json().toString()).apply()
        changes.update {it+1}
    }
}
