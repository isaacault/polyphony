package app.polyphony.core.domain.playback

/**
 * When a play counts (a scrobble): after [threshold] of the track (Settings, Playback, "Count as
 * played after") or four minutes, whichever comes first, never under ten seconds; four minutes when
 * the length isn't known. Only time actually spent playing counts. The Mac app's ScrobbleRule
 * (PolyphonyKit) is the same rule. Everything that counts a play uses this one: the server's play
 * count, the local history, and the scrobbles sent to ListenBrainz and Last.fm.
 */
object ScrobbleRule {
    const val FOUR_MINUTES_MS = 240_000L
    const val MIN_PLAY_MS = 10_000L

    /** How long a track of [durationMs] has to play before it counts. */
    fun neededMs(durationMs: Long, threshold: Float): Long {
        val needed = if (durationMs > 0) minOf((durationMs * threshold).toLong(), FOUR_MINUTES_MS) else FOUR_MINUTES_MS
        return needed.coerceAtLeast(MIN_PLAY_MS)
    }

    fun counts(playedMs: Long, durationMs: Long, threshold: Float = 0.5f): Boolean =
        playedMs >= neededMs(durationMs, threshold)
}
