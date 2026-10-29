package app.polyphony.core.playback.session

import app.polyphony.core.domain.model.PlayContext
import app.polyphony.core.playback.util.PlaybackJson

/** JSON form of a [PlayContext] for custom-command args, session extras and the persisted queue. */
object PlayContextCodec {
    fun encode(context: PlayContext?): String? = context?.let { PlaybackJson.encodeToString(PlayContext.serializer(), it) }

    fun decode(json: String?): PlayContext? =
        json?.takeIf { it.isNotBlank() }?.let { runCatching { PlaybackJson.decodeFromString(PlayContext.serializer(), it) }.getOrNull() }
}
