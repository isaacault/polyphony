package app.polyphony.core.playback.reporting

import app.polyphony.core.domain.model.PlayNowIds
import app.polyphony.core.domain.model.Track
import app.polyphony.core.domain.playback.ScrobbleRule
import app.polyphony.core.domain.repository.LibraryRepository
import app.polyphony.core.domain.repository.Scrobbler
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

/**
 * Scrobbling: "now playing" when a track starts, "played" once per play when [ScrobbleRule] says
 * it counts, counting only time actually spent playing. Both go to [scrobbler], which tells the
 * server when [enabled] (Navidrome for a library song, continuo for a Play now song, continuo#12)
 * and this phone's own ListenBrainz and Last.fm accounts for whatever the server didn't scrobble
 * itself; a counted play waits on the phone, with its time, while there's no network.
 *
 * A library song's play also goes into this phone's history at once; a Play now song isn't in the
 * library.
 */
class PlaybackReporter(
    private val scope: CoroutineScope,
    private val libraryRepository: LibraryRepository,
    private val scrobbler: Scrobbler = Scrobbler.NONE,
) {
    @Volatile var enabled: Boolean = true
    @Volatile var threshold: Float = 0.5f

    private var current: Track? = null
    private var playedMs: Long = 0L
    private var reported = false
    private var startedAt: Long = 0L

    fun onTrackStarted(track: Track?) {
        current = track
        playedMs = 0L
        reported = false
        startedAt = System.currentTimeMillis()
        val reportable = current ?: return
        val toServer = enabled
        scope.launch(Dispatchers.IO) { runCatching { scrobbler.nowPlaying(reportable, toServer) } }
    }

    /** Called about once a second while playing, with the elapsed interval. */
    fun onPlayingTick(deltaMs: Long) {
        val track = current ?: return
        playedMs += deltaMs
        if (reported) return
        if (ScrobbleRule.counts(playedMs, track.durationMs, threshold)) {
            reported = true
            val playedAt = startedAt
            val toServer = enabled
            scope.launch(Dispatchers.IO) {
                if (PlayNowIds.streamId(track.id) == null) runCatching { libraryRepository.recordPlay(track, playedAt) }
                runCatching { scrobbler.played(track, playedAt, toServer) }
            }
        }
    }
}
