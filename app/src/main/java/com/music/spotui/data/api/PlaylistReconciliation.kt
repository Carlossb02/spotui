package com.music.spotui.data.api

import com.music.spotui.data.entity.SongsModel

internal fun exportPlaylistUri(song: SongsModel): String? {
    if (song.isLocalSpotifyTrack || song.url.startsWith("file:") || song.url.startsWith("content:") || song.url.startsWith("spotify:local:")) return null
    val id=song.spotifyTrackId.removePrefix("spotify:track:").substringBefore('|').substringBefore('?')
        .ifBlank { Regex("^spotify:track:([A-Za-z0-9]{22})(?:[|]|$)").find(song.url)?.groupValues?.get(1).orEmpty() }
    return id.takeIf { it.matches(Regex("[A-Za-z0-9]{22}")) }?.let { "spotify:track:" + it }
}

/** Three-way sequence merge: unchanged sides follow the edited side. Simultaneous
 * additions converge by maximum occurrence count; concurrent deletions never remove more occurrences than either side removed.
 * Local order wins concurrent reorder conflicts, while new remote items remain present. */
internal fun reconcilePlaylist(base: List<String>, local: List<String>, remote: List<String>): List<String> {
    if (local == remote || remote == base) return local
    if (local == base) return remote
    val b=base.groupingBy {it}.eachCount(); val l=local.groupingBy {it}.eachCount(); val r=remote.groupingBy {it}.eachCount()
    val remaining=(b.keys+l.keys+r.keys).associateWith { key ->
        val original=b[key]?:0; val ours=l[key]?:0; val theirs=r[key]?:0
        minOf(original,ours,theirs) + maxOf((ours-original).coerceAtLeast(0), (theirs-original).coerceAtLeast(0))
    }.toMutableMap()
    val result=mutableListOf<String>()
    (local+remote).forEach { key -> if((remaining[key]?:0)>0) {result+=key;remaining[key]=(remaining[key]?:0)-1} }
    return result
}

/** Keep non-exportable tracks on-device in their relative slots, including duplicates. */
internal fun retainLocalPlaylistTracks(local: List<SongsModel>, synced: List<SongsModel>): List<SongsModel> {
    val slots=mutableMapOf<Int,MutableList<SongsModel>>(); var index=0
    local.forEach { if(exportPlaylistUri(it)==null)slots.getOrPut(minOf(index,synced.size)){mutableListOf()}.add(it) else index++ }
    return buildList { for(i in 0..synced.size) {addAll(slots[i].orEmpty());synced.getOrNull(i)?.let(::add)} }
}

internal enum class PendingPlaylistResult { APPLIED, NOT_APPLIED, CONCURRENT }
internal fun pendingPlaylistResult(before:List<String>,after:List<String>,remote:List<String>) = when(remote) {
    after -> PendingPlaylistResult.APPLIED
    before -> PendingPlaylistResult.NOT_APPLIED
    else -> PendingPlaylistResult.CONCURRENT
}
