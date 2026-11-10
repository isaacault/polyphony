package app.polyphony.core.playback.reporting

import app.polyphony.core.domain.model.PlayNowIds
import app.polyphony.core.domain.model.Track
import app.polyphony.core.domain.playback.ScrobbleRule
import app.polyphony.core.domain.repository.LibraryRepository
import app.polyphony.core.playback.engine.ServerLookup
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

/**
 * Scrobbling: "now playing" when a track starts, "played" once per play when [ScrobbleRule] says
 * it counts, counting only time actually spent playing. A Play now
 * song (continuo#12) isn't a Navidrome song, so it goes to [playNow] instead (continuo scrobbles
 * it to the account's linked ListenBrainz and Last.fm): stream id, and when it was listened to
 * (null: it's playing now).
 */
class PlaybackReporter(
    private val scope: CoroutineScope,
    private val serverLookup: ServerLookup,
    private val libraryRepository: LibraryRepository,
    private val playNow: suspend (serverId: String, streamId: String, listenedAtMs: Long?) -> Unit = { _, _, _ -> },
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
        if (!enabled) return
        val stream = PlayNowIds.streamId(reportable.id)
        scope.launch(Dispatchers.IO) {
            if (stream != null) runCatching { playNow(reportable.serverId, stream, null) }
            else runCatching { serverLookup.awaitProvider(reportable.serverId)?.reportNowPlaying(reportable.id) }
        }
    }

    /** Called about once a second while playing, with the elapsed interval. */
    fun onPlayingTick(deltaMs: Long) {
        val track = current ?: return
        playedMs += deltaMs
        if (reported) return
        if (ScrobbleRule.counts(playedMs, track.durationMs, threshold)) {
            reported = true
            val playedAt = startedAt
            val stream = PlayNowIds.streamId(track.id)
            scope.launch(Dispatchers.IO) {
                if (stream != null) {
                    if (enabled) runCatching { playNow(track.serverId, stream, playedAt) }
                    return@launch
                }
                runCatching { libraryRepository.recordPlay(track, playedAt) }
                if (enabled) runCatching { serverLookup.awaitProvider(track.serverId)?.reportPlayed(track.id, playedAt) }
            }
        }
    }
}
