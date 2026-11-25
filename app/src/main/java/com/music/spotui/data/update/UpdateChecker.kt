package com.music.spotui.data.update

import android.content.Context
import com.music.spotui.BuildConfig
import com.music.spotui.data.preferences.DEFAULT_UPDATE_REPO_URL
import com.music.spotui.data.preferences.LEGACY_UPDATE_REPO_URL
import com.music.spotui.data.preferences.getUpdateRepoUrl
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONObject
import java.util.concurrent.TimeUnit

object UpdateChecker {

    private const val PREFS = "update_prefs"
    private const val KEY_SKIP = "skip_fingerprint"

    data class UpdateInfo(
        val version: String,
        val downloadUrl: String?,
        val sha256: String?,
        val fingerprint: String,
        val releaseBody: String,
    )

    private val client = OkHttpClient.Builder()
        .connectTimeout(8, TimeUnit.SECONDS)
        .readTimeout(8, TimeUnit.SECONDS)
        .build()

    suspend fun check(context: Context): UpdateInfo? = withContext(Dispatchers.IO) {
        val configured = getUpdateRepoUrl(context).trim().trimEnd('/')
        val sources = if (configured.equals(DEFAULT_UPDATE_REPO_URL, ignoreCase = true)) {
            listOf(configured, LEGACY_UPDATE_REPO_URL)
        } else listOf(configured)
        var withoutApk: UpdateInfo? = null
        val info = sources.firstNotNullOfOrNull { source ->
            val release = runCatching { fetchLatestRelease(source) }.getOrNull()
                ?.takeIf { isNewer(it.version, BuildConfig.VERSION_NAME) }
            if (release?.downloadUrl != null) release else {
                if (withoutApk == null) withoutApk = release
                null
            }
        } ?: withoutApk ?: return@withContext null
        val skipped = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .getString(KEY_SKIP, null)
        if (skipped == info.fingerprint) return@withContext null
        info
    }

    fun skipRelease(context: Context, info: UpdateInfo) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .edit().putString(KEY_SKIP, info.fingerprint).apply()
    }

    private fun fetchLatestRelease(repoUrl: String): UpdateInfo? {
        val repoPath = repoUrl.removePrefix("https://github.com/")
            .removePrefix("http://github.com/")
            .removeSuffix("/releases")
            .removeSuffix("/releases/latest")
            .trimEnd('/')
        val apiLatest = "https://api.github.com/repos/$repoPath/releases/latest"
        val apiReleases = "https://api.github.com/repos/$repoPath/releases"

        val request = Request.Builder()
            .url(apiLatest)
            .header("Accept", "application/vnd.github+json")
            .header("User-Agent", "Spotidroid-App")
            .build()
        var json = client.newCall(request).execute().use { response ->
            if (response.isSuccessful) response.body?.string()?.let { runCatching { JSONObject(it) }.getOrNull() }
            else null
        }

        // Fallback: if /releases/latest failed or returned invalid json, fetch /releases list and pick the first release
        if (json == null || json.optString("tag_name").trim().isBlank()) {
            val listRequest = Request.Builder()
                .url(apiReleases)
                .header("Accept", "application/vnd.github+json")
                .header("User-Agent", "Spotidroid-App")
                .build()
            json = client.newCall(listRequest).execute().use { response ->
                if (response.isSuccessful) response.body?.string()?.let { body ->
                    runCatching { org.json.JSONArray(body).optJSONObject(0) }.getOrNull()
                } else null
            }
        }

        val j = json ?: return null
        val tag = j.optString("tag_name").trim()
        if (tag.isBlank()) return null
        val version = extractVersion(tag)
            ?: return null
        val releaseBody = j.optString("body", "")
        val assets = j.optJSONArray("assets")
        val apks = (0 until (assets?.length() ?: 0))
            .asSequence()
            .mapNotNull { assets?.optJSONObject(it) }
            .filter { it.optString("name").endsWith(".apk", ignoreCase = true) }
            .filter { it.optString("browser_download_url").isNotBlank() }
            .toList()
        val apk = apks.firstOrNull { it.optString("name").startsWith("Spotidroid", ignoreCase = true) }
            ?: apks.firstOrNull { it.optString("name").startsWith("Spotui", ignoreCase = true) }
            ?: apks.firstOrNull()
        return UpdateInfo(
            version = version,
            downloadUrl = apk?.getString("browser_download_url"),
            sha256 = apk?.optString("digest")?.removePrefix("sha256:")
                ?.takeIf { it.matches(Regex("[a-fA-F0-9]{64}")) },
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
