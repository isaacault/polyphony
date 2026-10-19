package app.polyphony.core.domain.playback

import app.polyphony.core.domain.model.Track
import app.polyphony.core.domain.transcode.OutputTarget
import app.polyphony.core.domain.transcode.TranscodeDecision
import kotlinx.collections.immutable.ImmutableList
import kotlinx.collections.immutable.persistentListOf
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.StateFlow

enum class RepeatMode { OFF, ALL, ONE }

/** Snapshot of the player, safe to render directly from Compose. */
data class PlaybackState(
    val queue: ImmutableList<Track> = persistentListOf(),
    val currentIndex: Int = -1,
    val isPlaying: Boolean = false,
    val isBuffering: Boolean = false,
    val shuffleEnabled: Boolean = false,
    val repeatMode: RepeatMode = RepeatMode.OFF,
    val playbackSpeed: Float = 1f,
    val outputTarget: OutputTarget = OutputTarget.Local,
    /** The transcoding decision in effect for the current item, if known. */
    val transcode: TranscodeDecision? = null,
    /** Whether the adaptive latency monitor currently caps quality. */
    val latencyDegraded: Boolean = false,
    val sleepTimerEndsAtEpochMs: Long? = null,
    val queueTitle: String? = null,
    val audioSessionId: Int = 0,
    val error: String? = null,
) {
    val currentTrack: Track? get() = queue.getOrNull(currentIndex)
    val hasNext: Boolean get() = currentIndex in 0 until queue.lastIndex || repeatMode == RepeatMode.ALL
    val hasPrevious: Boolean get() = currentIndex > 0 || repeatMode == RepeatMode.ALL
    val isActive: Boolean get() = currentTrack != null
}

data class PlaybackPosition(
    val positionMs: Long = 0L,
    val bufferedMs: Long = 0L,
    val durationMs: Long = 0L,
) {
    val progress: Float get() = if (durationMs > 0) (positionMs.toFloat() / durationMs).coerceIn(0f, 1f) else 0f
    val bufferedProgress: Float get() = if (durationMs > 0) (bufferedMs.toFloat() / durationMs).coerceIn(0f, 1f) else 0f
}

/**
 * The UI-facing playback API. Implemented in `core:playback` on top of a Media3 MediaController so
 * it survives configuration changes and keeps working while the app is backgrounded.
 */
interface PlaybackController {
    val state: StateFlow<PlaybackState>
    /** Emits roughly every 250 ms while playing, and on seeks. */
    val position: Flow<PlaybackPosition>

    fun play()
    fun pause()
    fun togglePlayPause()
    fun stop()
    fun seekTo(positionMs: Long)
    fun next()
    fun previous()
    fun skipToQueueItem(index: Int)

    fun playTracks(tracks: List<Track>, startIndex: Int = 0, shuffle: Boolean = false, queueTitle: String? = null)
    fun playNext(tracks: List<Track>)
    fun addToQueue(tracks: List<Track>)
    fun removeFromQueue(index: Int)
    fun moveQueueItem(from: Int, to: Int)
    fun clearQueue()

    fun setShuffle(enabled: Boolean)
    fun setRepeatMode(mode: RepeatMode)
    fun setPlaybackSpeed(speed: Float)
    fun setSleepTimer(endsAtEpochMs: Long?)

    /** Clears the latency-degraded state so the normal profile applies again. */
    fun restoreQuality()
    /** Re-run the transcoding decision now (after the user edits rules) and reload if needed. */
    fun refreshTranscodeDecision()
}

data class CastState(
    val isAvailable: Boolean = false,
    val isConnecting: Boolean = false,
    val isConnected: Boolean = false,
    val target: OutputTarget? = null,
    val volume: Float = 1f,
) {
    val isActiveSession: Boolean get() = isConnected || isConnecting
}

/** Observes and controls remote output sessions (Google Cast now; DLNA later). */
interface CastController {
    val state: StateFlow<CastState>
    fun disconnect()
    fun setVolume(volume: Float)
}
