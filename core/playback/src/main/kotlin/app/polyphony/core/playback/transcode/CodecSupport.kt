package app.polyphony.core.playback.transcode

import app.polyphony.core.domain.model.Server
import app.polyphony.core.domain.model.ServerOptions
import app.polyphony.core.domain.transcode.AudioCodec
import app.polyphony.core.domain.transcode.TranscodeProfile

/** Pure helpers around a server's probed transcoding capabilities. */
object CodecSupport {
    /** Codecs the probe found working, or null when the server has not been probed yet. */
    fun supported(server: Server): Set<AudioCodec>? =
        server.options[ServerOptions.TRANSCODE_CODECS]?.let { parse(it) }

    fun parse(raw: String): Set<AudioCodec> =
        raw.split(',').mapNotNull { name -> AudioCodec.entries.firstOrNull { it.name == name.trim() } }.toSet()

    fun encode(codecs: Set<AudioCodec>): String = codecs.sortedBy { it.ordinal }.joinToString(",") { it.name }

    /**
     * Replace a profile whose codec the server cannot produce with the closest one it can:
     * MP3 (universal on Subsonic servers), then AAC, then Vorbis, keeping the bitrate cap; original
     * quality when nothing lossy works. Profiles are left untouched when support is unknown.
     */
    fun adjust(profile: TranscodeProfile, supported: Set<AudioCodec>?): TranscodeProfile {
        if (supported == null || profile.isPassthrough || profile.codec == AudioCodec.ORIGINAL) return profile
        if (profile.codec in supported) return profile
        val replacement = listOf(AudioCodec.MP3, AudioCodec.AAC, AudioCodec.VORBIS, AudioCodec.OPUS)
            .firstOrNull { it in supported } ?: return TranscodeProfile.ORIGINAL
        val kbps = profile.maxBitrateKbps.takeIf { it > 0 } ?: 192
        return TranscodeProfile(
            id = "auto.${replacement.name.lowercase()}.$kbps",
            name = "${replacement.label} $kbps kbps (auto)",
            codec = replacement,
            maxBitrateKbps = kbps,
        )
    }
}
