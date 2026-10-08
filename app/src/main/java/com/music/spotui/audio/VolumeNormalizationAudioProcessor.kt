package com.music.spotui.audio

import androidx.media3.common.C
import androidx.media3.common.audio.AudioProcessor
import androidx.media3.common.audio.BaseAudioProcessor
import androidx.media3.common.util.UnstableApi
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.ceil
import kotlin.math.exp
import kotlin.math.log10
import kotlin.math.pow
import kotlin.math.roundToInt
import kotlin.math.tan

// ---------------------------------------------------------------------------------------------
// Parámetros (equivalentes a la configuración "Normal" de Spotify: -14 LUFS)
// ---------------------------------------------------------------------------------------------
private const val TARGET_LUFS = -14.0

/** Límites de ganancia por pista (dB). */
private const val MAX_BOOST_DB = 9.0
private const val MAX_CUT_DB = -20.0

/** Techo del limitador: -1 dBFS (margen para picos entre muestras al convertir a analógico). */
private const val CEILING = 0.891f
private const val LOOKAHEAD_MS = 5.0
private const val RELEASE_MS = 120.0

/** Velocidades máximas de cambio de ganancia (dB por segundo). */
private const val RATE_METADATA = 40.0       // cambio de pista: rampa de ~0,15 s para 6 dB
private const val RATE_FALLBACK_FIRST = 3.0  // primera estimación: entra suave en ~2 s
private const val RATE_FALLBACK_REFINE = 0.25 // refinamientos posteriores: imperceptibles
private const val DEADBAND_DB = 0.3          // ignora correcciones menores a esto

/** Bloques de 400 ms (solape 75 %) necesarios antes de fiarse de la medición (~2,5 s). */
private const val MIN_BLOCKS_FOR_ESTIMATE = 20

/**
 * [AudioProcessor] de Media3 que normaliza la sonoridad de las pistas como lo hace Spotify:
 *
 *  - **Ganancia constante por pista** (un único valor en dB, sin compresión ni dinámica), por lo que
 *    la canción suena exactamente igual, solo más fuerte o más suave.
 *  - Con metadatos (ReplayGain / loudness): la ganancia se conoce desde el primer segundo.
 *  - Sin metadatos: se mide la sonoridad integrada real (ITU-R BS.1770 / EBU R128: filtro K +
 *    gating) y la ganancia se mueve solo con rampas muy lentas, nunca a saltos.
 *  - Limitador con anticipación (lookahead) transparente: solo actúa si, tras subir el volumen, un
 *    pico superaría -1 dBFS. Si no hace falta, el audio sale bit a bit idéntico (ganancia 0 dB).
 *  - Dither TPDF al re-cuantizar a 16 bits cuando se modifica la ganancia (sin distorsión de truncado).
 *
 * Uso: llama a [setTrackLoudness] / [setTrackGainDb] / [setTrackLoudnessLufs] al cambiar de pista, o
 * a [resetToFallbackEstimation] si no tienes metadatos de esa pista.
 */
@UnstableApi
class VolumeNormalizationAudioProcessor : BaseAudioProcessor() {

    @Volatile
    var enabled: Boolean = false

    // --- Control (hilo principal -> hilo de audio) ---
    @Volatile private var controlVersion = 0
    @Volatile private var metadataDriven = false
    @Volatile private var requestedGainDb = 0.0
    private var appliedVersion = 0

    // --- Formato ---
    private var sampleRate = 0
    private var channelCount = 0
    private var frameBuf = FloatArray(0)
    private var outBuf = FloatArray(0)

    // --- Estado de ganancia (solo hilo de audio) ---
    private var currentDb = 0.0
    private var fallbackTargetDb = 0.0
    private var fallbackEstablished = false
    private var fallbackSettled = false
    private var hasProcessedAudio = false
    private var wasActive = false

    private val meter = LoudnessMeter()
    private val limiter = LookaheadLimiter()
    private var rng = 22222

    // ------------------------------------------------------------------------------------------
    // API pública
    // ------------------------------------------------------------------------------------------

