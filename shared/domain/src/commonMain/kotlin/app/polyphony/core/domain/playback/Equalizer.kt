package app.polyphony.core.domain.playback

import kotlinx.serialization.Serializable

/**
 * Which equaliser shapes this phone's playback. One at a time, never two stacked without the user
 * knowing: the built-in one, or the phone's own audio effects and equaliser apps (which hear of the
 * player's audio session only in [SYSTEM]), or none.
 */
@Serializable
enum class EqualizerMode { OFF, BUILT_IN, SYSTEM }

/**
 * The bands of the built-in equaliser, one octave apart (the ISO graphic-equaliser centres), and the
 * gain each may take. Presets and the custom curve are a gain per band, in this order.
 */
object EqualizerBands {
    val FREQUENCIES_HZ: List<Float> = listOf(31f, 62f, 125f, 250f, 500f, 1_000f, 2_000f, 4_000f, 8_000f, 16_000f)
    val COUNT: Int = FREQUENCIES_HZ.size
    const val MIN_GAIN_DB = -12f
    const val MAX_GAIN_DB = 12f
    /** Gains are kept to half-decibel steps: finer than anyone hears, coarse enough to land on 0. */
    const val STEP_DB = 0.5f
    val FLAT: List<Float> = List(COUNT) { 0f }

    /** One band's gain within range, on the half-decibel grid; anything unreadable is 0. */
    fun clamp(gainDb: Float): Float {
        if (gainDb.isNaN()) return 0f
        val stepped = kotlin.math.round(gainDb / STEP_DB) * STEP_DB
        return stepped.coerceIn(MIN_GAIN_DB, MAX_GAIN_DB)
    }

    /**
     * A curve with exactly [COUNT] gains, each [clamp]ed: a settings file from another build may have
     * more bands, fewer, or values out of range, and none of that reaches the audio.
     */
    fun sanitize(gainsDb: List<Float>): List<Float> = List(COUNT) { i -> clamp(gainsDb.getOrNull(i) ?: 0f) }

    /** "31", "1k", "16k": a band's label under its slider. */
    fun label(frequencyHz: Float): String =
        if (frequencyHz >= 1_000f) {
            val k = frequencyHz / 1_000f
            if (k == kotlin.math.floor(k)) "${k.toInt()}k" else "${kotlin.math.round(k * 10) / 10}k"
        } else {
            frequencyHz.toInt().toString()
        }
}

/**
 * Ready-made curves, a gain per [EqualizerBands] band. [CUSTOM] is the user's own
 * ([EqualizerSettings.customGainsDb]); it has no curve of its own here.
 */
@Serializable
enum class EqualizerPreset(val gainsDb: List<Float>?) {
    FLAT(EqualizerBands.FLAT),
    BASS_BOOST(listOf(6f, 5.5f, 4.5f, 2.5f, 0.5f, 0f, 0f, 0f, 0f, 0f)),
    BASS_CUT(listOf(-6f, -5f, -3.5f, -1.5f, 0f, 0f, 0f, 0f, 0f, 0f)),
    TREBLE_BOOST(listOf(0f, 0f, 0f, 0f, 0f, 0.5f, 2f, 4f, 5.5f, 6f)),
    TREBLE_CUT(listOf(0f, 0f, 0f, 0f, 0f, 0f, -1.5f, -3.5f, -5f, -6f)),
    VOCAL(listOf(-2f, -2f, -1f, 0.5f, 2.5f, 3.5f, 3.5f, 2f, 0.5f, -1f)),
    /** The ear's loss of bass and treble at low volume, made up for: for quiet listening. */
    LOUDNESS(listOf(5f, 4f, 2f, 0.5f, -0.5f, -1f, -0.5f, 1f, 3f, 4f)),
    ROCK(listOf(4f, 3f, 2f, 0.5f, -1f, -1f, 0.5f, 2f, 3f, 3.5f)),
    POP(listOf(-1f, 0f, 1.5f, 3f, 3.5f, 2f, 0.5f, -0.5f, -1f, -1f)),
    JAZZ(listOf(3f, 2f, 1f, 1.5f, -1f, -1f, 0f, 1f, 2f, 3f)),
    CLASSICAL(listOf(3f, 2.5f, 1.5f, 0.5f, 0f, 0f, 0f, 0.5f, 2f, 2.5f)),
    ELECTRONIC(listOf(4.5f, 4f, 1.5f, 0f, -1.5f, 0.5f, 0f, 1f, 3f, 4f)),
    HIP_HOP(listOf(5f, 4.5f, 2f, 2.5f, -0.5f, -0.5f, 1f, 0f, 1.5f, 2.5f)),
    ACOUSTIC(listOf(2.5f, 2.5f, 1.5f, 0.5f, 1f, 1f, 2f, 2.5f, 2f, 1f)),
    /** Softer lows and sibilance, for headphones late at night or small, harsh speakers. */
    LATE_NIGHT(listOf(-3f, -2f, -0.5f, 0.5f, 1.5f, 1.5f, 0.5f, -1f, -2.5f, -3.5f)),
    CUSTOM(null),
}

/** The equaliser part of [app.polyphony.core.domain.repository.PlaybackSettings]. */
@Serializable
data class EqualizerSettings(
    /** [EqualizerMode.SYSTEM] by default: before the built-in one, the session was always announced to the phone's effects. */
    val mode: EqualizerMode = EqualizerMode.SYSTEM,
    val preset: EqualizerPreset = EqualizerPreset.FLAT,
    /** The user's own curve, kept when another preset is chosen and back with [EqualizerPreset.CUSTOM]. */
    val customGainsDb: List<Float> = EqualizerBands.FLAT,
) {
    /** The curve the built-in equaliser plays: the preset's, or the custom one, always [EqualizerBands.COUNT] safe gains. */
    val gainsDb: List<Float>
        get() = EqualizerBands.sanitize(preset.gainsDb ?: customGainsDb)

    /** What the built-in equaliser should play now, or null to leave the sound untouched. */
    val builtInCurve: List<Float>?
        get() = if (mode == EqualizerMode.BUILT_IN) gainsDb.takeIf { gains -> gains.any { it != 0f } } else null

    /** The curve with [band] set to [gainDb]: whatever preset was playing becomes the starting point of the custom one. */
    fun withBand(band: Int, gainDb: Float): EqualizerSettings {
        if (band !in 0 until EqualizerBands.COUNT) return this
        val gains = gainsDb.toMutableList().also { it[band] = EqualizerBands.clamp(gainDb) }
        return copy(preset = EqualizerPreset.CUSTOM, customGainsDb = gains)
    }
}
