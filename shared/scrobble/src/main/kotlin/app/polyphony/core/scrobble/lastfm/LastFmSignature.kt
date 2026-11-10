package app.polyphony.core.scrobble.lastfm

import java.security.MessageDigest

/**
 * Last.fm's api_sig (https://www.last.fm/api/authspec, section 8): every parameter but `format` and
 * `callback`, ordered by name, each name followed by its value, then the shared secret, as the
 * lower-case hex MD5 of the UTF-8 bytes.
 */
object LastFmSignature {
    private val UNSIGNED = setOf("format", "callback", "api_sig")

    fun sign(params: Map<String, String>, secret: String): String {
        val text = buildString {
            params.filterKeys { it !in UNSIGNED }.toSortedMap().forEach { (k, v) -> append(k).append(v) }
            append(secret)
        }
        return md5(text)
    }

    fun md5(text: String): String =
        MessageDigest.getInstance("MD5").digest(text.toByteArray(Charsets.UTF_8))
            .joinToString("") { "%02x".format(it.toInt() and 0xff) }
}
