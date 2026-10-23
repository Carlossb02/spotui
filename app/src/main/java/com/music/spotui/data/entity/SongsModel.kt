package com.music.spotui.data.entity

data class SongsModel(
    val id : Int,
    val title : String,
    val album : String,
    val singer : String,
    val coverUri : String,
    val url : String,
    // Real Spotify track id (e.g. "3n3Ppam7vgaVa1iaRUc9Lp"), kept so playback can
    // seed Spotify's recommendations endpoint for autoplay radio. Empty when unknown.
    val spotifyTrackId : String = "",
    // Whether the Spotify track is the explicit version, so the YouTube fallback
    // can pick the matching (explicit vs clean) edit.
    val explicit : Boolean = false,
    // Track length in ms (from Spotify). Used to disambiguate the YouTube match:
    // a same-title-different-artist song almost always has a different duration.
    val durationMs : Int = 0,
    // Comma-separated artist IDs matching the order of names in [singer].
    // Populated from Spotify's API so tapping a specific artist in the player
    // navigates to the correct artist page.
    val artistIds : String = "",
    // Podcast episode description (if applicable)
    val description : String = "",
    // Podcast episode release/upload date string (e.g. "12 oct 2025" or "Hace 2 días")
    val releaseDate : String = "",
    // Source provenance: Spotify LOCAL library item, not an official Spotify catalogue track.
    // Never infer this flag from artist/title or edited metadata.
    val isLocalSpotifyTrack: Boolean = false,
){
    constructor() : this(-1 ,"" ,"" ,"" ,"" ,"", "", description = "", releaseDate = "")
}
