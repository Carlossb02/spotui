package com.music.spotui.di

/** Edition evidence ranks only recordings already accepted by the identity matcher. */
internal object ContentEditionPolicy {
    const val BADGE_EXPLICIT = 0
    const val LABEL_EXPLICIT = 1
    const val UNKNOWN = 2
    const val REJECT_CLEAN = 3

    private val clean = Regex(
        """(?i)(?:\b(?:clean[\s_-]+(?:version|edit|audio|lyrics|edition)|censored|censurad[oa]|radio[\s_-]+clean)\b|(?:^|[\[(\-–—:])\s*clean\s*(?:[\])]|$))"""
    )
    private val uncut = Regex("""(?i)\b(?:explicit|uncensored|uncut|dirty\s+(?:version|edit)|sin\s+censura|not\s+censored)\b""")
    private val negative = Regex("""(?i)\b(?:not\s+censored|sin\s+censura|no\s+censurad[oa])\b""")

    private fun labelledClean(value:String, reference:String):Boolean {
        // A real title such as "Clean" is not an edition marker added to another song.
        if(value.trim().equals(reference.trim(),ignoreCase=true))return false
        return clean.containsMatchIn(negative.replace(value,""))
    }

    fun rank(wantExplicit:Boolean?,badge:Boolean,title:String,album:String?,targetTitle:String,targetAlbum:String?):Int {
        if(wantExplicit!=true)return BADGE_EXPLICIT
        if(labelledClean(title,targetTitle) || labelledClean(album.orEmpty(),targetAlbum.orEmpty()))return REJECT_CLEAN
        if(badge)return BADGE_EXPLICIT
        if(uncut.containsMatchIn(title) || uncut.containsMatchIn(album.orEmpty()))return LABEL_EXPLICIT
        // Missing E metadata on a video does not prove that its audio is censored.
        return UNKNOWN
    }

    fun <T> preferred(candidates:List<T>,rank:(T)->Int):List<T> {
        val allowed=candidates.filter {rank(it)!=REJECT_CLEAN}
        val best=allowed.minOfOrNull(rank) ?: return emptyList()
        return allowed.filter {rank(it)==best}
    }
}
