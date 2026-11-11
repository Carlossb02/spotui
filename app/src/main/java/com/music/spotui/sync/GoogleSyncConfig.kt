package com.music.spotui.sync

import android.content.Context
import com.music.spotui.BuildConfig
import com.google.firebase.FirebaseApp
import com.google.firebase.FirebaseOptions
import org.json.JSONObject
import java.io.FileNotFoundException

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
        return synchronized(firebaseLock) { FirebaseApp.getApps(context).firstOrNull { it.name == name }
            ?: FirebaseApp.initializeApp(
                context,
                FirebaseOptions.Builder().setApiKey(apiKey).setApplicationId(appId)
                    .setProjectId(projectId).setDatabaseUrl(databaseUrl).build(),
                name,
            )
        }
    }

    internal class ConfigurationException(val code:String, detail:String): IllegalStateException("$detail [$code]")

    companion object {
        private val firebaseLock = Any()
        internal fun failureMessage(error:Throwable):String = when(error) {
            is ConfigurationException -> error.message.orEmpty()
            is com.google.firebase.auth.FirebaseAuthException -> "No se pudo iniciar Google. Código: ${error.errorCode}"
            else -> "No se pudo iniciar la conexión con Google. Vuelve a intentarlo. [GOOGLE_INIT_${error.javaClass.simpleName}]"
        }
        private const val ASSET = "spotui/google-services.json"

        fun load(context: Context): GoogleSyncConfig {
            val raw=try {context.assets.open(ASSET).bufferedReader(Charsets.UTF_8).use {it.readText()}}
            catch(_:FileNotFoundException) {throw ConfigurationException("GOOGLE_CONFIG_MISSING","Falta la configuración de Google en el APK. Recompila incluyendo app/src/main/assets/spotui/google-services.json.")}
            catch(_:java.io.IOException) {throw ConfigurationException("GOOGLE_CONFIG_READ","No se pudo leer la configuración de Google incluida en el APK.")}
            return try {fromJson(raw,BuildConfig.APPLICATION_ID)}
            catch(e:ConfigurationException){throw e}
            catch(_:Exception){throw ConfigurationException("GOOGLE_CONFIG_INVALID","La configuración de Google incluida en el APK está incompleta o no es válida.")}
        }

        /** Kept separate from Android I/O to validate every shipped package before packaging. */
        internal fun fromJson(raw: String, packageName: String): GoogleSyncConfig {
            val root = JSONObject(raw)
            require(!root.has("private_key") && !root.has("private_key_id")) { "Expected public Firebase application configuration" }
            val project = root.getJSONObject("project_info")
            val clients = root.getJSONArray("client")
            val client = (0 until clients.length()).map { clients.getJSONObject(it) }.firstOrNull {
                it.getJSONObject("client_info").optJSONObject("android_client_info")?.optString("package_name") == packageName
            } ?: throw ConfigurationException("GOOGLE_PACKAGE_MISSING","La configuración de Google no incluye la variante instalada ($packageName).")
            val oauth = client.optJSONArray("oauth_client") ?: org.json.JSONArray()
            val webClientId = (0 until oauth.length()).map { oauth.getJSONObject(it) }
                .firstOrNull { it.optInt("client_type") == 3 }?.getString("client_id")
                ?: throw ConfigurationException("GOOGLE_WEB_CLIENT_MISSING","Falta el identificador web de Google en la configuración de esta app.")
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
