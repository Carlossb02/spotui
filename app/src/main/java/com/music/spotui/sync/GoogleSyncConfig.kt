package com.music.spotui.sync

import android.content.Context
import com.music.spotui.BuildConfig
import com.google.firebase.FirebaseApp
import com.google.firebase.FirebaseOptions
import org.json.JSONObject

/** Public application configuration bundled once by the developer for both Android variants. */
internal data class GoogleSyncConfig(
    val apiKey: String,
    val appId: String,
    val projectId: String,
    val databaseUrl: String,
    val webClientId: String,
) {
    fun firebase(context: Context): FirebaseApp {
        // Keep the same name as earlier versions so existing linked accounts remain signed in.
        val name = "spotui-sync-" + SyncTree.digest(appId + databaseUrl).take(12)
        return FirebaseApp.getApps(context).firstOrNull { it.name == name }
            ?: FirebaseApp.initializeApp(
                context,
                FirebaseOptions.Builder().setApiKey(apiKey).setApplicationId(appId)
                    .setProjectId(projectId).setDatabaseUrl(databaseUrl).build(),
                name,
            )
    }

    companion object {
        private const val ASSET = "spotui/google-services.json"

        fun load(context: Context): GoogleSyncConfig = context.assets.open(ASSET)
            .bufferedReader(Charsets.UTF_8).use { fromJson(it.readText(), BuildConfig.APPLICATION_ID) }

        /** Kept separate from Android I/O to validate every shipped package before packaging. */
        internal fun fromJson(raw: String, packageName: String): GoogleSyncConfig {
            val root = JSONObject(raw)
            require(!root.has("private_key") && !root.has("private_key_id")) { "Expected public Firebase application configuration" }
            val project = root.getJSONObject("project_info")
            val clients = root.getJSONArray("client")
            val client = (0 until clients.length()).map { clients.getJSONObject(it) }.firstOrNull {
                it.getJSONObject("client_info").optJSONObject("android_client_info")?.optString("package_name") == packageName
            } ?: error("Firebase configuration is missing Android package $packageName")
            val oauth = client.optJSONArray("oauth_client") ?: org.json.JSONArray()
            val webClientId = (0 until oauth.length()).map { oauth.getJSONObject(it) }
                .firstOrNull { it.optInt("client_type") == 3 }?.getString("client_id")
                ?: error("Firebase configuration is missing the web OAuth client")
            val databaseUrl = project.getString("firebase_url").trim().trimEnd('/')
            val uri = java.net.URI(databaseUrl)
            require(uri.scheme == "https" && uri.userInfo == null && uri.port == -1 &&
                uri.query == null && uri.fragment == null && uri.path.orEmpty().isEmpty() &&
                (uri.host?.endsWith(".firebaseio.com") == true || uri.host?.endsWith(".firebasedatabase.app") == true)) {
                "Invalid Firebase Realtime Database endpoint"
            }
            return GoogleSyncConfig(
                apiKey = client.getJSONArray("api_key").getJSONObject(0).getString("current_key"),
                appId = client.getJSONObject("client_info").getString("mobilesdk_app_id"),
                projectId = project.getString("project_id"),
                databaseUrl = databaseUrl,
                webClientId = webClientId,
            ).also { config ->
                require(listOf(config.apiKey, config.appId, config.projectId, config.webClientId).all { it.isNotBlank() }) {
                    "Incomplete Firebase application configuration"
                }
            }
        }
    }
}
