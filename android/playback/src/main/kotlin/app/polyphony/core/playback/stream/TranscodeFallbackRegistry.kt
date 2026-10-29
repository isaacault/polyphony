package app.polyphony.core.playback.stream

import app.polyphony.core.domain.transcode.AudioCodec
import java.util.concurrent.ConcurrentHashMap

/**
 * Remembers, for this process, which (server, codec) transcodes have failed so later tracks skip
 * straight to original quality instead of timing out again. Nothing is persisted: a server
 * upgrade or network change may fix it, and a restart gives it another chance.
 */
class TranscodeFallbackRegistry {
    private val brokenCodecs = ConcurrentHashMap.newKeySet<String>()
    private val passthroughTracks = ConcurrentHashMap.newKeySet<String>()

    fun isBroken(serverId: String, codec: AudioCodec): Boolean = "$serverId|$codec" in brokenCodecs
    fun forcesPassthrough(trackId: String): Boolean = trackId in passthroughTracks

    /** Record a failed transcode; returns false when the codec was already known to be broken. */
    fun markBroken(serverId: String, codec: AudioCodec, trackId: String): Boolean {
        passthroughTracks += trackId
        return brokenCodecs.add("$serverId|$codec")
    }

    fun clear() { brokenCodecs.clear(); passthroughTracks.clear() }

    val summary: String
        get() = if (brokenCodecs.isEmpty()) "none" else brokenCodecs.sorted().joinToString(", ")
}
