package app.polyphony.core.playback.audio

import app.polyphony.core.domain.model.Track
import app.polyphony.core.domain.repository.ReplayGainMode
import kotlin.math.min
import kotlin.math.pow

/** Pure ReplayGain math; unit-tested. ExoPlayer's volume tops out at 1.0, so positive gain is clamped. */
object ReplayGain {
    fun volumeFor(
        track: Track?,
        mode: ReplayGainMode,
        preampDb: Float,
        preventClipping: Boolean,
        sameAlbumAsNeighbour: Boolean,
    ): Float {
        if (track == null || mode == ReplayGainMode.OFF) return 1f
        val (gainDb, peak) = when (mode) {
            ReplayGainMode.TRACK -> (track.replayGainTrackDb ?: track.replayGainAlbumDb) to (track.replayGainTrackPeak ?: track.replayGainAlbumPeak)
            ReplayGainMode.ALBUM -> (track.replayGainAlbumDb ?: track.replayGainTrackDb) to (track.replayGainAlbumPeak ?: track.replayGainTrackPeak)
            ReplayGainMode.AUTO -> if (sameAlbumAsNeighbour) {
                (track.replayGainAlbumDb ?: track.replayGainTrackDb) to (track.replayGainAlbumPeak ?: track.replayGainTrackPeak)
            } else {
                (track.replayGainTrackDb ?: track.replayGainAlbumDb) to (track.replayGainTrackPeak ?: track.replayGainAlbumPeak)
            }
            ReplayGainMode.OFF -> null to null
        }
        if (gainDb == null) return 1f
        var linear = 10.0.pow((gainDb + preampDb) / 20.0)
        if (preventClipping && peak != null && peak > 0.0) {
            linear = min(linear, 1.0 / peak)
        }
        return linear.coerceIn(0.0, 1.0).toFloat()
    }

    fun sameAlbum(a: Track?, b: Track?): Boolean =
        a != null && b != null && a.albumId != null && a.albumId == b.albumId
}
