package com.music.spotui.data.preferences

import org.json.JSONObject

internal fun repairLocalPlaylistRating(raw:String,id:String,explicit:Boolean):String? = runCatching {
    val root=JSONObject(raw)
    val songs=root.optJSONArray("songs") ?: return null
    var changed=false
    for(index in 0 until songs.length()) {
        val song=songs.optJSONObject(index) ?: continue
        val stored=song.optString("spotifyTrackId").removePrefix("spotify:track:").removePrefix("track:").substringBefore('|').substringBefore('?')
            .ifBlank {Regex("^spotify:track:([A-Za-z0-9]{22})(?:[|]|$)").find(song.optString("url"))?.groupValues?.get(1).orEmpty()}
        if(stored==id && (!song.has("explicit") || song.optBoolean("explicit")!=explicit)) {
            song.put("explicit",explicit);changed=true
        }
    }
    if(changed)root.toString() else null
}.getOrNull()
