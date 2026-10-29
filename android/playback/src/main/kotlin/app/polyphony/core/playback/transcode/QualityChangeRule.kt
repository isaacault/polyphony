package app.polyphony.core.playback.transcode

import app.polyphony.core.domain.transcode.NetworkKind
import app.polyphony.core.domain.transcode.TranscodeProfile

/** The quality the player applied when the current item was loaded, and the conditions it was chosen under. */
data class AppliedQuality(
    val targetKey: String,
    val profile: TranscodeProfile,
    val networkKind: NetworkKind,
    val metered: Boolean,
    val cappedByLatency: Boolean,
)

/**
 * Decides what to do with the playing track when the transcoding decision changes.
 *
 * A change the user made by hand (a device setting, a rule, an edited profile) applies at once:
 * nothing about the connection moved, so re-opening the stream is safe and waiting would look like
 * the setting did nothing. A change caused by the environment (network hand-over, metering,
 * latency cap) waits for the next track unless the user opted in, because re-opening the stream
 * during a hand-over is exactly when it is most likely to fail and the buffer usually carries the
 * track to its end.
 */
object QualityChangeRule {
    enum class Action { NONE, RELOAD_NOW, NEXT_TRACK }

    fun decide(previous: AppliedQuality?, next: AppliedQuality, reloadOnEnvironmentChange: Boolean): Action {
        if (previous == null) return Action.NONE
        // An output transfer re-resolves every item for the new target by itself.
        if (previous.targetKey != next.targetKey) return Action.NONE
        if (previous.profile.fingerprint == next.profile.fingerprint) return Action.NONE
        val environmentChanged = previous.networkKind != next.networkKind ||
            previous.metered != next.metered ||
            previous.cappedByLatency != next.cappedByLatency
        return if (!environmentChanged || reloadOnEnvironmentChange) Action.RELOAD_NOW else Action.NEXT_TRACK
    }
}
