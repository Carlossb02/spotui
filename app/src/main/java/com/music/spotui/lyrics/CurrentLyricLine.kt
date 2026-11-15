package com.music.spotui.lyrics

import com.music.spotui.data.entity.Lyrics

/** An instrumental gap or a future first timestamp must not display the previous lyric. */
internal fun currentLyricLine(lyrics: Lyrics, positionMs: Long): String? =
    if (!lyrics.synced) null else lyrics.lines.lastOrNull { it.timeMs <= positionMs }
        ?.text?.takeIf { it.isNotBlank() }
