package app.polyphony.core.downloads.storage

import app.polyphony.core.domain.model.Track
import app.polyphony.core.domain.transcode.AudioCodec
import app.polyphony.core.domain.transcode.TranscodeProfile

/** Pure path/extension rules, unit-tested without Android. */
object DownloadPaths {

    private val SAFE = Regex("[^A-Za-z0-9._-]")

    /** File-system safe id; appends a short hash when characters had to be replaced. */
    fun sanitize(id: String): String {
        val cleaned = id.replace(SAFE, "_")
        return if (cleaned == id) id else "${cleaned.take(80)}_${(id.hashCode().toLong() and 0xffffffffL).toString(16)}"
    }

    fun relativePath(serverId: String, trackId: String, extension: String): String =
        "${sanitize(serverId)}/${sanitize(trackId)}.$extension"

    /**
     * Extension for the stored file. Prefers the delivered MIME type, then the original container
     * for passthrough downloads, then a codec default.
     */
    fun extensionFor(track: Track, profile: TranscodeProfile, deliveredMimeType: String?): String {
        if (profile.isPassthrough || profile.codec == AudioCodec.ORIGINAL) {
            track.container?.lowercase()?.takeIf { it.isNotBlank() && it.length <= 5 }?.let { return it }
            mimeToExtension(deliveredMimeType ?: track.mimeType)?.let { return it }
            return "bin"
        }
        mimeToExtension(deliveredMimeType)?.let { return it }
        return when (profile.codec) {
            AudioCodec.FLAC -> "flac"
            AudioCodec.MP3 -> "mp3"
            AudioCodec.AAC -> "aac"
            AudioCodec.OPUS -> "ogg"
            AudioCodec.VORBIS -> "ogg"
            AudioCodec.ORIGINAL -> "bin"
        }
    }

    fun mimeToExtension(mime: String?): String? = when (mime?.substringBefore(';')?.trim()?.lowercase()) {
        "audio/flac", "audio/x-flac" -> "flac"
        "audio/mpeg", "audio/mp3" -> "mp3"
        "audio/ogg", "application/ogg", "audio/vorbis" -> "ogg"
        "audio/opus" -> "opus"
        "audio/aac", "audio/aacp" -> "aac"
        "audio/mp4", "audio/x-m4a", "audio/m4a" -> "m4a"
        "audio/wav", "audio/x-wav", "audio/wave" -> "wav"
        "audio/webm" -> "webm"
        "audio/x-ms-wma" -> "wma"
        "audio/aiff", "audio/x-aiff" -> "aiff"
        else -> null
    }
}