    /** [loudnessDb]: sonoridad de la pista relativa a la referencia (positivo = más fuerte). */
    fun setTrackLoudness(loudnessDb: Double?) {
        setTrackGainDb(if (loudnessDb != null && loudnessDb.isFinite()) -loudnessDb else null)
    }

    /** [lufs]: sonoridad integrada absoluta de la pista (p. ej. -8.3). Ganancia = -14 - lufs. */
    fun setTrackLoudnessLufs(lufs: Double?) {
        setTrackGainDb(if (lufs != null && lufs.isFinite()) TARGET_LUFS - lufs else null)
    }

    /** Ganancia directa en dB a aplicar a la pista. */
    fun setTrackGainDb(gainDb: Double?) {
        if (gainDb == null || !gainDb.isFinite()) {
            resetToFallbackEstimation()
            return
        }
        requestedGainDb = gainDb.coerceIn(MAX_CUT_DB, MAX_BOOST_DB)
        metadataDriven = true
        controlVersion++
    }

    /** Sin metadatos: medir la sonoridad real de la pista mientras suena. Llamar en cada cambio de pista. */
    fun resetToFallbackEstimation() {
        metadataDriven = false
        controlVersion++
    }

    // ------------------------------------------------------------------------------------------
    // BaseAudioProcessor
    // ------------------------------------------------------------------------------------------

    override fun onConfigure(inputAudioFormat: AudioProcessor.AudioFormat): AudioProcessor.AudioFormat {
        if (inputAudioFormat.encoding != C.ENCODING_PCM_16BIT) {
            return AudioProcessor.AudioFormat.NOT_SET
        }
        sampleRate = inputAudioFormat.sampleRate
        channelCount = inputAudioFormat.channelCount
        frameBuf = FloatArray(channelCount)
        outBuf = FloatArray(channelCount)
        meter.configure(sampleRate, channelCount)
        limiter.configure(sampleRate, channelCount)
        wasActive = false
        return inputAudioFormat
    }

    override fun queueInput(inputBuffer: ByteBuffer) {
        val available = inputBuffer.remaining()
        if (available == 0) return

        // Desactivado: paso directo, sin tocar nada.
        if (!enabled || sampleRate == 0 || channelCount == 0) {
            wasActive = false
            val out = replaceOutputBuffer(available)
            out.put(inputBuffer)
            out.flip()
            return
        }
        if (!wasActive) {
            wasActive = true
            limiter.reset()
            resetEstimation()
        }

        val frameSize = channelCount * 2
        val frames = available / frameSize
        if (frames == 0) {
            inputBuffer.position(inputBuffer.limit())
            return
        }

        applyPendingControl()

        // --- Ganancia objetivo y rampa para este buffer (interpolada por frame, igual en todos los canales) ---
        val useMetadata = metadataDriven
        val targetDb: Double
        val rate: Double
        if (useMetadata) {
            targetDb = requestedGainDb
            rate = RATE_METADATA
        } else {
            targetDb = if (fallbackEstablished) fallbackTargetDb else currentDb
            rate = if (fallbackSettled) RATE_FALLBACK_REFINE else RATE_FALLBACK_FIRST
        }
        val maxStep = rate * frames / sampleRate
        val delta = targetDb - currentDb
        val newDb = when {
            abs(delta) <= maxStep -> targetDb
            delta > 0 -> currentDb + maxStep
            else -> currentDb - maxStep
        }
        var g = 10.0.pow(currentDb / 20.0)
        val ratio = if (newDb == currentDb) 1.0 else 10.0.pow((newDb - currentDb) / 20.0 / frames)

        inputBuffer.order(ByteOrder.nativeOrder())
        val out = replaceOutputBuffer(frames * frameSize)
        val x = frameBuf
        val y = outBuf

        for (f in 0 until frames) {
            for (c in 0 until channelCount) x[c] = inputBuffer.short / 32768f
            if (!useMetadata) meter.process(x)

            val gf = g.toFloat()
            g *= ratio
            for (c in 0 until channelCount) x[c] *= gf

            val limiterGain = limiter.process(x, y)
            val dither = gf != 1f || limiterGain != 1f
            for (c in 0 until channelCount) out.putShort(quantize(y[c], dither))
        }

        currentDb = newDb
        hasProcessedAudio = true
        if (!useMetadata) updateFallbackEstimate()
        out.flip()
    }

