package com.music.spotui.ui.screens

import android.content.Context
import android.net.Uri
import com.music.spotui.data.entity.SongsModel
import com.music.spotui.data.entity.libraryIdentity
import com.music.spotui.data.preferences.OfflineCollectionsPref

internal fun singleSongAlbumRoute(context:Context,song:SongsModel):String {
    val id="single:"+java.security.MessageDigest.getInstance("SHA-256").digest(song.libraryIdentity().toByteArray())
        .joinToString(""){"%02x".format(it)}
    OfflineCollectionsPref.saveCollection(context,id,song.title,song.coverUri,song.singer,false,listOf(song))
    return "singleAlbum/${Uri.encode(id)}"
}
