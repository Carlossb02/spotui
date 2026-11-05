package com.music.spotui.audio

internal object PlaybackLoadingPolicy {
    fun canPreload(resolving: Boolean, hasMedia: Boolean, ready: Boolean): Boolean =
        !resolving && (!hasMedia || ready)

    fun exactCandidate(
        stage: Int, artistExact: Boolean, titleExact: Boolean, recordingExact: Boolean,
        albumExact: Boolean, albumVersionExact: Boolean, explicitExact: Boolean,
        durationDifference: Int?, official: Boolean, userUpload: Boolean,
    ): Boolean = stage == 1 && artistExact && titleExact && recordingExact && albumExact &&
        albumVersionExact && explicitExact && durationDifference != null && durationDifference in 0..2 &&
        official && !userUpload
}
