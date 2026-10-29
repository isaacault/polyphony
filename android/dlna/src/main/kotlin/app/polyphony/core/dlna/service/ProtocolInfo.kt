package app.polyphony.core.dlna.service

/** Parsing of ConnectionManager protocol-info strings into MIME types. */
object ProtocolInfo {

    /** `http-get:*:audio/mpeg:DLNA.ORG_PN=MP3,...` → {"audio/mpeg", ...}. Wildcards are dropped. */
    fun parseMimeTypes(protocolInfo: String?): Set<String> {
        if (protocolInfo.isNullOrBlank()) return emptySet()
        return protocolInfo.split(',')
            .mapNotNull { entry ->
                val fields = entry.trim().split(':')
                if (fields.size < 3) return@mapNotNull null
                if (!fields[0].equals("http-get", true) && !fields[0].equals("*", true)) return@mapNotNull null
                fields[2].trim().lowercase().takeIf { it.isNotEmpty() && it != "*" }
            }
            .toSet()
    }

    /** DLNA `protocolInfo` for a `<res>` element, with profile names where receivers care. */
    fun forMime(mime: String): String {
        val lower = mime.lowercase()
        val pn = when {
            lower == "audio/mpeg" || lower == "audio/mp3" -> "DLNA.ORG_PN=MP3;"
            lower == "audio/mp4" || lower == "audio/aac" || lower == "audio/x-m4a" -> "DLNA.ORG_PN=AAC_ISO_320;"
            lower == "audio/wav" || lower == "audio/x-wav" || lower.startsWith("audio/l16") -> "DLNA.ORG_PN=LPCM;"
            else -> ""
        }
        return "http-get:*:$mime:${pn}DLNA.ORG_OP=01;DLNA.ORG_CI=0;DLNA.ORG_FLAGS=01700000000000000000000000000000"
    }
}

/** Which audio formats a renderer advertises; `unknown` when it did not answer GetProtocolInfo. */
data class RendererFormatSupport(
    val flac: Boolean,
    val mp3: Boolean,
    val aac: Boolean,
    val opus: Boolean,
    val vorbis: Boolean,
    val wav: Boolean,
    val unknown: Boolean,
) {
    /** True when [mime] is advertised, or when support is unknown (optimistic). */
    fun supports(mime: String?): Boolean {
        if (unknown || mime == null) return true
        val m = mime.lowercase()
        return when {
            m.contains("flac") -> flac
            m == "audio/mpeg" || m == "audio/mp3" -> mp3
            m == "audio/mp4" || m == "audio/aac" || m == "audio/x-m4a" -> aac
            m.contains("opus") -> opus
            m == "audio/ogg" || m.contains("vorbis") -> vorbis
            m.contains("wav") || m.startsWith("audio/l16") -> wav
            else -> true
        }
    }

    /** Codec identifiers (matching `AudioCodec` names) in preference order for this renderer. */
    val preferredCodecs: List<String>
        get() = buildList {
            if (unknown) { addAll(listOf("FLAC", "MP3", "AAC", "OPUS", "VORBIS")); return@buildList }
            if (flac) add("FLAC")
            if (mp3) add("MP3")
            if (aac) add("AAC")
            if (opus) add("OPUS")
            if (vorbis) add("VORBIS")
            if (isEmpty()) add("MP3") // every renderer we've met plays MP3 even when it says otherwise
        }

    companion object {
        val UNKNOWN = RendererFormatSupport(flac = true, mp3 = true, aac = true, opus = true, vorbis = true, wav = true, unknown = true)

        fun from(mimeTypes: Set<String>): RendererFormatSupport {
            if (mimeTypes.isEmpty()) return UNKNOWN
            val m = mimeTypes.map { it.lowercase() }
            fun any(vararg needles: String) = m.any { mime -> needles.any { mime.contains(it) } }
            return RendererFormatSupport(
                flac = any("flac"),
                mp3 = any("audio/mpeg", "audio/mp3"),
                aac = any("audio/mp4", "audio/aac", "audio/x-m4a", "audio/3gpp"),
                opus = any("opus"),
                vorbis = m.any { mime -> mime.contains("vorbis") || (mime.startsWith("audio/ogg") && !mime.contains("opus")) },
                wav = any("wav", "audio/l16"),
                unknown = false,
            )
        }
    }
}
