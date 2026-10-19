package app.polyphony.provider.subsonic.internal

import java.security.MessageDigest
import java.security.SecureRandom

internal fun ByteArray.toHex(): String = joinToString("") { "%02x".format(it) }

internal fun md5Hex(input: String): String =
    MessageDigest.getInstance("MD5").digest(input.toByteArray(Charsets.UTF_8)).toHex()

internal fun sha256Hex(input: String): String =
    MessageDigest.getInstance("SHA-256").digest(input.toByteArray(Charsets.UTF_8)).toHex()

internal fun randomSalt(length: Int = 16): String {
    val bytes = ByteArray(length / 2 + 1)
    SecureRandom().nextBytes(bytes)
    return bytes.toHex().take(length)
}
