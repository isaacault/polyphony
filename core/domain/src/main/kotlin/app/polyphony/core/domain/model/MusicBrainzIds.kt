package app.polyphony.core.domain.model

/**
 * MusicBrainz ids as servers hand them over. Navidrome sends "" for an unmatched artist or album,
 * and a tag can carry several ids ("id1;id2" or "id1/id2"), so callers keep only a real one.
 */
object MusicBrainzIds {
    private val UUID = Regex("[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}")

    /** The first well-formed id in [raw], lower-cased; null when there is none. */
    fun first(raw: String?): String? = raw?.let { UUID.find(it)?.value?.lowercase() }
}
