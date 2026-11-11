package com.music.spotui.sync

import android.content.Context
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow

/** Device preferences deliberately stay outside the synchronized stores. */
object SyncOptions {
    enum class Category(val title:String,val description:String) {
        LIBRARY("Local library","Local playlists, track references, favorites and saved shortcuts."),
        METADATA("Metadata and audio sources","Edited titles, artists, artwork and alternative streams."),
        PODCASTS("Podcasts","Followed shows, episode progress and played status."),
        HISTORY("Listening history","Merge listening activity from your devices."),
        SETTINGS("App preferences","Sound, language, sorting and display preferences."),
        SEARCH("Recent searches","Items opened from search."),
        PLAYER("Playback and devices","Queue, playback position and remote controls."),
    }
    private const val STORE="google_sync_categories"
    private val mutable=MutableStateFlow<Map<Category,Boolean>>(emptyMap())
    val values=mutable.asStateFlow()
    @Volatile private var installed=false
    @Synchronized fun install(context:Context) {
        if(installed)return
        val p=context.applicationContext.getSharedPreferences(STORE,0)
        mutable.value=Category.entries.associateWith {p.getBoolean(it.name,true)}
        installed=true
    }
    fun enabled(category:Category)=mutable.value[category]!=false
    fun category(key:String):Category?=when(key.substringBefore(':')) {
        "LocalPlaylists","LocalLibrary","OwnedLikedSongs","LikedSongs","playlist_youtube_additions_v1","spotui_pinned_library_v1","favorite_external_playlists_v1"->Category.LIBRARY
        "CustomMetadata","AlternativeStreams"->Category.METADATA
        "SubscribedPodcasts","spotui_podcast_resume_v1","spotui_podcast_mode_prefs"->Category.PODCASTS
        "history"->Category.HISTORY
        "settings_prefs","app_sort_preferences","translation_prefs","tablet_player_layout"->Category.SETTINGS
        "RecentItems"->Category.SEARCH
        "player"->Category.PLAYER
        else->null
    }
    internal fun allows(key:String)=category(key)?.let(::enabled)==true
    fun set(context:Context,category:Category,enabled:Boolean) {
        install(context)
        mutable.value=mutable.value+(category to enabled)
        context.applicationContext.getSharedPreferences(STORE,0).edit().putBoolean(category.name,enabled).apply()
        GoogleDeviceSync.optionsChanged()
        CloudPlayback.optionsChanged()
    }
}
