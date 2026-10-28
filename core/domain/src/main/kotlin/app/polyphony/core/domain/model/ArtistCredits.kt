package app.polyphony.core.domain.model

/** Who a track's artist tag names: "Drake feat. Rihanna & Future" is three artists. */
object ArtistCredits {
    // What joins one credited artist to the next in an artist tag.
    private const val JOINER =
        """\s*(?:,|;|/|&|\+|\s+x\s+|\s+(?:feat|ft)\.?\s+|\s+featuring\s+|\s+with\s+|\s+and\s+|\s+vs\.?\s+)\s*"""
    private val joiner = Regex(JOINER, RegexOption.IGNORE_CASE)
    private val endsWithJoiner = Regex("(?:$JOINER)$", RegexOption.IGNORE_CASE)
    private val startsWithJoiner = Regex("^(?:$JOINER)", RegexOption.IGNORE_CASE)

    fun names(tag: String?): List<String> =
        tag.orEmpty().split(joiner).map { it.trim().trim('(', ')') }.filter { it.isNotEmpty() }

    /**
     * Whether [tag] credits [artist] as one of its artists, not just contains the name ("Rihanna
     * Tribute Band" doesn't credit Rihanna). A name with a joiner of its own ("Simon & Garfunkel")
     * counts when the tag holds it whole.
     */
    fun credits(tag: String?, artist: String): Boolean {
        val name = artist.trim()
        if (name.isEmpty() || tag.isNullOrBlank()) return false
        if (names(tag).any { it.equals(name, ignoreCase = true) }) return true
        if (names(name).size < 2) return false
        val at = tag.indexOf(name, ignoreCase = true)
        if (at < 0) return false
        val before = tag.substring(0, at)
        val after = tag.substring(at + name.length)
        return (before.isBlank() || endsWithJoiner.containsMatchIn(before)) &&
            (after.isBlank() || startsWithJoiner.containsMatchIn(after))
    }
}
