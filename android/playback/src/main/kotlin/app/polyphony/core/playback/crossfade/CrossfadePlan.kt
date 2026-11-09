package app.polyphony.core.playback.crossfade

import app.polyphony.core.domain.model.Track
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.sin

/**
 * When, and over how long, one song fades into the next. Pure, so it's tested; [Crossfader] does it.
 *
 * - A fade is at most the setting, and at most a third of the song, so a short song still mostly
 *   plays at full volume; under a second isn't worth it.
 * - With "keep albums gapless", an album played in order runs gaplessly: no fade between one track
 *   and the next track of the same album.
 * - Equal-power curves: the two songs' loudness together stays level through the fade, where a
 *   linear one dips in the middle.
 */
object CrossfadePlan {
    /** The fade, in ms, for a song of [durationMs] going into [next]; 0 for none. */
    fun fadeMs(settingMs: Int, keepAlbumsGapless: Boolean, current: Track?, next: Track?, durationMs: Long): Long {
        if (settingMs <= 0 || durationMs <= 0 || next == null) return 0
        if (keepAlbumsGapless && consecutiveOnAlbum(current, next)) return 0
        val fade = minOf(settingMs.toLong(), durationMs / 3)
        return if (fade < MIN_FADE_MS) 0 else fade
    }

    /** [next] is the track after [current] on the same album (by disc and track number). */
    fun consecutiveOnAlbum(current: Track?, next: Track?): Boolean {
        if (current == null || next == null) return false
        val album = current.albumId ?: return false
        if (next.albumId != album) return false
        val track = current.trackNumber
        val nextTrack = next.trackNumber
        // Numbers unknown: the same album, played in its order, keeps its gaps closed.
        if (track == null || nextTrack == null) return true
        val disc = current.discNumber ?: 1
        val nextDisc = next.discNumber ?: 1
        return (nextDisc == disc && nextTrack == track + 1) || (nextDisc == disc + 1 && nextTrack == 1)
    }

    /** The incoming song's level at [t] (0..1) through the fade. */
    fun fadeIn(t: Float): Float = sin(t.coerceIn(0f, 1f) * PI / 2).toFloat()

    /** The outgoing song's level at [t] (0..1) through the fade. */
    fun fadeOut(t: Float): Float = cos(t.coerceIn(0f, 1f) * PI / 2).toFloat()

    /** Where the fade starts, and how much earlier the outgoing song's second player gets ready. */
    fun fadeStartMs(durationMs: Long, fadeMs: Long): Long = durationMs - fadeMs
    fun prepareAtMs(durationMs: Long, fadeMs: Long): Long = (durationMs - fadeMs - PREPARE_LEAD_MS).coerceAtLeast(0)

    const val MIN_FADE_MS = 1_000L
    /** Long enough to open the song from the cache and be ready at its place. */
    const val PREPARE_LEAD_MS = 4_000L
}
