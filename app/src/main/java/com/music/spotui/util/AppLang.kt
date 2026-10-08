package com.music.spotui.util

import android.content.Context
import com.music.spotui.data.preferences.getAppLanguage

object AppLang {
    private val translations = mapOf(
        "es" to mapOf(
            "queue" to "Cola de reproducción",
            "now_playing" to "Reproduciendo ahora",
            "listening_history" to "Historial de escucha",
            "clear_all" to "Borrar todo",
            "downloads" to "Descargas",
            "lyrics" to "Letra",
            "clear" to "Borrar",
            "cancel" to "Cancelar",
            "create_playlist" to "Crear lista local",
            "playlist_name" to "Nombre de la lista",
            "local_files" to "Archivos locales",
            "no_lyrics" to "No se encontró la letra de esta canción",
            "show_lyrics" to "Mostrar letra",
            "translate" to "Traducir",
            "stream_resolution" to "Información de resolución",
            "copy_logs" to "Copiar registros",
            "close" to "Cerrar",
            "search_query" to "Buscar término",
            "rename_playlist" to "Renombrar lista",
            "delete_playlist" to "Eliminar lista",
            "log_in" to "Iniciar sesión",
            "retry" to "Reintentar",
            "skip_for_now" to "Omitir por ahora",
            "open_in_browser" to "Abrir en el navegador",
            "verified_artist" to "Artista verificado"
        ),
        "en" to mapOf(
            "queue" to "Queue",
            "now_playing" to "Now playing",
            "listening_history" to "Listening history",
            "clear_all" to "Clear all",
            "downloads" to "Downloads",
            "lyrics" to "Lyrics",
            "clear" to "Clear",
            "cancel" to "Cancel",
            "create_playlist" to "Create Local Playlist",
            "playlist_name" to "Playlist name",
            "local_files" to "Local files",
            "no_lyrics" to "No lyrics found for this track",
            "show_lyrics" to "Show lyrics",
            "translate" to "Translate",
            "stream_resolution" to "Stream Resolution Info",
            "copy_logs" to "Copy Logs",
            "close" to "Close",
            "search_query" to "Search query",
            "rename_playlist" to "Rename Playlist",
            "delete_playlist" to "Delete Playlist",
            "log_in" to "Log In",
            "retry" to "Retry",
            "skip_for_now" to "Skip for now",
            "open_in_browser" to "Open Spotify in Browser",
            "verified_artist" to "Verified Artist"
        )
    )

    fun get(context: Context, key: String, default: String = key): String {
        val resId = context.resources.getIdentifier(key, "string", context.packageName)
        if (resId != 0) {
            runCatching { return context.getString(resId) }
        }
        val lang = getAppLanguage(context)
        return translations[lang]?.get(key) ?: translations["en"]?.get(key) ?: default
    }
}
