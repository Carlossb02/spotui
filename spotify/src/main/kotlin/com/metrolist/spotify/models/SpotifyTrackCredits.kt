package com.metrolist.spotify.models

import kotlinx.serialization.json.*

data class SpotifyCreditPerson(val name: String, val artistId: String = "")
data class SpotifyCreditRole(val title: String, val people: List<SpotifyCreditPerson>)
data class SpotifyTrackCredits(val roles: List<SpotifyCreditRole>, val sources: List<String>)

internal fun parseTrackCredits(root: JsonObject): SpotifyTrackCredits {
    fun JsonObject.text(key: String) = (get(key) as? JsonPrimitive)?.contentOrNull.orEmpty()
    val roles = (root["roleCredits"] as? JsonArray).orEmpty().mapNotNull { value ->
        val role = value as? JsonObject ?: return@mapNotNull null
        val title = role.text("roleTitle")
        val people = (role["artists"] as? JsonArray).orEmpty().mapNotNull person@ { item ->
            val person = item as? JsonObject ?: return@person null
            val name = person.text("name").takeIf { it.isNotBlank() } ?: return@person null
            val uri = person.text("uri").ifBlank { person.text("creatorUri") }
            val id = Regex("^spotify:artist:([A-Za-z0-9]{22})$").find(uri)?.groupValues?.get(1).orEmpty()
            SpotifyCreditPerson(name, id)
        }.distinct()
        if (title.isBlank() || people.isEmpty()) null else SpotifyCreditRole(title, people)
    }
    val sources = (root["sourceNames"] as? JsonArray).orEmpty().mapNotNull { (it as? JsonPrimitive)?.contentOrNull?.takeIf(String::isNotBlank) }.distinct()
    return SpotifyTrackCredits(roles, sources)
}
