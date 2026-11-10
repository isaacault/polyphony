package app.polyphony.core.playback.audio.eq

import app.polyphony.core.domain.playback.EqualizerBands
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.log10
import kotlin.math.max
import kotlin.math.pow
import kotlin.math.sin

/** One second-order filter section, normalised so a0 = 1 (direct form coefficients). */
data class Biquad(val b0: Double, val b1: Double, val b2: Double, val a1: Double, val a2: Double) {

    /** This section's gain at [frequencyHz], in dB: |H(e^jω)| from the coefficients themselves. */
    fun magnitudeDb(frequencyHz: Double, sampleRate: Int): Double {
        val w = 2 * PI * frequencyHz / sampleRate
        val c1 = cos(w)
        val s1 = sin(w)
        val c2 = cos(2 * w)
        val s2 = sin(2 * w)
        val nr = b0 + b1 * c1 + b2 * c2
        val ni = -(b1 * s1 + b2 * s2)
        val dr = 1 + a1 * c1 + a2 * c2
        val di = -(a1 * s1 + a2 * s2)
        return 10 * log10((nr * nr + ni * ni) / (dr * dr + di * di))
    }

    companion object {
        /** A peaking (bell) filter: [gainDb] at [frequencyHz], fading out either side (RBJ Audio EQ Cookbook). */
        fun peaking(frequencyHz: Double, gainDb: Double, q: Double, sampleRate: Int): Biquad {
            val a = 10.0.pow(gainDb / 40)
            val w0 = 2 * PI * frequencyHz / sampleRate
            val alpha = sin(w0) / (2 * q)
            val cosW0 = cos(w0)
            val a0 = 1 + alpha / a
            return Biquad(
                b0 = (1 + alpha * a) / a0,
                b1 = -2 * cosW0 / a0,
                b2 = (1 - alpha * a) / a0,
                a1 = -2 * cosW0 / a0,
                a2 = (1 - alpha / a) / a0,
            )
        }
    }
}

/**
 * The built-in equaliser's filters for one curve at one sample rate: a bell per band, and the
 * preamp that keeps the loudest point of the curve at 0 dB, so a boost never pushes a full-scale
 * song into clipping (EqualizerDsp's limiter is only the safety net for what's left).
 *
 * Each band's bell is a little wider than its octave, so neighbours add up: set alone they'd give
 * more than asked between two raised bands. The bells' gains are therefore corrected until the
 * response at every band's centre is the curve's ([CORRECTION_PASSES]), which is what the sliders
 * promise. Bands at or above the stream's [MAX_FRACTION_OF_RATE] (16 kHz in a 32 kHz stream) can't be
 * made and are left out.
 */
class EqualizerDesign private constructor(
    val sampleRate: Int,
    /** The bands this stream can carry, and the bell for each. */
    val frequenciesHz: List<Double>,
    val filters: List<Biquad>,
    val preampDb: Double,
) {
    /** The filters' response at [frequencyHz] in dB, without the preamp. */
    fun filtersDb(frequencyHz: Double): Double = filters.sumOf { it.magnitudeDb(frequencyHz, sampleRate) }

    /** What the listener gets at [frequencyHz], in dB: filters and preamp. Never above 0. */
    fun responseDb(frequencyHz: Double): Double = filtersDb(frequencyHz) + preampDb

    val isFlat: Boolean get() = filters.isEmpty() && preampDb == 0.0

    companion object {
        /** About one octave between the half-gain points: smooth, overlapping bells, as a graphic equaliser has. */
        const val Q = 1.41
        const val MAX_FRACTION_OF_RATE = 0.45
        const val CORRECTION_PASSES = 12
        /** How far a corrected bell may go to make up for its neighbours. */
        const val MAX_FILTER_GAIN_DB = 18.0
        /** Points per octave where the response is checked for its peak. */
        private const val GRID_PER_OCTAVE = 24

        fun of(gainsDb: List<Float>, sampleRate: Int): EqualizerDesign {
            require(sampleRate > 0) { "sample rate $sampleRate" }
            val curve = EqualizerBands.sanitize(gainsDb)
            val bands = EqualizerBands.FREQUENCIES_HZ.indices
                .filter { EqualizerBands.FREQUENCIES_HZ[it] < sampleRate * MAX_FRACTION_OF_RATE }
            val freqs = bands.map { EqualizerBands.FREQUENCIES_HZ[it].toDouble() }
            val targets = bands.map { curve[it].toDouble() }
            if (targets.all { it == 0.0 }) return EqualizerDesign(sampleRate, emptyList(), emptyList(), 0.0)

            val gains = targets.toDoubleArray()
            var filters = build(freqs, gains, sampleRate)
            repeat(CORRECTION_PASSES) {
                val response = freqs.map { f -> filters.sumOf { it.magnitudeDb(f, sampleRate) } }
                for (i in gains.indices) {
                    gains[i] = (gains[i] + targets[i] - response[i]).coerceIn(-MAX_FILTER_GAIN_DB, MAX_FILTER_GAIN_DB)
                }
                filters = build(freqs, gains, sampleRate)
            }
            val peak = grid(sampleRate).maxOf { f -> filters.sumOf { it.magnitudeDb(f, sampleRate) } }
            return EqualizerDesign(sampleRate, freqs, filters, preampDb = -max(0.0, peak))
        }

        private fun build(freqs: List<Double>, gains: DoubleArray, sampleRate: Int): List<Biquad> =
            freqs.mapIndexed { i, f -> Biquad.peaking(f, gains[i], Q, sampleRate) }

        /** Log-spaced frequencies across what's heard and what the stream holds. */
        fun grid(sampleRate: Int): List<Double> {
            val top = minOf(20_000.0, sampleRate * 0.49)
            val octaves = kotlin.math.ln(top / 20.0) / kotlin.math.ln(2.0)
            val n = (octaves * GRID_PER_OCTAVE).toInt().coerceAtLeast(1)
            return List(n + 1) { i -> 20.0 * 2.0.pow(octaves * i / n) }
        }
    }
}
