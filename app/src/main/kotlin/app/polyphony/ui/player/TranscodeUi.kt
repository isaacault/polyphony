package app.polyphony.ui.player

import app.polyphony.core.domain.model.Track
import app.polyphony.core.domain.provider.MediaProvider
import app.polyphony.core.domain.transcode.AudioCodec
import app.polyphony.core.domain.transcode.TranscodeDecision
import app.polyphony.core.domain.transcode.TranscodeProfile

/** "Original", "Opus 128k", "FLAC ≤48 kHz" — a compact description of what is being streamed. */
fun TranscodeProfile.qualityLabel(): String = buildString {
    if (isPassthrough) {
        append("Original")
        return@buildString
    }
    append(if (codec == AudioCodec.ORIGINAL) "Original" else codec.label)
    if (maxBitrateKbps > 0) append(" ${maxBitrateKbps}k")
    if (maxBitrateKbps == 0 && maxSampleRateHz > 0) append(" ≤${maxSampleRateHz / 1000} kHz")
}

/** Chip text: quality plus why it was picked. */
fun TranscodeDecision.chipText(): String = "${profile.qualityLabel()} · ${reason.label}"

/** Large artwork for the player; falls back to the list-size URL until the provider is known. */
fun largeArtworkUrl(track: Track, providers: Map<String, MediaProvider>, sizePx: Int = 1200): String? =
    providers[track.serverId]?.artworkUrl(track.artworkId, sizePx) ?: track.artworkUrl
