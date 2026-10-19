package app.polyphony.core.dlna.ssdp

/** A parsed SSDP datagram: an M-SEARCH response or a NOTIFY advertisement. */
data class SsdpMessage(
    val kind: Kind,
    val headers: Map<String, String>,
) {
    enum class Kind { RESPONSE, NOTIFY_ALIVE, NOTIFY_BYEBYE, NOTIFY_UPDATE, OTHER }

    val location: String? get() = headers["location"]?.takeIf { it.isNotBlank() }
    val usn: String? get() = headers["usn"]
    /** Search target (responses) or notification type (NOTIFY). */
    val type: String? get() = headers["st"] ?: headers["nt"]
    val udn: String? get() = usn?.substringBefore("::")?.trim()?.takeIf { it.isNotEmpty() }
    val maxAgeSeconds: Int?
        get() = headers["cache-control"]
            ?.let { MAX_AGE.find(it)?.groupValues?.get(1)?.toIntOrNull() }

    /** True when this datagram concerns a UPnP MediaRenderer (any version). */
    val isMediaRenderer: Boolean
        get() = (type?.contains(MEDIA_RENDERER_PREFIX, ignoreCase = true) == true) ||
            (usn?.contains(MEDIA_RENDERER_PREFIX, ignoreCase = true) == true)

    companion object {
        const val MEDIA_RENDERER_PREFIX = "urn:schemas-upnp-org:device:MediaRenderer:"
        const val MEDIA_RENDERER_ST = "urn:schemas-upnp-org:device:MediaRenderer:1"
        private val MAX_AGE = Regex("max-age\\s*=\\s*(\\d+)", RegexOption.IGNORE_CASE)

        /** Parses a raw datagram; returns null for things that are not SSDP (or are M-SEARCH requests). */
        fun parse(raw: String): SsdpMessage? {
            val lines = raw.split("\r\n", "\n").map { it.trimEnd('\r') }
            val start = lines.firstOrNull { it.isNotBlank() } ?: return null
            val kind = when {
                start.startsWith("HTTP/1.1 200", ignoreCase = true) || start.startsWith("HTTP/1.0 200", ignoreCase = true) -> Kind.RESPONSE
                start.startsWith("NOTIFY", ignoreCase = true) -> null // resolved from NTS below
                start.startsWith("M-SEARCH", ignoreCase = true) -> return null
                else -> Kind.OTHER
            }
            val headers = HashMap<String, String>()
            for (line in lines.drop(lines.indexOf(start) + 1)) {
                if (line.isBlank()) break
                val idx = line.indexOf(':')
                if (idx <= 0) continue
                val key = line.substring(0, idx).trim().lowercase()
                val value = line.substring(idx + 1).trim()
                headers[key] = value
            }
            val resolved = kind ?: when (headers["nts"]?.lowercase()) {
                "ssdp:alive" -> Kind.NOTIFY_ALIVE
                "ssdp:byebye" -> Kind.NOTIFY_BYEBYE
                "ssdp:update" -> Kind.NOTIFY_UPDATE
                else -> Kind.OTHER
            }
            return SsdpMessage(resolved, headers)
        }

        fun buildSearch(searchTarget: String, mx: Int = 2): String =
            "M-SEARCH * HTTP/1.1\r\n" +
                "HOST: 239.255.255.250:1900\r\n" +
                "MAN: \"ssdp:discover\"\r\n" +
                "MX: $mx\r\n" +
                "ST: $searchTarget\r\n" +
                "USER-AGENT: Android UPnP/1.1 Polyphony/0.1\r\n" +
                "\r\n"
    }
}
