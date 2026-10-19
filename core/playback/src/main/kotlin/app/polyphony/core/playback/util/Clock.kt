package app.polyphony.core.playback.util

/** Injectable wall clock so time-window logic is unit-testable. */
fun interface Clock {
    fun nowMs(): Long

    companion object {
        val SYSTEM: Clock = Clock { System.currentTimeMillis() }
    }
}
