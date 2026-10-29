package app.polyphony.core.domain.model

import kotlinx.serialization.Serializable

/**
 * How you mark what you like: a favourite heart, or a 1–5 star rating. The app uses one at a time
 * and leaves the other untouched on the server, so switching back loses nothing.
 */
@Serializable
enum class FeedbackMode { FAVOURITES, RATINGS }

/**
 * The feedback system in effect. [mode] is already resolved against the server: RATINGS only when
 * it was chosen and [ratingsSupported] (Jellyfin has like/dislike, not stars).
 */
data class Feedback(
    val mode: FeedbackMode = FeedbackMode.FAVOURITES,
    /** In Ratings mode, the least a song needs to count as Top rated (Favourites screen, the favourites download rule). */
    val topRatedMinimum: Int = DEFAULT_TOP_RATED,
    val ratingsSupported: Boolean = true,
) {
    val usesRatings: Boolean get() = mode == FeedbackMode.RATINGS

    /** What "something you like" selects: favourites, or items rated at least [topRatedMinimum]. */
    val liked: LibraryFilter
        get() = if (usesRatings) LibraryFilter(minRating = topRatedMinimum) else LibraryFilter(favouritesOnly = true)

    /** [fields] without the one that belongs to the system not in use. */
    fun filterFields(fields: Set<FilterField>): Set<FilterField> =
        fields - if (usesRatings) FilterField.FAVOURITES else FilterField.RATING

    /** A saved filter with the unused system's narrowing dropped, so it cannot hide items invisibly. */
    fun applyTo(filter: LibraryFilter): LibraryFilter =
        if (usesRatings) filter.copy(favouritesOnly = false) else filter.copy(minRating = 0)

    companion object {
        const val DEFAULT_TOP_RATED = 4
    }
}
