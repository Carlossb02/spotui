package com.music.spotui.util

import android.content.ContentProvider
import android.content.ContentValues
import android.database.Cursor
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.net.Uri
import android.os.ParcelFileDescriptor
import android.util.Log
import java.io.File
import java.io.FileOutputStream
import java.net.HttpURLConnection
import java.net.URL

/**
 * A [ContentProvider] that serves artwork files safely via `content://com.music.spotui.artwork/...`
 * URIs to external processes like Android Auto (main and secondary modes) and system notification/MediaSession hosts.
 */
class ArtworkContentProvider : ContentProvider() {

    companion object {
        private const val TAG = "ArtworkContentProvider"
        const val AUTHORITY = "com.music.spotui.artwork"

        fun getContentUri(file: File): Uri {
            return Uri.Builder()
                .scheme(android.content.ContentResolver.SCHEME_CONTENT)
                .authority(AUTHORITY)
                .appendPath(file.name)
                .build()
        }

        fun getContentUri(fileName: String): Uri {
            val clean = fileName.trim().trimStart('/')
            return Uri.Builder()
                .scheme(android.content.ContentResolver.SCHEME_CONTENT)
                .authority(AUTHORITY)
                .appendPath(clean)
                .build()
        }
    }

    override fun onCreate(): Boolean = true

    override fun openFile(uri: Uri, mode: String): ParcelFileDescriptor? {
        val ctx = context ?: return null
        val fileName = uri.lastPathSegment ?: return generateFallbackDescriptor(ctx, "fallback.jpg")

        val downloadsDir = File(ctx.filesDir, "downloads")
        val downloadFile = File(downloadsDir, fileName)
        if (downloadFile.exists() && downloadFile.length() > 0) {
            return ParcelFileDescriptor.open(downloadFile, ParcelFileDescriptor.MODE_READ_ONLY)
        }

        val cacheFile = File(ctx.cacheDir, fileName)
        if (cacheFile.exists() && cacheFile.length() > 0) {
            return ParcelFileDescriptor.open(cacheFile, ParcelFileDescriptor.MODE_READ_ONLY)
        }

        // Try alternative name variations in downloads/
        val altName = when {
            fileName.endsWith("_cover.jpg") -> fileName.removeSuffix("_cover.jpg") + ".jpg"
            fileName.endsWith(".jpg") -> fileName.removeSuffix(".jpg") + "_cover.jpg"
            else -> null
        }
        if (altName != null) {
            val altDownloadFile = File(downloadsDir, altName)
            if (altDownloadFile.exists() && altDownloadFile.length() > 0) {
                return ParcelFileDescriptor.open(altDownloadFile, ParcelFileDescriptor.MODE_READ_ONLY)
            }
        }

        // On-demand fetch/resolve for missing artwork (runs on background binder thread)
        val resolved = resolveArtworkOnDemand(ctx, fileName, cacheFile)
        if (resolved != null && resolved.exists() && resolved.length() > 0) {
            return ParcelFileDescriptor.open(resolved, ParcelFileDescriptor.MODE_READ_ONLY)
        }

        return generateFallbackDescriptor(ctx, fileName)
    }

    private fun resolveArtworkOnDemand(ctx: android.content.Context, fileName: String, cacheFile: File): File? {
        return try {
            // Check if there is a Glide disk cached file for any recent cover
            val id = fileName.removeSuffix("_cover.jpg").removeSuffix(".jpg").removePrefix("song/").removePrefix("playlist/")
            
            // Try fetching from Glide cache
            val glideCacheFile = runCatching {
                com.bumptech.glide.Glide.with(ctx.applicationContext)
                    .downloadOnly()
                    .load("https://i.scdn.co/image/$id")
                    .onlyRetrieveFromCache(true)
                    .submit()
                    .get()
            }.getOrNull()

            if (glideCacheFile != null && glideCacheFile.exists() && glideCacheFile.length() > 0) {
                glideCacheFile.copyTo(cacheFile, overwrite = true)
                return cacheFile
            }
            null
        } catch (e: Exception) {
            Log.w(TAG, "Failed on-demand artwork resolve for $fileName: ${e.message}")
            null
        }
    }

    private fun generateFallbackDescriptor(ctx: android.content.Context, fileName: String): ParcelFileDescriptor? {
        val fallbackFile = File(ctx.cacheDir, "placeholder_$fileName")
        if (!fallbackFile.exists() || fallbackFile.length() == 0L) {
            try {
                val size = 512
                val bitmap = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888)
                val canvas = Canvas(bitmap)
                canvas.drawColor(Color.parseColor("#181818"))

                val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                    color = Color.parseColor("#1DB954")
                    style = Paint.Style.FILL
                    textSize = 120f
                    textAlign = Paint.Align.CENTER
                }
                canvas.drawText("♪", size / 2f, size / 2f + 40f, paint)

                FileOutputStream(fallbackFile).use { out ->
                    bitmap.compress(Bitmap.CompressFormat.JPEG, 85, out)
                }
                bitmap.recycle()
            } catch (e: Exception) {
                Log.e(TAG, "Failed to generate fallback placeholder", e)
                return null
            }
        }
        return try {
            ParcelFileDescriptor.open(fallbackFile, ParcelFileDescriptor.MODE_READ_ONLY)
        } catch (e: Exception) {
            null
        }
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
