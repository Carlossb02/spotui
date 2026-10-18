package com.music.spotui.audio

import androidx.media3.common.C
import androidx.media3.common.audio.AudioProcessor
import androidx.media3.common.audio.BaseAudioProcessor
import androidx.media3.common.util.UnstableApi
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.min
import kotlin.math.pow
import kotlin.math.roundToInt

/** Objetivo de Spotify en modo «Normal». */
private const val TARGET_LUFS = -14.0

/** No amplificar si se superaría -1 dBTP (incluidos los picos entre muestras). */
private const val POSITIVE_GAIN_CEILING_DBTP = -1.0

/**
 * Normalización de SONORIDAD por pista, sin alterar su dinámica.
 *
 * Principios:
 * 1. Se calcula UNA ganancia a partir de metadatos de toda la pista, ANTES de su primer frame.
 * 2. Se mantiene EXACTAMENTE esa ganancia durante TODA la pista (también al hacer seek).
 * 3. No hay compresor, limitador, lookahead, rampas ni medición durante la reproducción.
 * 4. Para amplificar se exige el True Peak de la pista completa; de otro modo NO se amplifica.
 * 5. A 0 dB se copian los bytes intactos; a otra ganancia solo se multiplica cada muestra.
 *
 * IMPORTANTE: los metadatos deben corresponder al AUDIO FINAL que se va a decodificar.
 * Un True Peak erróneo no permite garantizar que una amplificación esté libre de clipping.
 * En PCM de 16 bits cualquier cambio de ganancia conlleva redondeo a ese formato.
 * Para minimizarlo se admite también PCM FLOAT de 32 bits, si la cadena lo permite.
 *
 * Uso recomendado (antes de enviar el primer frame de cada pista):
 *   processor.setTrackLoudnessLufs(-18.0, -7.0)  // LUFS integrado y True Peak en dBTP
 *   processor.beginTrack()                       // frontera EXPLÍCITA entre pistas
 *
 * Durante la canción, no llamar a beginTrack(). Se permite actualizar metadatos para
 * la SIGUIENTE canción: los nuevos valores no cambiarán la ganancia ya fijada.
 * onFlush(), incluso tras un seek, no modifica la ganancia de la pista activa.
 *
 * Sin metadatos completos no es posible reproducir el método de Spotify con precisión
 * desde la primera muestra. Es necesario precalcularlos o recuperarlos de una caché.
 */
@UnstableApi
class VolumeNormalizationAudioProcessor : BaseAudioProcessor() {

    /** Se lee y se fija al iniciar cada pista; cambiarlo a mitad de canción actualiza la ganancia. */
    @Volatile
    var enabled: Boolean = true
        set(value) {
            field = value
            updateGainFromCurrentSettings()
        }

    /** Modo de normalización (Desactivado, Suave o Equilibrado Beta). */
    @Volatile
    var mode: com.music.spotui.data.preferences.NormalizationMode =
        com.music.spotui.data.preferences.NormalizationMode.SUAVE
        set(value) {
            field = value
            updateGainFromCurrentSettings()
        }

    @Synchronized
    private fun updateGainFromCurrentSettings() {
        if (!enabled || mode == com.music.spotui.data.preferences.NormalizationMode.OFF) {
            appliedGainDb = 0.0
            linearGain = 1.0
        } else {
            val currentMeta = (explicitTrackStart?.metadata) ?: preparedTrack
            lockGainForTrack(currentMeta)
        }
    }

    /** Solo informativo: ganancia realmente fijada para la pista que está sonando. */
    @Volatile
    var appliedGainDb: Double = 0.0
        private set

    /** Número de muestras que han necesitado saturación: indica metadatos de pico incorrectos. */
    @Volatile
    var clippedSampleCount: Long = 0L
        private set

    private data class TrackMetadata(
        val gainDb: Double?,
        val truePeakDbtp: Double?
    )

    private data class TrackStart(val id: Long, val metadata: TrackMetadata)

    // Es una referencia inmutable: ambos metadatos se publican juntos entre hilos.
    @Volatile private var preparedTrack = TrackMetadata(null, null)
    @Volatile private var explicitTrackStart: TrackStart? = null
    private var nextTrackId = 0L

    // Estado del hilo de audio; nunca se recalcula en función de las muestras recibidas.
    private var appliedTrackId = 0L
    private var trackLocked = false
    private var linearGain = 1.0
    private var encoding = C.ENCODING_INVALID
    private var channelCount = 0

