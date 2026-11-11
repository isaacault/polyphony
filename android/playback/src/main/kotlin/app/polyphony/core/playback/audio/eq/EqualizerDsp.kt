package app.polyphony.core.playback.audio.eq

import java.nio.ByteBuffer
import kotlin.math.abs
import kotlin.math.exp
import kotlin.math.pow
import kotlin.math.roundToInt

/**
 * Runs an [EqualizerDesign] over interleaved PCM: the preamp, the bells (transposed direct form II,
 * in double precision), then a peak limiter just under full scale, all channels limited together so
 * the stereo image doesn't move.
 *
 * The filters' memory is kept from one buffer to the next and across a change of curve, so a gapless
 * change of song or a slider moved mid-song doesn't click. A new curve's preamp glides in over a few
 * milliseconds rather than jumping.
 */
class EqualizerDsp(design: EqualizerDesign, val channelCount: Int) {
    val sampleRate: Int = design.sampleRate
    var design: EqualizerDesign = design
        private set

    private var coefficients = DoubleArray(0)
    private var sections = 0
    /** z1, z2 for each channel and section. */
    private var state = DoubleArray(0)
    private var preampTarget = 1.0
    private var preamp = 1.0
    private var limiterGain = 1.0
    /** How much of the equalised sound is heard against the plain one (1: all), gliding to [wetTarget]. */
    private var wet = 1.0
    private var wetTarget = 1.0
    private val preampGlide = 1 - exp(-1.0 / (PREAMP_GLIDE_S * sampleRate))
    private val limiterRelease = 1 - exp(-1.0 / (LIMITER_RELEASE_S * sampleRate))
    private val frame = DoubleArray(channelCount)

    init {
        require(channelCount > 0) { "channels $channelCount" }
        update(design)
        preamp = preampTarget
    }

    /** Plays [next] from now on, keeping the filters' memory where the bands are the same ones. */
    fun update(next: EqualizerDesign) {
        require(next.sampleRate == sampleRate) { "a new sample rate needs a new EqualizerDsp" }
        design = next
        val n = next.filters.size
        coefficients = DoubleArray(n * 5).also { c ->
            next.filters.forEachIndexed { i, f ->
                c[i * 5] = f.b0; c[i * 5 + 1] = f.b1; c[i * 5 + 2] = f.b2; c[i * 5 + 3] = f.a1; c[i * 5 + 4] = f.a2
            }
        }
        if (n != sections) state = DoubleArray(channelCount * n * 2)
        sections = n
        preampTarget = 10.0.pow(next.preampDb / 20)
    }

    /**
     * Crossfades to the plain sound ([on] false: the equaliser being turned off or set flat) or
     * back to the equalised one, over a few milliseconds, so neither the preamp nor the bells
     * vanish in one step.
     */
    fun fadeTo(on: Boolean) {
        if (on && isFadedOut) state.fill(0.0)   // its memory is from before it went quiet
        wetTarget = if (on) 1.0 else 0.0
    }

    /** Starts out unheard, to be faded in ([fadeTo]). */
    fun startFadedOut() {
        wet = 0.0
        wetTarget = 0.0
    }

    /** Faded out completely: the plain sound can pass without it. */
    val isFadedOut: Boolean get() = wetTarget == 0.0 && wet == 0.0

    /** 16-bit PCM from [input] (all of it) to [output]. */
    fun processPcm16(input: ByteBuffer, output: ByteBuffer) {
        while (input.remaining() >= channelCount * 2) {
            for (ch in 0 until channelCount) frame[ch] = input.getShort() / PCM16_SCALE
            processFrame()
            for (ch in 0 until channelCount) {
                output.putShort((frame[ch] * PCM16_SCALE).roundToInt().coerceIn(Short.MIN_VALUE.toInt(), Short.MAX_VALUE.toInt()).toShort())
            }
        }
        flushDenormals()
    }

    /** 32-bit float PCM from [input] (all of it) to [output]. */
    fun processFloat(input: ByteBuffer, output: ByteBuffer) {
        while (input.remaining() >= channelCount * 4) {
            for (ch in 0 until channelCount) frame[ch] = input.getFloat().toDouble()
            processFrame()
            for (ch in 0 until channelCount) output.putFloat(frame[ch].toFloat())
        }
        flushDenormals()
    }

    /** One sample per channel in [frame], equalised and limited in place. */
    private fun processFrame() {
        preamp += (preampTarget - preamp) * preampGlide
        if (wet != wetTarget) {
            wet += (wetTarget - wet) * preampGlide
            if (abs(wetTarget - wet) < WET_SETTLED) wet = wetTarget
        }
        var peak = 0.0
        val c = coefficients
        val z = state
        for (ch in 0 until channelCount) {
            val x = frame[ch]
            var y = x * preamp
            var zi = ch * sections * 2
            for (s in 0 until sections) {
                val k = s * 5
                val out = c[k] * y + z[zi]
                z[zi] = c[k + 1] * y - c[k + 3] * out + z[zi + 1]
                z[zi + 1] = c[k + 2] * y - c[k + 4] * out
                y = out
                zi += 2
            }
            if (wet != 1.0) y = x + (y - x) * wet
            frame[ch] = y
            val a = abs(y)
            if (a > peak) peak = a
        }
        // Instant attack, gentle release: with the preamp this is rarely touched.
        limiterGain += (1 - limiterGain) * limiterRelease
        if (peak * limiterGain > LIMIT) limiterGain = LIMIT / peak
        for (ch in 0 until channelCount) frame[ch] = (frame[ch] * limiterGain).coerceIn(-1.0, 1.0)
    }

    /** Silence decays the filters' memory towards numbers so small that some CPUs slow down on them. */
    private fun flushDenormals() {
        for (i in state.indices) if (abs(state[i]) < 1e-25) state[i] = 0.0
    }

    companion object {
        private const val PCM16_SCALE = 32768.0
        /** Just under full scale (-0.3 dBFS). */
        val LIMIT: Double = 10.0.pow(-0.3 / 20)
        private const val LIMITER_RELEASE_S = 0.1
        private const val PREAMP_GLIDE_S = 0.01
        /** Close enough to the crossfade's end to stop it: well under a 16-bit step. */
        private const val WET_SETTLED = 1e-5
    }
}
