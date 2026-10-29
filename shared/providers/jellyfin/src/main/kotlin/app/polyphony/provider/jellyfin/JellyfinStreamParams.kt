package app.polyphony.provider.jellyfin

import app.polyphony.core.domain.model.Track
import app.polyphony.core.domain.transcode.AudioCodec
import app.polyphony.core.domain.transcode.TranscodeProfile

/**
 * Parameters for Jellyfin's `/Audio/{id}/universal` endpoint derived from a [TranscodeProfile].
 *
 * The universal endpoint builds a device profile from these: files whose container is in
 * [containers] and whose bitrate / sample rate / bit depth are within the caps are direct-played;
 * everything else is transcoded to [audioCodec] inside [transcodingContainer].
 */
data class JellyfinStreamParams(
    val containers: List<String>,
    val audioCodec: String,
    val transcodingContainer: String,
    /** Direct-play threshold in bits per second; null = no cap. */
    val maxStreamingBitrate: Int?,
    /** Bitrate used when transcoding, in bits per second. */
    val audioBitRate: Int?,
    val maxAudioSampleRate: Int?,
    val maxAudioBitDepth: Int?,
    val transcodedMimeType: String,
    /** Best-effort guess whether the server will transcode this particular track. */
    val likelyTranscoded: Boolean,
) {
    companion object {
        /** Containers (optionally `container|codec`) Android's ExoPlayer plays natively. */
        val LOSSLESS_CONTAINERS = listOf("flac", "m4a|alac", "wav", "aiff")
        val LOSSY_CONTAINERS = listOf(
            "mp3", "opus", "ogg|opus", "webm|opus", "aac", "m4a|aac", "m4b|aac",
            "ogg|vorbis", "oga", "webma", "webm|webma",
        )
        val ALL_CONTAINERS = LOSSLESS_CONTAINERS + LOSSY_CONTAINERS

        private val NATIVE_CONTAINER_NAMES = setOf(
            "flac", "mp3", "opus", "ogg", "oga", "webm", "webma", "aac", "m4a", "m4b", "wav", "aiff", "alac",
        )

        fun from(profile: TranscodeProfile, track: Track): JellyfinStreamParams {
            val (codec, container, mime) = when (profile.codec) {
                AudioCodec.ORIGINAL, AudioCodec.FLAC -> Triple("flac", "flac", "audio/flac")
                AudioCodec.OPUS -> Triple("opus", "opus", "audio/ogg")
                AudioCodec.AAC -> Triple("aac", "aac", "audio/aac")
                AudioCodec.MP3 -> Triple("mp3", "mp3", "audio/mpeg")
                AudioCodec.VORBIS -> Triple("vorbis", "ogg", "audio/ogg")
            }
            val lossyTarget = profile.codec.lossy
            val bitrateBps = profile.maxBitrateKbps.takeIf { it > 0 }?.let { it * 1000 }
            val sampleRate = profile.maxSampleRateHz.takeIf { it > 0 }
            val bitDepth = profile.maxBitDepth.takeIf { it > 0 }
            // A lossy profile should never direct-play lossless originals, even without a bitrate cap.
            val containers = if (lossyTarget) LOSSY_CONTAINERS else ALL_CONTAINERS

            val trackContainer = track.container?.lowercase()
            val trackBitrateKbps = track.bitrateKbps
            val trackSampleRate = track.sampleRateHz
            val trackBitDepth = track.bitDepth
            val nativelyPlayable = trackContainer != null && trackContainer in NATIVE_CONTAINER_NAMES
            val overBitrate = bitrateBps != null && trackBitrateKbps != null && trackBitrateKbps * 1000 > bitrateBps
            val overSampleRate = sampleRate != null && trackSampleRate != null && trackSampleRate > sampleRate
            val overBitDepth = bitDepth != null && trackBitDepth != null && trackBitDepth > bitDepth
            val likelyTranscoded = !nativelyPlayable || overBitrate || overSampleRate || overBitDepth ||
                (lossyTarget && track.isLossless)

            return JellyfinStreamParams(
                containers = containers,
                audioCodec = codec,
                transcodingContainer = container,
                maxStreamingBitrate = bitrateBps,
                audioBitRate = if (lossyTarget) bitrateBps else null,
                maxAudioSampleRate = sampleRate,
                maxAudioBitDepth = bitDepth,
                transcodedMimeType = mime,
                likelyTranscoded = likelyTranscoded,
            )
        }

        fun directMimeType(container: String?): String? = when (container?.lowercase()) {
            "flac" -> "audio/flac"
            "mp3" -> "audio/mpeg"
            "m4a", "m4b", "alac" -> "audio/mp4"
            "aac" -> "audio/aac"
            "opus", "ogg", "oga" -> "audio/ogg"
            "webm", "webma" -> "audio/webm"
            "wav" -> "audio/wav"
            "aiff" -> "audio/aiff"
            null -> null
            else -> null
        }
    }

    /** MIME type the client should expect for [track]. */
    fun mimeTypeFor(track: Track): String? =
        if (likelyTranscoded) transcodedMimeType else directMimeType(track.container) ?: track.mimeType
}
