package com.music.spotui.data.update

import android.content.Context
import android.util.Log
import com.music.spotui.BuildConfig
import com.music.spotui.data.preferences.getUpdateRepoUrl
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONObject
import java.util.concurrent.TimeUnit

object UpdateChecker {

    private const val TAG = "UpdateChecker"
    private const val PREFS = "update_prefs"
    private const val KEY_SKIP = "skip_fingerprint"

    data class UpdateInfo(
        val version: String,
        val downloadUrl: String,
        val fingerprint: String,
        val releaseBody: String,
    )

    private val client = OkHttpClient.Builder()
        .connectTimeout(8, TimeUnit.SECONDS)
        .readTimeout(8, TimeUnit.SECONDS)
        .build()

    suspend fun check(context: Context): UpdateInfo? = withContext(Dispatchers.IO) {
        val info = runCatching { fetchLatestRelease(context) }
            .onFailure { Log.d(TAG, "update check failed: ${it.message}") }
            .getOrNull() ?: return@withContext null
        if (!isNewer(info.version, BuildConfig.VERSION_NAME)) return@withContext null
        val skipped = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .getString(KEY_SKIP, null)
        if (skipped == info.fingerprint) return@withContext null
        info
    }

    fun skipRelease(context: Context, info: UpdateInfo) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .edit().putString(KEY_SKIP, info.fingerprint).apply()
    }

    private fun fetchLatestRelease(context: Context): UpdateInfo? {
        val repoUrl = getUpdateRepoUrl(context).trimEnd('/')
        val repoPath = repoUrl.removePrefix("https://github.com/")
            .removePrefix("http://github.com/")
            .removeSuffix("/releases")
            .removeSuffix("/releases/latest")
            .trimEnd('/')
        val cleanRepoUrl = "https://github.com/$repoPath"
        val apiLatest = "https://api.github.com/repos/$repoPath/releases/latest"
        val apiReleases = "https://api.github.com/repos/$repoPath/releases"
        val releasesPage = "$cleanRepoUrl/releases/latest"

        val request = Request.Builder()
            .url(apiLatest)
            .header("Accept", "application/vnd.github+json")
            .header("User-Agent", "Spotui-App")
            .build()
        val response = client.newCall(request).execute()
        val body = response.body?.string()
        var json: JSONObject? = null

        if (response.isSuccessful && !body.isNullOrBlank()) {
            runCatching { json = JSONObject(body) }
        }

        // Fallback: if /releases/latest failed or returned invalid json, fetch /releases list and pick the first release
        if (json == null || json.optString("tag_name").trim().isBlank()) {
            val listRequest = Request.Builder()
                .url(apiReleases)
                .header("Accept", "application/vnd.github+json")
                .header("User-Agent", "Spotui-App")
                .build()
            val listResponse = client.newCall(listRequest).execute()
            if (listResponse.isSuccessful) {
                val listBody = listResponse.body?.string()
                if (!listBody.isNullOrBlank()) {
                    runCatching {
                        val arr = org.json.JSONArray(listBody)
                        if (arr.length() > 0) {
                            json = arr.optJSONObject(0)
                        }
                    }
                }
            }
        }

        val j = json ?: return null
        val tag = j.optString("tag_name").trim()
        if (tag.isBlank()) return null
        val version = extractVersion(tag)
            ?: return null
        val releaseBody = j.optString("body", "")
        val htmlUrl = j.optString("html_url", releasesPage)
            .ifBlank { releasesPage }
        val assets = j.optJSONArray("assets")
        val apkUrl = (0 until (assets?.length() ?: 0))
            .asSequence()
            .mapNotNull { assets?.optJSONObject(it) }
            .firstOrNull { it.optString("name").endsWith(".apk") }
            ?.optString("browser_download_url")
        return UpdateInfo(
            version = version,
            downloadUrl = apkUrl?.ifBlank { null } ?: htmlUrl,
            fingerprint = "${j.optLong("id")}:${j.optString("updated_at")}:$version",
            releaseBody = releaseBody,
        )
    }

    private fun extractVersion(text: String): String? =
        Regex("""\d+(?:\.\d+)+""").find(text)?.value

    private fun isNewer(remote: String, installed: String): Boolean {
        val r = remote.split('.').map { it.toIntOrNull() ?: 0 }
        val i = installed.split('-', '+').first().split('.').map { it.toIntOrNull() ?: 0 }
        for (n in 0 until maxOf(r.size, i.size)) {
            val a = r.getOrElse(n) { 0 }
            val b = i.getOrElse(n) { 0 }
            if (a != b) return a > b
        }
        return false
    }
}
