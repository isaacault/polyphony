package app.polyphony.core.domain.playback

import app.polyphony.core.domain.model.RecentContext
import app.polyphony.core.domain.model.Track

/** Where to start a rebuilt queue so it picks up where a [RecentContext] left off. */
data class ResumePoint(val index: Int, val positionMs: Long) {
    companion object {
        /** Within this much of a track's end we move on to the next one instead of replaying the tail. */
        const val NEAR_END_MS = 3_000L

        val START = ResumePoint(0, 0L)

        /**
         * Finds the remembered track by id (its index may have shifted since). Falls back to the
         * remembered index when the track is gone, and to the start once the context was finished.
         */
        fun of(tracks: List<Track>, entry: RecentContext?): ResumePoint {
            if (tracks.isEmpty() || entry == null) return START
            val trackId = entry.trackId ?: return START
            val index = tracks.indexOfFirst { it.id == trackId }
            if (index < 0) return ResumePoint(entry.trackIndex.coerceIn(0, tracks.lastIndex), 0L)
            val track = tracks[index]
            val position = entry.positionMs.coerceAtLeast(0L).let { if (track.durationMs > 0) it.coerceAtMost(track.durationMs) else it }
            val nearEnd = track.durationMs > 0 && track.durationMs - position <= NEAR_END_MS
            return when {
                !nearEnd -> ResumePoint(index, position)
                index < tracks.lastIndex -> ResumePoint(index + 1, 0L)
                else -> START
            }
        }
    }
}