    /** Vacía la latencia del limitador (5 ms) al terminar el stream. */
    override fun onQueueEndOfStream() {
        if (!wasActive || !enabled || channelCount == 0 || !hasProcessedAudio) return
        val n = limiter.latencyFrames
        if (n <= 0) return
        val out = replaceOutputBuffer(n * channelCount * 2)
        val x = frameBuf
        val y = outBuf
        java.util.Arrays.fill(x, 0f)
        repeat(n) {
            val limiterGain = limiter.process(x, y)
            val dither = currentDb != 0.0 || limiterGain != 1f
            for (c in 0 until channelCount) out.putShort(quantize(y[c], dither))
        }
        out.flip()
        limiter.reset()
    }

    // Se llama también al hacer seek: se limpia el limitador, pero NO la ganancia ni la medición,
    // para que un seek no provoque cambios de volumen.
    @Suppress("DEPRECATION")
    override fun onFlush() {
        super.onFlush()
        limiter.reset()
        hasProcessedAudio = false
    }

    override fun onReset() {
        super.onReset()
        // 'enabled' es un ajuste del usuario, no estado del stream: no se toca aquí.
        limiter.reset()
        resetEstimation()
        currentDb = 0.0
        hasProcessedAudio = false
        wasActive = false
    }

    // ------------------------------------------------------------------------------------------
    // Internos
    // ------------------------------------------------------------------------------------------

    private fun applyPendingControl() {
        val v = controlVersion
        if (v == appliedVersion) return
        appliedVersion = v
        resetEstimation()
        // Pista nueva sin audio previo en la tubería: la ganancia entra directa (no hay nada que "saltar").
        // Con audio previo (gapless) entra con una rampa corta para evitar clics.
        if (!hasProcessedAudio) {
            currentDb = if (metadataDriven) requestedGainDb else 0.0
        }
    }

    private fun resetEstimation() {
        meter.reset()
        fallbackEstablished = false
        fallbackSettled = false
        fallbackTargetDb = 0.0
    }

    private fun updateFallbackEstimate() {
        if (!meter.consumeUpdated() || meter.blocks < MIN_BLOCKS_FOR_ESTIMATE) return
        val lufs = meter.integratedLufs()
        if (lufs.isNaN()) return
        val desired = (TARGET_LUFS - lufs).coerceIn(MAX_CUT_DB, MAX_BOOST_DB)
        if (!fallbackEstablished) {
            fallbackEstablished = true
            fallbackTargetDb = desired
        } else if (abs(desired - fallbackTargetDb) > DEADBAND_DB) {
            fallbackTargetDb = desired
        }
        if (!fallbackSettled && abs(currentDb - fallbackTargetDb) < 0.01) fallbackSettled = true
    }

    private fun nextRand(): Float {
        rng = rng xor (rng shl 13)
        rng = rng xor (rng ushr 17)
        rng = rng xor (rng shl 5)
        return (rng ushr 8) * (1f / 16777216f)
    }

    /** float [-1,1) -> short. Con [dither]: ruido TPDF de ±1 LSB; sin él, conversión exacta. */
    private fun quantize(sample: Float, dither: Boolean): Short {
        var v = sample * 32768f
        if (dither) v += nextRand() - nextRand()
        return v.roundToInt().coerceIn(-32768, 32767).toShort()
    }

    // ------------------------------------------------------------------------------------------
    // Limitador con anticipación
    //   1) ganancia necesaria por frame (nunca >1)
    //   2) mínimo deslizante sobre la ventana de lookahead
    //   3) release suave (el mínimo baja al instante, sube lentamente)
    //   4) media móvil -> ataque suave que llega a tiempo al pico
    // El audio se retrasa 'look' frames para que la ganancia ya esté bajada cuando llega el pico.
    // ------------------------------------------------------------------------------------------
    private class LookaheadLimiter {
        private var channels = 0
        var latencyFrames = 0
            private set

