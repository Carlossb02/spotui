package com.music.spotui.util

import android.content.ContentProvider
import android.content.ContentValues
import android.database.Cursor
import android.net.Uri
import android.os.ParcelFileDescriptor
import java.io.File
import java.io.FileNotFoundException

/**
 * A [ContentProvider] that serves artwork files safely via `content://com.music.spotui.artwork/...`
 * URIs to external processes like Android Auto and system notification/MediaSession hosts.
 */
class ArtworkContentProvider : ContentProvider() {

    companion object {
        const val AUTHORITY = "com.music.spotui.artwork"

        fun getContentUri(file: File): Uri {
            return Uri.Builder()
                .scheme(android.content.ContentResolver.SCHEME_CONTENT)
                .authority(AUTHORITY)
                .appendPath(file.name)
                .build()
        }

        fun getContentUri(fileName: String): Uri {
            return Uri.Builder()
                .scheme(android.content.ContentResolver.SCHEME_CONTENT)
                .authority(AUTHORITY)
                .appendPath(fileName)
                .build()
        }
    }

    override fun onCreate(): Boolean = true

    override fun openFile(uri: Uri, mode: String): ParcelFileDescriptor? {
        val ctx = context ?: return null
        val fileName = uri.lastPathSegment ?: return null
        val downloadsDir = File(ctx.filesDir, "downloads")
        val file = File(downloadsDir, fileName)

        if (file.exists() && file.length() > 0) {
            return ParcelFileDescriptor.open(file, ParcelFileDescriptor.MODE_READ_ONLY)
        }

        val cacheFile = File(ctx.cacheDir, fileName)
        if (cacheFile.exists() && cacheFile.length() > 0) {
            return ParcelFileDescriptor.open(cacheFile, ParcelFileDescriptor.MODE_READ_ONLY)
        }

        throw FileNotFoundException("Artwork not found for: $fileName")
    }

    override fun query(
        uri: Uri,
        projection: Array<out String>?,
        selection: String?,
        selectionArgs: Array<out String>?,
        sortOrder: String?
    ): Cursor? = null

    override fun getType(uri: Uri): String = "image/jpeg"

    override fun insert(uri: Uri, values: ContentValues?): Uri? = null

    override fun delete(uri: Uri, selection: String?, selectionArgs: Array<out String>?): Int = 0

    override fun update(uri: Uri, values: ContentValues?, selection: String?, selectionArgs: Array<out String>?): Int = 0
}
