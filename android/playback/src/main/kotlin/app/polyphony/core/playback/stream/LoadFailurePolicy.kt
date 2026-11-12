package app.polyphony.core.playback.stream

import androidx.media3.common.PlaybackException

/**
 * What to do about a song whose stream failed to load (#11, #185): retry it, play its ordinary
 * stream instead of the adaptive one, try original quality, skip it, or wait for the network.
 * Pure, so every path is tested (LoadFailurePolicyTest); PlaybackService carries the answer out.
 * Play now songs, and songs that can't play at all (offline mode, a removed server), are decided
 * before this.
 */
object LoadFailurePolicy {
    data class Failure(
        val errorCode: Int,
        val httpStatus: Int?,
        val online: Boolean,
        /** Its source is HLS from continuo, as it was created (not as it would be routed now). */
        val adaptive: Boolean,
        /** Its ordinary stream was already tried this session. */
        val ordinaryTried: Boolean,
        /** It was downloaded after it was queued: the ordinary path plays the file. */
        val downloaded: Boolean,
        /** Retries of this song so far. */
        val retries: Int,
        /** It plays transcoded, so original quality is a step left to try. */
        val originalQualityLeft: Boolean,
    )

    sealed interface Action {
        data class Retry(val attempt: Int, val afterMs: Long) : Action
        data object PlayOrdinaryStream : Action
        data object PlayOriginalQuality : Action
        data object SkipGone : Action
        data object WaitForNetwork : Action
        /** Nothing left to try; the error stays for the listener to see. */
        data object Stop : Action
    }

    const val MAX_RETRIES = 2
    private const val RETRY_STEP_MS = 1_500L

    fun decide(f: Failure): Action {
        // The server says the song isn't there (deleted since it was queued): waiting won't bring
        // it. After one retry (a server restarting can answer 404 for a moment) it's skipped; an
        // adaptive song tries its ordinary stream first.
        if (f.online && LoadFailures.isGone(f.httpStatus) && f.retries >= 1) {
            return if (f.adaptive && !f.ordinaryTried) Action.PlayOrdinaryStream else Action.SkipGone
        }
        // A network hand-over, a server hiccup: the same stream again, with a short back-off.
        if (f.retries < MAX_RETRIES) return Action.Retry(f.retries + 1, RETRY_STEP_MS * (f.retries + 1))
        if (f.adaptive) {
            // A lost connection: the adaptive stream stays (at the lowest level while it keeps
            // failing) and picks up when the network is back. Anything else, or a download since:
            // the ordinary path.
            val lost = LoadFailures.connectionLost(f.errorCode, f.online)
            return if ((f.downloaded || !lost) && !f.ordinaryTried) Action.PlayOrdinaryStream else Action.WaitForNetwork
        }
        // Original quality only when the server answered: a lost connection says nothing about its
        // transcoding, and marking it broken would send lossless over mobile data all session.
        if (f.originalQualityLeft && !LoadFailures.connectionLost(f.errorCode, f.online)) return Action.PlayOriginalQuality
        return if (!f.online || f.errorCode in WAIT_FOR) Action.WaitForNetwork else Action.Stop
    }

    /** Failures of the server or the network, which come back: worth waiting for. */
    private val WAIT_FOR = setOf(
        PlaybackException.ERROR_CODE_IO_NETWORK_CONNECTION_FAILED,
        PlaybackException.ERROR_CODE_IO_NETWORK_CONNECTION_TIMEOUT,
        PlaybackException.ERROR_CODE_IO_BAD_HTTP_STATUS,
    )
}
