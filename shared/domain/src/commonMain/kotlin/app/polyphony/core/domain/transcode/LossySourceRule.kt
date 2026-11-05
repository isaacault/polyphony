package app.polyphony.core.domain.transcode

import app.polyphony.core.domain.model.Track

/**
 * An already-compressed file is not re-encoded: lossy to lossy stacks a second encoder's losses on
 * the first (generation loss) and saves little. Such a track streams as it is, unless the
 * connection is struggling (the decision is capped for latency), when a smaller stream matters
 * more than the extra loss.
 */
object LossySourceRule {
    /** Lossy formats a phone plays natively, by file suffix / container. */
    private val PLAYABLE_LOSSY = setOf("mp3", "m4a", "aac", "mp4", "ogg", "oga", "opus", "webm")

    /** The decision to use for [track]: [decision] itself, or the original when the rule applies. */
    fun apply(decision: TranscodeDecision, track: Track): TranscodeDecision =
        if (keepsOriginal(decision, track)) {
            TranscodeDecision(TranscodeProfile.ORIGINAL, TranscodeDecision.Reason.LOSSY_SOURCE)
        } else {
            decision
        }

    fun keepsOriginal(decision: TranscodeDecision, track: Track): Boolean {
        val profile = decision.profile
        if (profile.isPassthrough || !profile.codec.lossy || decision.cappedByLatency) return false
        val container = track.container?.lowercase() ?: return false
        return !track.isLossless && container in PLAYABLE_LOSSY
    }
}
