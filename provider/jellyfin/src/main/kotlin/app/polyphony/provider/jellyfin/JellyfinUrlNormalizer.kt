package app.polyphony.provider.jellyfin

/**
 * Turns what a user types into a usable base URL.
 *
 * - Keeps an explicit scheme untouched.
 * - Without a scheme: LAN-looking hosts (IPv4/IPv6 literals, `localhost`, `.local`, `.lan`,
 *   `.home`, `.internal`) get `http://` and, when neither a port nor a path is given, the default
 *   Jellyfin port; anything else gets `https://`.
 * - Trailing slashes are removed so the SDK can append paths cleanly.
 */
object JellyfinUrlNormalizer {

    private val IPV4 = Regex("""^\d{1,3}(\.\d{1,3}){3}$""")
    private val LOCAL_SUFFIXES = listOf(".local", ".lan", ".home", ".internal", ".localdomain")

    fun normalize(input: String, defaultPort: Int = 8096): String {
        val raw = input.trim()
        require(raw.isNotEmpty()) { "Server address is empty" }
        if (raw.contains("://")) return raw.trimEnd('/')

        val authority = raw.substringBefore('/')
        val path = raw.substring(authority.length)
        val host = hostOf(authority)
        val hasPort = authority.lastIndexOf(':') > authority.lastIndexOf(']') && !isBareIpv6(authority)

        val withScheme = if (isLocalHost(host)) {
            val authorityWithPort = if (!hasPort && path.isEmpty()) "$authority:$defaultPort" else authority
            "http://$authorityWithPort$path"
        } else {
            "https://$raw"
        }
        return withScheme.trimEnd('/')
    }

    fun isLocalHost(host: String): Boolean {
        val h = host.lowercase().removePrefix("[").removeSuffix("]")
        return h == "localhost" ||
            LOCAL_SUFFIXES.any { h.endsWith(it) } ||
            IPV4.matches(h) ||
            h.contains(':') // IPv6 literal
    }

    private fun hostOf(authority: String): String = when {
        authority.startsWith("[") -> authority.substringBefore(']').removePrefix("[")
        else -> authority.substringBefore(':')
    }

    /** `::1` or `fe80::1` without brackets: colons are part of the address, not a port. */
    private fun isBareIpv6(authority: String): Boolean =
        !authority.startsWith("[") && authority.count { it == ':' } >= 2
}
