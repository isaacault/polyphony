package app.polyphony.core.playback.audio

/**
 * One player's volume, from the things that each want a say in it: ReplayGain's level for the song,
 * the sleep timer's fade and a crossfade's ramp. Each sets its own factor and the player gets their
 * product, so one never undoes another (the sleep timer restoring "full" volume used to wipe out
 * ReplayGain's level).
 */
class OutputVolume(private val apply: (Float) -> Unit) {
    var gain: Float = 1f
        set(value) { field = value.coerceIn(0f, 1f); push() }

    var sleep: Float = 1f
        set(value) { field = value.coerceIn(0f, 1f); push() }

    var fade: Float = 1f
        set(value) { field = value.coerceIn(0f, 1f); push() }

    val level: Float get() = gain * sleep * fade

    fun push() = apply(level)
}
