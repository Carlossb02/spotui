package com.music.spotui.lyrics

import java.text.Normalizer
import java.util.Locale
import kotlin.math.abs

/** Match the recording before preferring a synchronised transcription. */
internal object LyricsMatcher {
    data class Track(val title: String, val artist: String, val album: String, val durationSec: Int)
    data class Candidate(val title: String, val artist: String, val album: String, val durationSec: Int, val synced: Boolean)

    private val noise = Regex("""\s*(?:[(\[][^)\]]*\b(?:remaster(?:ed)?|deluxe|bonus|mono|stereo|feat\.?|ft\.?)\b[^)\]]*[)\]]| - (?:\d{4} )?(?:remaster(?:ed)?|deluxe|bonus|mono|stereo)\b.*)""", RegexOption.IGNORE_CASE)
    private val versionTag = Regex("""(?:\(|\[| - )([^\)\]]+)""")
    private val versions = Regex("""\b(live|remix|acoustic|instrumental|karaoke|cover|demo|radio|edit|sped|slowed|clean|explicit|rerecorded|re-recorded)\b""", RegexOption.IGNORE_CASE)
    private val credits = Regex(""",|\s+(?:feat\.?|ft\.?|with|&)\s+""", RegexOption.IGNORE_CASE)

    fun normalize(value: String): String = Normalizer.normalize(value, Normalizer.Form.NFD)
        .replace(Regex("\\p{M}+"), "").lowercase(Locale.ROOT)
        .replace(Regex("[^\\p{L}\\p{N}]+"), " ").trim().replace(Regex("\\s+"), " ")

    fun searchTitle(value: String): String = value.replace(noise, "").trim()

    private fun recordingVersions(title: String): Set<String> = versionTag.findAll(title)
        .flatMap { versions.findAll(it.groupValues[1]) }.map { it.value.lowercase(Locale.ROOT) }.toSet()

    private fun baseTitle(title: String): String {
        val withoutTags = searchTitle(title).replace(Regex("""\s*[(\[]([^)\]]*)[)\]]""")) {
            if (versions.containsMatchIn(it.groupValues[1])) "" else it.value
        }
        val suffix = withoutTags.substringAfterLast(" - ", "")
        return normalize(if (versions.containsMatchIn(suffix)) withoutTags.substringBeforeLast(" - ") else withoutTags)
    }

    private fun similarity(a: String, b: String): Double {
        if (a.isBlank() || b.isBlank()) return 0.0
        if (a == b) return 1.0
        val left = a.split(' ').toSet(); val right = b.split(' ').toSet()
        return left.intersect(right).size.toDouble() / left.union(right).size
    }

    private fun artistSimilarity(ours: String, theirs: String): Double {
        val full = similarity(normalize(ours), normalize(theirs))
        val oursCredits = ours.split(credits).filter { it.isNotBlank() }
        val theirCredits = theirs.split(credits).filter { it.isNotBlank() }
        // A shared guest alone must not turn an unrelated lead artist into a match.
        val lead = oursCredits.firstOrNull() ?: ours
        return maxOf(full, theirCredits.maxOfOrNull { similarity(normalize(lead), normalize(it)) } ?: 0.0)
    }

    private val plainCompatibleVersions = setOf("live", "acoustic", "radio", "edit", "demo", "sped", "slowed")

    /** Different timings/cuts can still supply the words, never fabricated timestamps. */
    fun plainScore(track: Track, candidate: Candidate): Double? {
        val ours = recordingVersions(track.title); val theirs = recordingVersions(candidate.title)
        val different = (ours - theirs) + (theirs - ours)
        if (different.any { it !in plainCompatibleVersions }) return null
        val title = similarity(baseTitle(track.title), baseTitle(candidate.title))
        val artist = artistSimilarity(track.artist, candidate.artist)
        if (title < 0.85 || artist < 0.8) return null
        return title * 300 + artist * 200 + similarity(normalize(track.album), normalize(candidate.album)) * 20
    }

    fun plainQueryTitle(title: String): String = if (recordingVersions(title).all { it in plainCompatibleVersions })
        baseTitle(title) else searchTitle(title)

    /** null means wrong/insufficient identity, regardless of synced availability. */
    fun score(track: Track, candidate: Candidate): Double? {
        if (recordingVersions(track.title) != recordingVersions(candidate.title)) return null
        val title = similarity(baseTitle(track.title), baseTitle(candidate.title))
        val artist = artistSimilarity(track.artist, candidate.artist)
        if (title < 0.85 || artist < 0.8) return null
        val durationKnown = track.durationSec > 0 && candidate.durationSec > 0
        val difference = if (durationKnown) abs(track.durationSec - candidate.durationSec) else 0
        val tolerance = if (candidate.synced) maxOf(3.0, track.durationSec * 0.015).coerceAtMost(6.0)
            else maxOf(8.0, track.durationSec * 0.04).coerceAtMost(15.0)
        if (durationKnown && difference > tolerance) return null
        if (!durationKnown && (title < 1.0 || artist < 1.0)) return null
        val album = similarity(normalize(track.album), normalize(candidate.album))
        return title * 300 + artist * 200 + album * 20 - difference * 3 + if (candidate.synced) 4 else 0
    }
}
