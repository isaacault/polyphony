package app.polyphony.core.playback.stream

/**
 * A live transcode has no length: Navidrome sends it chunked while ffmpeg runs, and Media3 then
 * treats the song as a live stream (no seeking; a dropped connection restarts it from 0). Once the
 * transcode is finished, Navidrome serves it from its cache with an exact length and byte ranges.
 * A one-byte range request tells the two apart: 206 with `Content-Range: bytes 0-0/<total>`
 * means finished, and the total is the length to give the player.
 */
internal object TranscodeLength {
    private val CONTENT_RANGE = Regex("""^\s*bytes\s+\d+-\d+/(\d+)\s*$""", RegexOption.IGNORE_CASE)

    /** The total of a `Content-Range` header, or null when it is absent, malformed or unknown (`*`). */
    fun totalFromContentRange(header: String?): Long? =
        header?.let { CONTENT_RANGE.find(it)?.groupValues?.get(1)?.toLongOrNull() }?.takeIf { it > 0 }

    /** The cache key of a transcode whose exact length is known; distinct from the live one's. */
    fun keyWithLength(baseKey: String, length: Long): String = "$baseKey|L$length"

    fun lengthKeyPrefix(baseKey: String): String = "$baseKey|L"

    fun hasKnownLength(key: String?): Boolean = key != null && Regex("""\|L\d+$""").containsMatchIn(key)
}
