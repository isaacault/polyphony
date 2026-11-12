package app.polyphony.core.playback.stream

import androidx.media3.common.PlaybackException
import androidx.media3.datasource.HttpDataSource
import java.io.IOException

/**
 * A song that can't load until the user changes something, so no retry helps: offline mode is on
 * and it isn't downloaded (or its download was deleted), or its server was removed. Thrown by
 * [StreamResolver]; the service skips to the next song instead of retrying.
 */
class UnplayableSongException(message: String) : IOException(message)

/** What a failed load says about the song. Pure; unit-tested. */
object LoadFailures {
    /** The failure, or one of its causes, is an [UnplayableSongException]. */
    fun isUnplayable(error: Throwable?): Boolean = causes(error).any { it is UnplayableSongException }

    /** The HTTP status the server answered with, when the failure was one. */
    fun httpStatus(error: Throwable?): Int? =
        causes(error).filterIsInstance<HttpDataSource.InvalidResponseCodeException>().firstOrNull()?.responseCode

    /** The server says the song isn't there (deleted since it was queued), not that it's struggling. */
    fun isGone(httpStatus: Int?): Boolean = httpStatus == 404 || httpStatus == 410

    /**
     * The failure is the connection (offline, or no answer in time), which the song's ordinary
     * stream can't get round either: an adaptive stream already drops to the lowest level the
     * connection carries, so it waits for the network. Anything else, online (an error status,
     * a wrong content type, playlists or segments that don't parse, an unexpected I/O error) is
     * continuo not serving it, and the ordinary stream may play where this won't.
     */
    fun connectionLost(errorCode: Int, online: Boolean): Boolean =
        !online || errorCode in CONNECTION_ERRORS

    private val CONNECTION_ERRORS = setOf(
        PlaybackException.ERROR_CODE_IO_NETWORK_CONNECTION_FAILED,
        PlaybackException.ERROR_CODE_IO_NETWORK_CONNECTION_TIMEOUT,
    )

    private fun causes(error: Throwable?): Sequence<Throwable> =
        generateSequence(error) { e -> e.cause?.takeIf { it !== e } }.take(MAX_DEPTH)

    private const val MAX_DEPTH = 16
}

/**
 * Skipping songs that can't play without going round a queue of them forever (with repeat all,
 * there is always a next song): at most one skip per song in the queue until something plays.
 */
class UnplayableSkips {
    private var run = 0

    /** Whether one more skip is allowed in a [queueSize]-song queue; counts it. */
    fun allow(queueSize: Int): Boolean {
        if (run >= queueSize) return false
        run++
        return true
    }

    /** A song is playing: the run of skips is over. */
    fun onPlaying() {
        run = 0
    }
}
