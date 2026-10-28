package app.polyphony.core.domain.repository

import app.polyphony.core.domain.model.AlbumSort
import app.polyphony.core.domain.model.ArtistSort
import app.polyphony.core.domain.model.LibraryFilter
import app.polyphony.core.domain.model.TrackSort
import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Test

/** Settings written by older builds must still decode, with new fields taking their defaults. */
class AppSettingsCompatTest {

    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }

    @Test
    fun `swipe actions default to add-to-queue and nothing`() {
        val decoded = json.decodeFromString(AppSettings.serializer(), """{"appearance":{"themeMode":"DARK"}}""")
        assertEquals(ThemeMode.DARK, decoded.appearance.themeMode)
        assertEquals(TrackSwipeAction.ADD_TO_QUEUE, decoded.appearance.swipeLeftToRight)
        assertEquals(TrackSwipeAction.NONE, decoded.appearance.swipeRightToLeft)
    }

    @Test
    fun `swipe actions round-trip`() {
        val settings = AppSettings(
            appearance = AppearanceSettings(
                swipeLeftToRight = TrackSwipeAction.TOGGLE_FAVORITE,
                swipeRightToLeft = TrackSwipeAction.PLAY_NEXT,
            ),
        )
        val decoded = json.decodeFromString(AppSettings.serializer(), json.encodeToString(AppSettings.serializer(), settings))
        assertEquals(settings.appearance, decoded.appearance)
    }

    @Test
    fun `library sorts default to name order for settings written before they existed`() {
        val decoded = json.decodeFromString(AppSettings.serializer(), """{"appearance":{"themeMode":"DARK"}}""")
        assertEquals(AlbumSort.NAME, decoded.appearance.albumSort)
        assertEquals(true, decoded.appearance.albumAscending)
        assertEquals(ArtistSort.NAME, decoded.appearance.artistSort)
        assertEquals(TrackSort.TITLE, decoded.appearance.trackSort)
    }

    @Test
    fun `library sorts round-trip`() {
        val settings = AppSettings(appearance = AppearanceSettings(albumSort = AlbumSort.ARTIST, albumAscending = false, trackSort = TrackSort.ALBUM))
        val decoded = json.decodeFromString(AppSettings.serializer(), json.encodeToString(AppSettings.serializer(), settings))
        assertEquals(AlbumSort.ARTIST, decoded.appearance.albumSort)
        assertEquals(false, decoded.appearance.albumAscending)
        assertEquals(TrackSort.ALBUM, decoded.appearance.trackSort)
    }

    @Test
    fun `library filters default to none and round-trip per tab`() {
        val legacy = json.decodeFromString(AppSettings.serializer(), """{"appearance":{"themeMode":"DARK"}}""")
        assertEquals(LibraryFilter.NONE, legacy.appearance.trackFilter)
        assertEquals(LibraryFilter.NONE, legacy.appearance.albumFilter)
        val songs = LibraryFilter(minRating = 3, downloadedOnly = true, genre = "Jazz", yearFrom = 1990, yearTo = 1999)
        val settings = AppSettings(appearance = AppearanceSettings(trackFilter = songs, artistFilter = LibraryFilter(favouritesOnly = true)))
        val decoded = json.decodeFromString(AppSettings.serializer(), json.encodeToString(AppSettings.serializer(), settings))
        assertEquals(songs, decoded.appearance.trackFilter)
        assertEquals(LibraryFilter(favouritesOnly = true), decoded.appearance.artistFilter)
        assertEquals(LibraryFilter.NONE, decoded.appearance.albumFilter)
    }

    @Test
    fun `library opens on albums by default and remembers another choice`() {
        val legacy = json.decodeFromString(AppSettings.serializer(), """{"appearance":{"themeMode":"DARK"}}""")
        assertEquals(LibrarySection.ALBUMS, legacy.appearance.libraryDefaultTab)
        val settings = AppSettings(appearance = AppearanceSettings(libraryDefaultTab = LibrarySection.SONGS))
        val decoded = json.decodeFromString(AppSettings.serializer(), json.encodeToString(AppSettings.serializer(), settings))
        assertEquals(LibrarySection.SONGS, decoded.appearance.libraryDefaultTab)
    }

    @Test
    fun `explore shows to someone whose tabs were saved before it, until they choose without it`() {
        val saved = json.decodeFromString(AppSettings.serializer(),
            """{"appearance":{"libraryTabs":["ALBUMS","ARTISTS","SONGS","PLAYLISTS","GENRES"]}}""")
        assertEquals(LibrarySection.EXPLORE, saved.appearance.shownLibraryTabs.first())
        assertEquals("the default order until arranged", LibrarySection.ARTISTS, saved.appearance.shownLibraryTabs[1])
        val chosen = saved.appearance.copy(libraryTabs = listOf(LibrarySection.ALBUMS), libraryTabsKnown = LibrarySection.entries)
        assertEquals(listOf(LibrarySection.ALBUMS), chosen.shownLibraryTabs)
        val arranged = saved.appearance.copy(libraryTabs = listOf(LibrarySection.SONGS, LibrarySection.ALBUMS),
                                             libraryTabsKnown = LibrarySection.entries, libraryTabsArranged = true)
        assertEquals("an arranged order stands", listOf(LibrarySection.SONGS, LibrarySection.ALBUMS), arranged.shownLibraryTabs)
        assertEquals(LibrarySection.entries, AppearanceSettings().shownLibraryTabs)
    }

    @Test
    fun `the removed screen transition setting is ignored`() {
        val decoded = json.decodeFromString(AppSettings.serializer(), """{"appearance":{"navTransition":"SLIDE","pureBlack":true}}""")
        assertEquals(true, decoded.appearance.pureBlack)
    }
}
