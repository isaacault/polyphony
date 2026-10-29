package app.polyphony.core.playback.dlna

import app.polyphony.core.dlna.service.RendererFormatSupport
import app.polyphony.core.domain.model.StreamRequest
import app.polyphony.core.domain.model.Track
import app.polyphony.core.domain.transcode.AudioCodec
import app.polyphony.core.domain.transcode.TranscodeProfile

/** Pure codec negotiation between the user's profile and what a renderer says it can play. */
object DlnaCodecFallback {

    data class Choice(
        val profile: TranscodeProfile,
        val request: StreamRequest,
        val mimeType: String,
        val substituted: Boolean,
    )

    val AAC_FALLBACK = TranscodeProfile("dlna.aac", "AAC 256 kbps", AudioCodec.AAC, maxBitrateKbps = 256, builtIn = true)
    val VORBIS_FALLBACK = TranscodeProfile("dlna.vorbis", "Vorbis 256 kbps", AudioCodec.VORBIS, maxBitrateKbps = 256, builtIn = true)

    /**
     * Keeps [preferred] when the renderer supports its MIME type (or support is unknown). Otherwise
     * walks the renderer's preferred codecs and picks the first that produces a supported stream.
     * Falls back to [preferred] when nothing matches so the renderer at least gets a chance.
     */
    fun choose(
        track: Track,
        preferred: TranscodeProfile,
        support: RendererFormatSupport,
        request: (TranscodeProfile) -> StreamRequest,
    ): Choice {
        val first = request(preferred)
        val firstMime = mimeOf(first, track, preferred)
        if (support.supports(firstMime)) return Choice(preferred, first, firstMime, substituted = false)
        for (codec in support.preferredCodecs) {
            val candidate = profileFor(codec, track) ?: continue
            if (candidate.id == preferred.id) continue
            val req = request(candidate)
            val mime = mimeOf(req, track, candidate)
            if (support.supports(mime)) return Choice(candidate, req, mime, substituted = true)
        }
        return Choice(preferred, first, firstMime, substituted = false)
    }

    /** A sensible profile for a renderer-preferred codec; passthrough when the file already is that codec. */
    fun profileFor(codec: String, track: Track): TranscodeProfile? {
        val container = track.container?.lowercase()
        return when (codec.uppercase()) {
            "FLAC" -> if (container == "flac") TranscodeProfile.ORIGINAL else TranscodeProfile.LOSSLESS_CAPPED
            "MP3" -> if (container == "mp3") TranscodeProfile.ORIGINAL else TranscodeProfile.CAST_SAFE
            "AAC" -> if (container == "m4a" || container == "aac" || container == "mp4") TranscodeProfile.ORIGINAL else AAC_FALLBACK
            "OPUS" -> if (container == "opus") TranscodeProfile.ORIGINAL else TranscodeProfile.HIGH
            "VORBIS" -> if (container == "ogg" || container == "oga") TranscodeProfile.ORIGINAL else VORBIS_FALLBACK
            else -> null
        }
    }

    fun mimeOf(request: StreamRequest, track: Track, profile: TranscodeProfile): String =
        request.mimeType ?: when (profile.codec) {
            AudioCodec.ORIGINAL -> track.mimeType ?: guessMime(track.container)
            AudioCodec.FLAC -> "audio/flac"
            AudioCodec.MP3 -> "audio/mpeg"
            AudioCodec.AAC -> "audio/mp4"
            AudioCodec.OPUS -> "audio/ogg"
            AudioCodec.VORBIS -> "audio/ogg"
        }

    fun guessMime(container: String?): String = when (container?.lowercase()) {
        "flac" -> "audio/flac"
        "mp3" -> "audio/mpeg"
        "m4a", "aac", "mp4", "alac" -> "audio/mp4"
        "ogg", "oga", "vorbis", "opus" -> "audio/ogg"
        "wav" -> "audio/wav"
        "aiff", "aif" -> "audio/aiff"
        "webm" -> "audio/webm"
        else -> "audio/mpeg"
    }
}
