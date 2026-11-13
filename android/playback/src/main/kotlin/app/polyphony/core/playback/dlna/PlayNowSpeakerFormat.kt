package app.polyphony.core.playback.dlna

import app.polyphony.core.dlna.service.RendererFormatSupport

/**
 * What a speaker is told a Play now song is, and whether continuo converts it first. continuo
 * serves the Soulseek copy as it is, in whatever format Play now found quickest (MP3, FLAC, often
 * Opus), so the label comes from the copy's real format, and a format the speaker doesn't list
 * (the Yamaha RX-V483 has no Opus decoder) is asked for as FLAC, or MP3 (continuo's `as=`).
 * An unknown format is asked for converted too: the label is then true whatever the copy is.
 */
internal object PlayNowSpeakerFormat {

    data class Choice(
        /** The `protocolInfo` MIME type the speaker is given. */
        val mimeType: String,
        /** continuo's `as=` value (flac, mp3), or null for the file as it is. */
        val convertTo: String?,
    )

    fun choose(format: String?, support: RendererFormatSupport): Choice {
        val ext = format?.lowercase()?.trim()?.removePrefix(".")
        val mime = ext?.let(::mimeFor)
        val plays = when {
            mime == null -> false
            support.unknown -> true
            ext == "opus" -> support.opus
            ext == "ogg" || ext == "oga" -> support.vorbis
            else -> support.supports(mime)
        }
        return when {
            mime != null && plays -> Choice(mime, null)
            support.flac || !support.mp3 -> Choice("audio/flac", "flac")
            else -> Choice("audio/mpeg", "mp3")
        }
    }

    /** The link with continuo's `as=` added, when the copy is converted. */
    fun link(url: String, choice: Choice): String = choice.convertTo?.let { fmt ->
        url + (if ('?' in url) "&" else "?") + "as=" + fmt
    } ?: url

    /** The MIME type of a copy's file extension, as continuo serves it; null when not known. */
    fun mimeFor(ext: String): String? = when (ext) {
        "mp3" -> "audio/mpeg"
        "flac" -> "audio/flac"
        "m4a", "aac", "alac", "mp4" -> "audio/mp4"
        "ogg", "oga", "opus" -> "audio/ogg"
        "wav" -> "audio/wav"
        else -> null
    }
}
