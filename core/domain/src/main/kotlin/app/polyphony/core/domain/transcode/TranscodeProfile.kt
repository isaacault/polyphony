package app.polyphony.core.domain.transcode

import kotlinx.serialization.Serializable

/** Target codec for a transcoding profile. */
@Serializable
enum class AudioCodec(val label: String, val lossy: Boolean, val typicalMaxKbps: Int) {
    ORIGINAL("Original", false, 0),
    FLAC("FLAC", false, 0),
    OPUS("Opus", true, 510),
    AAC("AAC", true, 320),
    MP3("MP3", true, 320),
    VORBIS("Ogg Vorbis", true, 500),
}

/**
 * Describes how a track should be delivered. A profile with [AudioCodec.ORIGINAL] and no caps is a
 * passthrough. Bitrate caps of 0 mean "unlimited".
 */
@Serializable
data class TranscodeProfile(
    val id: String,
    val name: String,
    val codec: AudioCodec = AudioCodec.ORIGINAL,
    val maxBitrateKbps: Int = 0,
    val maxSampleRateHz: Int = 0,
    val maxBitDepth: Int = 0,
    val builtIn: Boolean = false,
) {
    val isPassthrough: Boolean
        get() = codec == AudioCodec.ORIGINAL && maxBitrateKbps == 0 && maxSampleRateHz == 0 && maxBitDepth == 0

    /**
     * Identifies the stream this profile produces: changes whenever any parameter that reaches the
     * server does, so an edited profile is never served from a URL cached under its old settings.
     */
    val fingerprint: String
        get() = "$id:${codec.name}:$maxBitrateKbps:$maxSampleRateHz:$maxBitDepth"

    /**
     * A comparable notion of "how much quality" the profile allows, used to pick the more
     * conservative of two profiles. Lossless and unlimited profiles rank highest.
     */
    val qualityRank: Int
        get() = when {
            codec == AudioCodec.ORIGINAL && maxBitrateKbps == 0 -> Int.MAX_VALUE
            codec == AudioCodec.FLAC && maxBitrateKbps == 0 -> Int.MAX_VALUE - 1
            maxBitrateKbps == 0 -> codec.typicalMaxKbps
            else -> maxBitrateKbps
        }

    val summary: String
        get() = buildString {
            append(if (codec == AudioCodec.ORIGINAL) "Original" else codec.label)
            if (maxBitrateKbps > 0) append(" · ≤${maxBitrateKbps} kbps")
            if (maxSampleRateHz > 0) append(" · ≤${maxSampleRateHz / 1000f} kHz".replace(".0 kHz", " kHz"))
            if (maxBitDepth > 0) append(" · ≤$maxBitDepth-bit")
        }

    companion object {
        const val ORIGINAL_ID = "builtin.original"
        const val LOSSLESS_CAPPED_ID = "builtin.lossless48"
        const val HIGH_ID = "builtin.high"
        const val BALANCED_ID = "builtin.balanced"
        const val SAVER_ID = "builtin.saver"
        const val CAST_SAFE_ID = "builtin.castsafe"

        val ORIGINAL = TranscodeProfile(ORIGINAL_ID, "Original quality", builtIn = true)
        val LOSSLESS_CAPPED = TranscodeProfile(
            LOSSLESS_CAPPED_ID, "Lossless, capped to 16-bit/48 kHz",
            codec = AudioCodec.FLAC, maxSampleRateHz = 48_000, maxBitDepth = 16, builtIn = true,
        )
        val HIGH = TranscodeProfile(HIGH_ID, "High", codec = AudioCodec.OPUS, maxBitrateKbps = 256, builtIn = true)
        val BALANCED = TranscodeProfile(BALANCED_ID, "Balanced", codec = AudioCodec.OPUS, maxBitrateKbps = 128, builtIn = true)
        val SAVER = TranscodeProfile(SAVER_ID, "Data saver", codec = AudioCodec.OPUS, maxBitrateKbps = 64, builtIn = true)
        val CAST_SAFE = TranscodeProfile(CAST_SAFE_ID, "Cast compatible", codec = AudioCodec.MP3, maxBitrateKbps = 320, builtIn = true)

        val BUILT_INS: List<TranscodeProfile> = listOf(ORIGINAL, LOSSLESS_CAPPED, HIGH, BALANCED, SAVER, CAST_SAFE)

        fun builtIn(id: String): TranscodeProfile? = BUILT_INS.firstOrNull { it.id == id }
    }
}
