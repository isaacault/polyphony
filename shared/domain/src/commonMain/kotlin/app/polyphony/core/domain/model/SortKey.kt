package app.polyphony.core.domain.model

/**
 * The text an A–Z sort compares: the server's sort name when it sent a real one, else the display
 * name, without a leading "The", "An" or "A". "The Strokes" files under S and "A Tribe Called
 * Quest" under T, even when the server (Navidrome by default) sends no sort name at all.
 *
 * `SortQueries.sortable` in core:data is the SQL twin that orders the lists; the alphabet rail
 * buckets rows with this one, so the two must strip the same prefixes.
 */
fun sortKey(name: String?, sortName: String? = null): String {
    val base = sortName?.trim()?.takeIf { it.isNotEmpty() } ?: name?.trim().orEmpty()
    for (article in LEADING_ARTICLES) {
        if (base.length > article.length && base.startsWith(article, ignoreCase = true)) return base.substring(article.length)
    }
    return base
}

/** Longest first, so "An " is not read as "A" + "n ". Each ends in the space that separates it from the name. */
val LEADING_ARTICLES: List<String> = listOf("The ", "An ", "A ")