    /**
     * Sonoridad RELATIVA al objetivo: positivo = más fuerte que la referencia.
     * Sin True Peak se permiten las bajadas de volumen, pero no las subidas.
     */
    fun setTrackLoudness(loudnessDb: Double?) {
        setTrackLoudness(loudnessDb, null)
    }

    fun setTrackLoudness(loudnessDb: Double?, truePeakDbtp: Double?) {
        setTrackGainDb(
            loudnessDb?.takeIf { it.isFinite() }?.let { -it },
            truePeakDbtp
        )
    }

    /** Sonoridad integrada real de toda la pista, en LUFS. */
    fun setTrackLoudnessLufs(lufs: Double?) {
        setTrackLoudnessLufs(lufs, null)
    }

    /**
     * [truePeakDbtp] es el True Peak MÁXIMO de la pista completa, en dBTP.
     * Ejemplo: lufs = -20, truePeakDbtp = -5 -> máximo +4 dB, resultado -16 LUFS.
     */
    fun setTrackLoudnessLufs(lufs: Double?, truePeakDbtp: Double?) {
        setTrackGainDb(
            lufs?.takeIf { it.isFinite() }?.let { TARGET_LUFS - it },
            truePeakDbtp
        )
    }

    /**
     * Ganancia solicitada, en decibelios; NO se aplica hasta la próxima frontera de pista.
     * Sin True Peak conocido, una solicitud positiva se convierte por seguridad en 0 dB.
     */
    fun setTrackGainDb(gainDb: Double?) {
        setTrackGainDb(gainDb, null)
    }

    fun setTrackGainDb(gainDb: Double?, truePeakDbtp: Double?) {
        preparedTrack = TrackMetadata(
            gainDb?.takeIf { it.isFinite() },
            truePeakDbtp?.takeIf { it.isFinite() }
        )
    }

    /** Pista sin metadatos: se deja a 0 dB en vez de corregirla mientras está sonando. */
    fun clearTrackMetadata() {
        preparedTrack = TrackMetadata(null, null)
    }

    /** Compatibilidad con el nombre de la API anterior: ya NO estima durante el audio. */
    @Deprecated("La estimación en directo cambia el volumen: usa clearTrackMetadata().")
    fun resetToFallbackEstimation() {
        clearTrackMetadata()
    }

    /**
     * Marca que el SIGUIENTE buffer PCM es el comienzo de una pista nueva.
     * Debe invocarse DESPUÉS de setTrack... y justo ANTES de entregar su primer audio.
     * No usar en seek. OBLIGATORIO en cada nueva pista después de la inicial,
     * incluso si hay end-of-stream, flush o reconfiguración del decoder.
     */
    @Synchronized
    fun beginTrack() {
        explicitTrackStart = TrackStart(++nextTrackId, preparedTrack)
    }

    override fun onConfigure(
        inputAudioFormat: AudioProcessor.AudioFormat
    ): AudioProcessor.AudioFormat {
        if (inputAudioFormat.encoding != C.ENCODING_PCM_16BIT &&
            inputAudioFormat.encoding != C.ENCODING_PCM_FLOAT
        ) {
            return AudioProcessor.AudioFormat.NOT_SET
        }
        encoding = inputAudioFormat.encoding
        channelCount = inputAudioFormat.channelCount
        return inputAudioFormat
    }

