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

    var state = TransportState.NO_MEDIA_PRESENT
        private set
    var currentUri: String? = null
        private set
    var nextUri: String? = null
        private set

    private var basePositionMs = 0L
    private var playingSince = 0L
    private var durationMs: Long? = null

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
        guard(); calls += "setUri:$url"; currentUri = url; nextUri = null
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

    override suspend fun positionInfo(): TransportPosition { guard(); tick(); return TransportPosition(currentUri, position(), durationMs) }

    override suspend fun transportState(): TransportState { guard(); tick(); return state }

    override suspend fun setVolume(percent: Int) { guard(); volume = percent }

    override suspend fun getVolume(): Int = volume
}
