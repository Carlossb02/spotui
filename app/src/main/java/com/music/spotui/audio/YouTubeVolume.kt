package com.music.spotui.audio

import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.sin

internal object YouTubeVolume {
    const val TARGET_LUFS = -14.0

    /** YouTube's perceptual value is a loudness estimate, not a peak measurement. */
    fun correctionDb(relativeDb: Double?, perceptualDb: Double?): Double? {
        val estimate = perceptualDb?.takeIf { it.isFinite() && it in -70.0..0.0 }
        val relative = relativeDb?.takeIf { it.isFinite() && it in -30.0..30.0 }
        return (estimate?.let { TARGET_LUFS - it } ?: relative?.let { -it })?.coerceIn(-30.0, 6.0)
    }

    /** Natural mode keeps the sum <= 1 even for strongly correlated tracks. */
    fun crossfadeGains(progress: Float, natural: Boolean): Pair<Float, Float> {
        val t = if (progress.isFinite()) progress.coerceIn(0f, 1f) else 0f
        if (natural) {
            val smooth = t * t * (3f - 2f * t)
            return (1f - smooth) to smooth
        }
        return cos(t * PI / 2).toFloat() to sin(t * PI / 2).toFloat()
    }
}
