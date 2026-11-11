package com.music.spotui.data.entity

private val catalogueTrackIdentity=Regex("^[A-Za-z0-9]{22}$")

/** Editable display metadata and playback search aliases are never membership identities. */
fun SongsModel.libraryIdentity(): String {
    val spotify = spotifyTrackId.removePrefix("spotify:track:").removePrefix("track:").substringBefore('?').substringBefore('|').trim()
    if (spotify.isNotBlank()) return "spotify:$spotify"
    if (url.startsWith("yt_local:")) return "url:$url"
    if (id != 0 && id != -1) return "id:$id"
    return "url:${url.substringBefore('|')}"
}

/** Preserve intentional catalogue repetitions; own additions appear once across all overlays. */
fun mergePlaylistSongs(base:List<SongsModel>,extras:List<SongsModel>):List<SongsModel> {
    val additions=linkedMapOf<String,SongsModel>()
    extras.forEach {additions.putIfAbsent(it.libraryIdentity(),it)}
    val seen=HashSet<String>()
    val result=ArrayList<SongsModel>(base.size+additions.size)
    base.forEach {song->
        val key=song.libraryIdentity()
        val catalog=!song.isLocalSpotifyTrack && catalogueTrackIdentity.matches(key.removePrefix("spotify:"))
        if(seen.add(key) || catalog)result+=if(catalog)song else additions[key]?:song
    }
    additions.forEach {(key,song)->if(seen.add(key))result+=song}
    return result
}
