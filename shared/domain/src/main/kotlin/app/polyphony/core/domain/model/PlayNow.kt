package app.polyphony.core.domain.model

/**
 * A song you don't have, fetched from Soulseek by continuo to play now (continuo#12). [playable]
 * once the first bytes are in; [status] is searching, downloading, streaming, ready or failed.
 */
data class PlayNowStream(
    val id: String,
    val status: String,
    val playable: Boolean,
    val percent: Double,
    val title: String,
    val artist: String?,
    val durationMs: Long?,
    val format: String?,
    val error: String?,
)

/** A Play now song in the player: its track id carries the stream, it isn't a library song. */
object PlayNowIds {
    private const val PREFIX = "playnow:"

    fun of(streamId: String): String = PREFIX + streamId

    /** The stream behind a track id, or null for a library song. */
    fun streamId(trackId: String): String? = trackId.takeIf { it.startsWith(PREFIX) }?.removePrefix(PREFIX)
}
