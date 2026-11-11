package com.music.spotui.ui.utils

import com.music.spotui.data.entity.SongsModel

/** Metadata and position are mutable; neither belongs in a list item's identity. */
fun stableSongKeys(songs:List<SongsModel>):List<String> {
    val occurrences=HashMap<String,Int>()
    return songs.map {song->
        val spotify=song.spotifyTrackId.removePrefix("spotify:track:").removePrefix("track:").substringBefore('?').trim()
        val identity=if(spotify.isNotBlank())"spotify:$spotify" else if(song.id!=0 && song.id != -1)"song:${song.id}" else "url:${song.url}"
        val occurrence=occurrences[identity]?:0
        occurrences[identity]=occurrence+1
        "$identity#$occurrence"
    }
}
