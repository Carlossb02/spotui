package com.music.spotui.data.preferences

import android.content.Context
import com.music.spotui.data.entity.PodcastModel
import org.json.JSONArray
import org.json.JSONObject

object SubscribedPodcastPref {
    private const val PREF_NAME = "SubscribedPodcasts"
    private const val KEY_PODCASTS = "subscribed_podcasts_json"

    fun getSubscribedPodcasts(context: Context): List<PodcastModel> {
        val prefs = context.getSharedPreferences(PREF_NAME, Context.MODE_PRIVATE)
        val jsonStr = prefs.getString(KEY_PODCASTS, null) ?: return emptyList()
        return runCatching {
            val arr = JSONArray(jsonStr)
            val list = mutableListOf<PodcastModel>()
            for (i in 0 until arr.length()) {
                val obj = arr.getJSONObject(i)
                list.add(
                    PodcastModel(
                        id = obj.optString("id", ""),
                        name = obj.optString("name", ""),
                        publisher = obj.optString("publisher", ""),
                        coverUri = obj.optString("coverUri", "")
                    )
                )
            }
            list.distinctBy { it.id }
        }.getOrDefault(emptyList())
    }

    fun isPodcastSubscribed(context: Context, podcastId: String): Boolean {
        if (podcastId.isBlank()) return false
        val cleanId = podcastId.trim()
        return getSubscribedPodcasts(context).any { it.id.trim() == cleanId }
    }

    fun addSubscribedPodcast(context: Context, podcast: PodcastModel) {
        if (podcast.id.isBlank()) return
        val current = getSubscribedPodcasts(context).toMutableList()
        current.removeAll { it.id.trim() == podcast.id.trim() }
        current.add(0, podcast)
        saveList(context, current)
    }

    fun removeSubscribedPodcast(context: Context, podcastId: String) {
        if (podcastId.isBlank()) return
        val current = getSubscribedPodcasts(context).toMutableList()
        current.removeAll { it.id.trim() == podcastId.trim() }
        saveList(context, current)
    }

    fun toggleSubscribedPodcast(context: Context, podcast: PodcastModel): Boolean {
        return if (isPodcastSubscribed(context, podcast.id)) {
            removeSubscribedPodcast(context, podcast.id)
            false
        } else {
            addSubscribedPodcast(context, podcast)
            true
        }
    }

    fun saveSubscribedPodcasts(context: Context, podcasts: List<PodcastModel>) {
        saveList(context, podcasts)
    }

    private fun saveList(context: Context, podcasts: List<PodcastModel>) {
        val arr = JSONArray()
        podcasts.distinctBy { it.id.trim() }.forEach { podcast ->
            val obj = JSONObject().apply {
                put("id", podcast.id)
                put("name", podcast.name)
                put("publisher", podcast.publisher)
                put("coverUri", podcast.coverUri)
            }
            arr.put(obj)
        }
        context.getSharedPreferences(PREF_NAME, Context.MODE_PRIVATE)
            .edit()
            .putString(KEY_PODCASTS, arr.toString())
            .apply()
    }
}