        private var delay = FloatArray(0)
        private var delayPos = 0

        private var dqVal = FloatArray(0)
        private var dqIdx = LongArray(0)
        private var dqHead = 0
        private var dqSize = 0
        private var dqCap = 0
        private var frameCounter = 0L

        private var avg = FloatArray(0)
        private var avgPos = 0
        private var avgSum = 0.0

        private var releaseGain = 1f
        private var releaseCoef = 0f

        fun configure(sampleRate: Int, channels: Int) {
            this.channels = channels
            latencyFrames = maxOf(1, (LOOKAHEAD_MS * sampleRate / 1000.0).toInt())
            delay = FloatArray(latencyFrames * channels)
            dqCap = latencyFrames + 2
            dqVal = FloatArray(dqCap)
            dqIdx = LongArray(dqCap)
            avg = FloatArray(latencyFrames)
            releaseCoef = (1.0 - exp(-1.0 / (RELEASE_MS * sampleRate / 1000.0))).toFloat()
            reset()
        }

        fun reset() {
            java.util.Arrays.fill(delay, 0f)
            java.util.Arrays.fill(avg, 1f)
            avgSum = latencyFrames.toDouble()
            avgPos = 0
            delayPos = 0
            dqHead = 0
            dqSize = 0
            frameCounter = 0L
            releaseGain = 1f
        }

        /** Entra [frame]; escribe en [out] el frame retrasado y limitado. Devuelve la ganancia aplicada. */
        fun process(frame: FloatArray, out: FloatArray): Float {
            var peak = 0f
            for (c in 0 until channels) {
                val a = abs(frame[c])
                if (a > peak) peak = a
            }
            val need = if (peak > CEILING) CEILING / peak else 1f

            // Mínimo deslizante (deque monótona) sobre los últimos latencyFrames+1 frames.
            val n = frameCounter++
            while (dqSize > 0 && dqVal[(dqHead + dqSize - 1) % dqCap] >= need) dqSize--
            val tail = (dqHead + dqSize) % dqCap
            dqVal[tail] = need
            dqIdx[tail] = n
            dqSize++
            while (dqIdx[dqHead] < n - latencyFrames) {
                dqHead = (dqHead + 1) % dqCap
                dqSize--
            }
            val m = dqVal[dqHead]

            // Release suave.
            releaseGain = if (m < releaseGain) m else releaseGain + (m - releaseGain) * releaseCoef
            if (1f - releaseGain < 1e-6f) releaseGain = 1f

            // Media móvil (ataque suave garantizado a tiempo).
            avgSum += releaseGain - avg[avgPos]
            avg[avgPos] = releaseGain
            avgPos = (avgPos + 1) % latencyFrames
            var s = (avgSum / latencyFrames).toFloat()
            if (abs(1f - s) < 1e-6f) s = 1f

            val base = delayPos * channels
            for (c in 0 until channels) {
                out[c] = delay[base + c] * s
                delay[base + c] = frame[c]
            }
            delayPos = (delayPos + 1) % latencyFrames
            return s
        }
    }

    // ------------------------------------------------------------------------------------------
    // Medidor de sonoridad integrada (ITU-R BS.1770-4 / EBU R128): filtro K + gating.
    // Bloques de 400 ms cada 100 ms, puerta absoluta -70 LUFS y relativa -10 LU.
    // ------------------------------------------------------------------------------------------
    private class LoudnessMeter {
        private companion object {
            const val BINS = 800 // pasos de 0,1 LU desde -70 LUFS
        }

        private var channels = 0
        private var subLen = 1
        private val hs = DoubleArray(5) // b0 b1 b2 a1 a2 (shelf alto)
        private val hp = DoubleArray(5) // b0 b1 b2 a1 a2 (paso alto)
        private var z = DoubleArray(0)

        private var subEnergy = 0.0
        private var subCount = 0
        private val subs = DoubleArray(4)
        private var subsPos = 0
        private var subsFilled = 0

        private val binCount = LongArray(BINS)
        private val binEnergy = DoubleArray(BINS)
        private var updated = false

        var blocks = 0
            private set