    override fun queueInput(inputBuffer: ByteBuffer) {
        if (!inputBuffer.hasRemaining()) return
        if (channelCount <= 0) return

        val bytesPerSample = if (encoding == C.ENCODING_PCM_FLOAT) 4 else 2
        val bytesPerFrame = bytesPerSample * channelCount
        val toProcess = (inputBuffer.remaining() / bytesPerFrame) * bytesPerFrame
        if (toProcess == 0) return  // Mantener bytes incompletos para el siguiente envío.

        // Latch ANTES de la primera muestra, nunca durante un buffer.
        val newTrack = explicitTrackStart
        if (newTrack != null && newTrack.id != appliedTrackId) {
            appliedTrackId = newTrack.id
            lockGainForTrack(newTrack.metadata)
        } else if (!trackLocked) {
            // La primera pista arranca con los metadatos preparados (o a 0 dB).
            // Las siguientes solo cambian de ganancia con beginTrack().
            lockGainForTrack(preparedTrack)
        }

        val out = replaceOutputBuffer(toProcess)
        if (linearGain == 1.0) {
            // Transparencia ABSOLUTA a 0 dB: bytes originales, sin re-cuantización.
            val oldLimit = inputBuffer.limit()
            inputBuffer.limit(inputBuffer.position() + toProcess)
            try {
                out.put(inputBuffer)
            } finally {
                inputBuffer.limit(oldLimit)
            }
            out.flip()
            return
        }

        inputBuffer.order(ByteOrder.nativeOrder())
        out.order(ByteOrder.nativeOrder())
        val samples = toProcess / bytesPerSample
        var clippedInBuffer = 0L

        when (encoding) {
            C.ENCODING_PCM_16BIT -> {
                repeat(samples) {
                    val scaled = inputBuffer.short.toInt() * linearGain
                    if (scaled > Short.MAX_VALUE || scaled < Short.MIN_VALUE) {
                        clippedInBuffer++
                    }
                    out.putShort(softClip16(scaled))
                }
            }
            C.ENCODING_PCM_FLOAT -> {
                repeat(samples) {
                    val scaled = (inputBuffer.float.toDouble() * linearGain).toFloat()
                    if (scaled > 1f || scaled < -1f) clippedInBuffer++
                    out.putFloat(softClipFloat(scaled))
                }
            }
        }

        if (clippedInBuffer > 0) clippedSampleCount += clippedInBuffer
        out.flip()
    }

    private fun softClip16(scaled: Double): Short {
        val norm = scaled / 32768.0
        val clipped = when {
            norm > 0.9 -> 0.9 + 0.1 * kotlin.math.tanh((norm - 0.9) / 0.1)
            norm < -0.9 -> -0.9 + 0.1 * kotlin.math.tanh((norm + 0.9) / 0.1)
            else -> norm
        }
        return (clipped * 32767.0).roundToInt().coerceIn(-32768, 32767).toShort()
    }

    private fun softClipFloat(scaled: Float): Float {
        return when {
            scaled > 0.9f -> (0.9 + 0.1 * kotlin.math.tanh(((scaled - 0.9f) / 0.1f).toDouble())).toFloat()
            scaled < -0.9f -> (-0.9 + 0.1 * kotlin.math.tanh(((scaled + 0.9f) / 0.1f).toDouble())).toFloat()
            else -> scaled
        }
    }

    /**
     * Un EOS puede deberse a un cambio interno de flujo, no necesariamente de canción.
     * Por seguridad NO se desbloquea la ganancia: solo beginTrack() cambia de pista.
     */
    override fun onQueueEndOfStream() {
        // No hay audio retenido ni latencia que vaciar.
    }

    /** También se ejecuta en un seek: NO resetear la ganancia ni el estado de pista. */
    @Suppress("DEPRECATION")
    override fun onFlush() {
        super.onFlush()
    }

    override fun onReset() {
        super.onReset()
        encoding = C.ENCODING_INVALID
        channelCount = 0
        // NO alterar la ganancia fijada: el renderer puede resetearse en mitad de una pista.
        // Para un tema distinto debe llamarse explícitamente a beginTrack().
    }

    private fun lockGainForTrack(metadata: TrackMetadata) {
        val desiredGainDb = metadata.gainDb ?: 0.0
        val safeGainDb = when {
            !enabled || mode == com.music.spotui.data.preferences.NormalizationMode.OFF -> 0.0
            desiredGainDb <= 0.0 -> desiredGainDb
            mode == com.music.spotui.data.preferences.NormalizationMode.BALANCED_BETA -> desiredGainDb
            metadata.truePeakDbtp == null -> 0.0
            else -> {
                // Solo subir el volumen si TODO el audio cabe bajo -1 dBTP.
                val availableHeadroom =
                    (POSITIVE_GAIN_CEILING_DBTP - metadata.truePeakDbtp).coerceAtLeast(0.0)
                min(desiredGainDb, availableHeadroom)
            }
        }
        appliedGainDb = if (enabled && mode != com.music.spotui.data.preferences.NormalizationMode.OFF) safeGainDb else 0.0
        linearGain = 10.0.pow(appliedGainDb / 20.0)
        clippedSampleCount = 0L
        trackLocked = true
    }
}
