package app.polyphony.core.data.search

/**
 * Turns free text into a safe FTS4 MATCH expression (prefix match on every token) and a LIKE
 * pattern used as a fallback. Only letters and digits survive, so no FTS operators can leak in.
 */
object FtsQuery {
    private val tokenRegex = Regex("[\\p{L}\\p{N}]+")
    private const val MAX_TOKENS = 8

    /** e.g. "the  beat-les!" → `the* beat* les*`; null when there is nothing searchable. */
    fun match(raw: String): String? {
        // Lower-casing also neutralises the FTS4 operators (OR/AND/NOT/NEAR are upper-case only).
        val tokens = tokenRegex.findAll(raw).map { it.value.lowercase() }.take(MAX_TOKENS).toList()
        if (tokens.isEmpty()) return null
        return tokens.joinToString(" ") { "$it*" }
    }

    /** `%escaped%` with `\` as the ESCAPE character. */
    fun likePattern(raw: String): String {
        val escaped = raw.trim()
            .replace("\\", "\\\\")
            .replace("%", "\\%")
            .replace("_", "\\_")
        return "%$escaped%"
    }
}
