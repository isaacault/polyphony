package app.polyphony.core.domain.model

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class FeedbackTest {

    private val favourites = Feedback(FeedbackMode.FAVOURITES)
    private val ratings = Feedback(FeedbackMode.RATINGS, topRatedMinimum = 4)

    @Test
    fun `liked means favourites, or top rated in ratings mode`() {
        assertEquals(LibraryFilter(favouritesOnly = true), favourites.liked)
        assertEquals(LibraryFilter(minRating = 4), ratings.liked)
        assertEquals(LibraryFilter(minRating = 5), ratings.copy(topRatedMinimum = 5).liked)
    }

    @Test
    fun `each mode hides the other system's filter`() {
        val all = FilterField.entries.toSet()
        assertFalse(FilterField.RATING in favourites.filterFields(all))
        assertTrue(FilterField.FAVOURITES in favourites.filterFields(all))
        assertFalse(FilterField.FAVOURITES in ratings.filterFields(all))
        assertTrue(FilterField.RATING in ratings.filterFields(all))
    }

    @Test
    fun `a saved filter loses the unused system's narrowing`() {
        val saved = LibraryFilter(favouritesOnly = true, minRating = 3, genre = "Jazz")
        assertEquals(LibraryFilter(favouritesOnly = true, genre = "Jazz"), favourites.applyTo(saved))
        assertEquals(LibraryFilter(minRating = 3, genre = "Jazz"), ratings.applyTo(saved))
    }
}
