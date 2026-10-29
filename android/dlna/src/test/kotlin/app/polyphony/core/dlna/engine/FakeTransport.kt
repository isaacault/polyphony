package app.polyphony.core.dlna.engine

import app.polyphony.core.dlna.service.TransportState
import app.polyphony.core.dlna.soap.DlnaException

/**
 * A renderer simulator driven by a virtual clock: position advances while PLAYING, tracks end at
 * their configured duration, and SetNextAVTransportURI hand-off is emulated when supported.
 */
class FakeTransport(private val clock: () -> Long) : DlnaTransport {
    val calls = mutableListOf<String>()
    val durations = mutableMapOf<String, Long>()
    var supportsSetNext = true
    var supportsPause = true
    var failAll = false
    var setNextAttempts = 0
    var volume = 30
    /** Some renderers keep reporting the old TrackURI for a while after a SetNext hand-off. */
    var staleTrackUri = false
    /** The worst renderers freeze every field (URI, duration, position at the old end) after a
     *  hand-off while still reporting PLAYING, so there is no signal that a track changed. */
    var freezeAtHandoff = false

    var state = TransportState.NO_MEDIA_PRESENT
        private set
    var currentUri: String? = null
        private set
    var nextUri: String? = null
        private set

    private var basePositionMs = 0L
    private var playingSince = 0L
    private var durationMs: Long? = null
    /** The URI reported by positionInfo; lags one hand-off behind when [staleTrackUri] is set. */
    private var reportedUri: String? = null
    private var pendingReportedUri: String? = null
    /** A frozen snapshot of the first track's end, reported forever once [freezeAtHandoff] fires. */
    private var frozen: TransportPosition? = null

    /** Move the renderer into an externally-controlled state (someone pressed a button on it). */
    fun externalPause() { tick(); if (state == TransportState.PLAYING) { basePositionMs = position(); state = TransportState.PAUSED_PLAYBACK } }
    fun externalPlay() { if (state == TransportState.PAUSED_PLAYBACK) { playingSince = clock(); state = TransportState.PLAYING } }

    private fun position(): Long = if (state == TransportState.PLAYING) basePositionMs + (clock() - playingSince) else basePositionMs

    private fun tick() {
        if (state != TransportState.PLAYING) return
        val d = durationMs ?: return
        val pos = position()
        if (pos >= d) {
            val overshoot = pos - d
            val next = nextUri
            if (next != null) {
                // The first hand-off of a frozen renderer pins its reported fields at the old end.
                if (freezeAtHandoff && frozen == null) frozen = TransportPosition(currentUri, d, d)
                // Stale renderers surface the new URI only on the *second* poll after the hand-off.
                if (staleTrackUri) { reportedUri = currentUri; pendingReportedUri = next } else { reportedUri = next }
                currentUri = next
                nextUri = null
                durationMs = durations[next]
                basePositionMs = overshoot.coerceAtMost(durationMs ?: Long.MAX_VALUE)
                playingSince = clock()
            } else {
                basePositionMs = d
                state = TransportState.STOPPED
            }
        }
    }

    private fun guard() { if (failAll) throw DlnaException(0, "Renderer unreachable") }

    override suspend fun setUri(url: String, metadata: String) {
        guard(); calls += "setUri:$url"; currentUri = url; nextUri = null; reportedUri = url; pendingReportedUri = null
        durationMs = durations[url]; basePositionMs = 0; state = TransportState.STOPPED
    }

    override suspend fun setNextUri(url: String, metadata: String) {
        guard(); setNextAttempts++
        if (!supportsSetNext) throw DlnaException(401, "Invalid Action")
        calls += "setNext:$url"; nextUri = url
    }

    override suspend fun play() { guard(); calls += "play"; tick(); if (state != TransportState.PLAYING) { playingSince = clock(); state = TransportState.PLAYING } }

    override suspend fun pause() {
        guard(); if (!supportsPause) throw DlnaException(701, "Transition not available")
        calls += "pause"; tick(); basePositionMs = position(); state = TransportState.PAUSED_PLAYBACK
    }

    override suspend fun stop() { guard(); calls += "stop"; tick(); basePositionMs = position(); state = TransportState.STOPPED }

    override suspend fun seek(positionMs: Long) { guard(); calls += "seek:$positionMs"; basePositionMs = positionMs; playingSince = clock() }

    override suspend fun positionInfo(): TransportPosition {
        guard(); tick()
        frozen?.let { return it }
        val uri = reportedUri ?: currentUri
        // A stale renderer catches up to the real URI on the following poll.
        if (pendingReportedUri != null) { reportedUri = pendingReportedUri; pendingReportedUri = null }
        return TransportPosition(uri, position(), durationMs)
    }

    override suspend fun transportState(): TransportState { guard(); tick(); return state }

    override suspend fun setVolume(percent: Int) { guard(); volume = percent }

    override suspend fun getVolume(): Int = volume
}
