package app.polyphony.ui.browse.search

import app.polyphony.core.domain.model.LibraryFilter
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SearchUiStateTest {

    private val continuo = SearchUiState(query = "nujabes", scopesAvailable = true, scope = SearchScope.ALL)

    @Test
    fun `a plain server offers the server search until it ran`() {
        val plain = SearchUiState(query = "nujabes")
        assertTrue(plain.canSearchFurther)
        assertFalse(plain.copy(remoteSearched = true).canSearchFurther)
    }

    @Test
    fun `a continuo server still offers Everything after the server search`() {
        assertTrue(continuo.copy(remoteSearched = true).canSearchFurther)
        assertFalse(continuo.copy(remoteSearched = true, everything = EverythingUi(searched = true)).canSearchFurther)
        assertFalse(continuo.copy(remoteSearched = true, everything = EverythingUi(searching = true)).canSearchFurther)
    }

    @Test
    fun `a failed Everything search can be retried`() {
        assertTrue(continuo.copy(remoteSearched = true, everything = EverythingUi(failed = true)).canSearchFurther)
    }

    @Test
    fun `Library scope and active filters only need the server search`() {
        assertFalse(continuo.copy(scope = SearchScope.LIBRARY, remoteSearched = true).canSearchFurther)
        assertFalse(continuo.copy(filter = LibraryFilter(minRating = 3), remoteSearched = true).canSearchFurther)
    }

    @Test
    fun `downloaded results have nothing further to search`() {
        assertFalse(continuo.copy(scope = SearchScope.DOWNLOADED, filter = LibraryFilter(downloadedOnly = true)).canSearchFurther)
    }
}