        fun configure(sampleRate: Int, channels: Int) {
            this.channels = channels
            subLen = maxOf(1, sampleRate / 10)
            z = DoubleArray(channels * 4)

            run {
                val f0 = 1681.974450955533
                val gain = 3.999843853973347
                val q = 0.7071752369554196
                val k = tan(PI * f0 / sampleRate)
                val vh = 10.0.pow(gain / 20.0)
                val vb = vh.pow(0.4996667741545416)
                val a0 = 1.0 + k / q + k * k
                hs[0] = (vh + vb * k / q + k * k) / a0
                hs[1] = 2.0 * (k * k - vh) / a0
                hs[2] = (vh - vb * k / q + k * k) / a0
                hs[3] = 2.0 * (k * k - 1.0) / a0
                hs[4] = (1.0 - k / q + k * k) / a0
            }
            run {
                val f0 = 38.13547087602444
                val q = 0.5003270373238773
                val k = tan(PI * f0 / sampleRate)
                val a0 = 1.0 + k / q + k * k
                hp[0] = 1.0
                hp[1] = -2.0
                hp[2] = 1.0
                hp[3] = 2.0 * (k * k - 1.0) / a0
                hp[4] = (1.0 - k / q + k * k) / a0
            }
            reset()
        }

        fun reset() {
            java.util.Arrays.fill(z, 0.0)
            java.util.Arrays.fill(subs, 0.0)
            java.util.Arrays.fill(binCount, 0L)
            java.util.Arrays.fill(binEnergy, 0.0)
            subEnergy = 0.0
            subCount = 0
            subsPos = 0
            subsFilled = 0
            blocks = 0
            updated = false
        }

        fun consumeUpdated(): Boolean {
            val u = updated
            updated = false
            return u
        }

        fun process(x: FloatArray) {
            var e = 0.0
            for (c in 0 until channels) {
                val o = c * 4
                val xin = x[c].toDouble()
                // Etapa 1: shelf alto (forma transpuesta II)
                var y = hs[0] * xin + z[o]
                z[o] = hs[1] * xin - hs[3] * y + z[o + 1]
                z[o + 1] = hs[2] * xin - hs[4] * y
                // Etapa 2: paso alto
                val x2 = y
                y = hp[0] * x2 + z[o + 2]
                z[o + 2] = hp[1] * x2 - hp[3] * y + z[o + 3]
                z[o + 3] = hp[2] * x2 - hp[4] * y
                e += y * y
            }
            subEnergy += e
            if (++subCount >= subLen) finishSubBlock()
        }

        private fun finishSubBlock() {
            subs[subsPos] = subEnergy / subLen
            subsPos = (subsPos + 1) % 4
            if (subsFilled < 4) subsFilled++
            subEnergy = 0.0
            subCount = 0
            if (subsFilled == 4) {
                blocks++
                addBlock((subs[0] + subs[1] + subs[2] + subs[3]) / 4.0)
                updated = true
            }
        }

        private fun addBlock(energy: Double) {
            if (energy <= 0.0) return
            val lufs = -0.691 + 10.0 * log10(energy)
            if (lufs < -70.0) return
            val idx = ((lufs + 70.0) * 10.0).toInt().coerceIn(0, BINS - 1)
            binCount[idx]++
            binEnergy[idx] += energy
        }

        /** Sonoridad integrada con doble puerta. NaN si aún no hay material por encima de -70 LUFS. */
        fun integratedLufs(): Double {
            var totalE = 0.0
            var totalN = 0L
            for (i in 0 until BINS) {
                totalE += binEnergy[i]
                totalN += binCount[i]
            }
            if (totalN == 0L) return Double.NaN

            val relGate = -0.691 + 10.0 * log10(totalE / totalN) - 10.0
            val start = ceil((relGate + 70.0) * 10.0).toInt().coerceIn(0, BINS - 1)
            var e = 0.0
            var n = 0L
            for (i in start until BINS) {
                e += binEnergy[i]
                n += binCount[i]
            }
            if (n == 0L) return Double.NaN
            return -0.691 + 10.0 * log10(e / n)
        }
    }
}