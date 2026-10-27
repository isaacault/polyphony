package app.polyphony.core.domain.repository

import app.polyphony.core.domain.model.AlbumSort
import app.polyphony.core.domain.model.ArtistSort
import app.polyphony.core.domain.model.Feedback
import app.polyphony.core.domain.model.FeedbackMode
import app.polyphony.core.domain.model.LibraryFilter
import app.polyphony.core.domain.model.TrackSort
import app.polyphony.core.domain.transcode.TranscodePolicy
import kotlinx.coroutines.flow.Flow
import kotlinx.serialization.Serializable

interface SettingsRepository {
    val settings: Flow<AppSettings>
    suspend fun current(): AppSettings
    suspend fun update(transform: (AppSettings) -> AppSettings)
}

@Serializable
data class AppSettings(
    val onboardingComplete: Boolean = false,
    val appearance: AppearanceSettings = AppearanceSettings(),
    val playback: PlaybackSettings = PlaybackSettings(),
    val transcode: TranscodePolicy = TranscodePolicy(),
    val library: LibrarySettings = LibrarySettings(),
    val downloads: DownloadSettings = DownloadSettings(),
)

@Serializable
enum class ThemeMode { SYSTEM, LIGHT, DARK }

@Serializable
enum class PlayerArtworkStyle { ROUNDED, SQUARE, CIRCLE }

/** How a song's star rating shows on its row: ★4, or all five stars with the rating filled. */
@Serializable
enum class RatingStyle { COMPACT, STARS }

/** How a library collection is laid out. */
@Serializable
enum class LibraryLayout { LIST, GRID }

/** Library tabs, in the order the user wants them. */
@Serializable
enum class LibrarySection { EXPLORE, ARTISTS, ALBUMS, SONGS, PLAYLISTS, GENRES }

/** The Library tabs there were before Explore: what a settings file without [AppearanceSettings.libraryTabsKnown] has seen. */
private val TABS_BEFORE_EXPLORE = listOf(LibrarySection.ARTISTS, LibrarySection.ALBUMS, LibrarySection.SONGS, LibrarySection.PLAYLISTS, LibrarySection.GENRES)

/** What a horizontal swipe on a song row does once it passes the threshold and is released. */
@Serializable
enum class TrackSwipeAction { NONE, ADD_TO_QUEUE, PLAY_NEXT, ADD_TO_PLAYLIST, TOGGLE_FAVORITE, DOWNLOAD, SONG_INFO }

@Serializable
data class AppearanceSettings(
    val themeMode: ThemeMode = ThemeMode.SYSTEM,
    /** Material You colours from the wallpaper (Android 12+). */
    val dynamicColor: Boolean = true,
    /** Seed colour (ARGB) used when dynamic colour is off or unavailable. */
    val accentSeedArgb: Long = 0xFF6750A4,
    /** Pure-black dark surfaces for OLED screens. */
    val pureBlack: Boolean = false,
    /** Tint the Now Playing screen with colours extracted from the artwork. */
    val artworkColorsInPlayer: Boolean = true,
    /** On a metered/cellular connection, request smaller cover art to save data. */
    val reduceImageDataOnMobile: Boolean = true,
    /** 0 = automatic based on width. */
    val gridColumns: Int = 0,
    val playerArtworkStyle: PlayerArtworkStyle = PlayerArtworkStyle.ROUNDED,
    val showTrackNumbers: Boolean = true,
    val ratingStyle: RatingStyle = RatingStyle.COMPACT,
    val blurredPlayerBackground: Boolean = true,
    /** Per-collection layout in the Library and "see all" lists. */
    val artistsLayout: LibraryLayout = LibraryLayout.LIST,
    val albumsLayout: LibraryLayout = LibraryLayout.LIST,
    val playlistsLayout: LibraryLayout = LibraryLayout.LIST,
    /** Enabled Library tabs in display order; an empty list means all, in default order. */
    val libraryTabs: List<LibrarySection> = LibrarySection.entries.toList(),
    /**
     * The tabs there were when [libraryTabs] was last chosen: one added since (Explore) shows, first,
     * until the user chooses again, rather than staying hidden behind a list saved before it existed.
     */
    val libraryTabsKnown: List<LibrarySection> = TABS_BEFORE_EXPLORE,
    /** Tab the Library opens on when nothing asks for a specific one; falls back to the first enabled tab. */
    val libraryDefaultTab: LibrarySection = LibrarySection.ALBUMS,
    /** Swipe gestures on song rows, by physical direction (not layout direction). */
    val swipeLeftToRight: TrackSwipeAction = TrackSwipeAction.ADD_TO_QUEUE,
    val swipeRightToLeft: TrackSwipeAction = TrackSwipeAction.NONE,
    /** Library list sorts. Persisted here so a cold start, a reboot or an update keeps them. */
    val artistSort: ArtistSort = ArtistSort.NAME,
    val albumSort: AlbumSort = AlbumSort.NAME,
    val albumAscending: Boolean = true,
    val trackSort: TrackSort = TrackSort.TITLE,
    val trackAscending: Boolean = true,
    /** Library list filters, one per tab, kept like the sorts. Search starts unfiltered every time. */
    val artistFilter: LibraryFilter = LibraryFilter.NONE,
    val albumFilter: LibraryFilter = LibraryFilter.NONE,
    val trackFilter: LibraryFilter = LibraryFilter.NONE,
    val playlistFilter: LibraryFilter = LibraryFilter.NONE,
) {
    /** The Library tabs to show, in order: the chosen ones, after any added since they were chosen. */
    val shownLibraryTabs: List<LibrarySection>
        get() {
            val chosen = libraryTabs.ifEmpty { LibrarySection.entries }
            return LibrarySection.entries.filter { it !in libraryTabsKnown && it !in chosen } + chosen
        }
}

@Serializable
enum class ReplayGainMode { OFF, TRACK, ALBUM, AUTO }

@Serializable
data class PlaybackSettings(
    val gapless: Boolean = true,
    val crossfadeMs: Int = 0,
    val replayGainMode: ReplayGainMode = ReplayGainMode.OFF,
    val replayGainPreampDb: Float = 0f,
    val replayGainPreventClipping: Boolean = true,
    val skipSilence: Boolean = false,
    val pauseOnDisconnect: Boolean = true,
    val resumeOnConnect: Boolean = false,
    val duckOnTransientFocusLoss: Boolean = true,
    val scrobble: Boolean = true,
    /** Report "played" after this fraction of the track (or 4 minutes, whichever comes first). */
    val scrobbleThreshold: Float = 0.5f,
    val streamCacheSizeMb: Int = 1024,
    val preloadNextTrack: Boolean = true,
    /** Keep the next songs of the queue fully on the phone, so a network drop doesn't stop the music. */
    val prefetchQueue: Boolean = true,
    /** Also on mobile data (fewer songs ahead: each one costs data even if it's skipped). */
    val prefetchOnMobileData: Boolean = true,
    val previousRestartsAfterMs: Long = 3_000,
    val rememberQueue: Boolean = true,
    val hapticFeedback: Boolean = true,
    val playbackSpeed: Float = 1f,
    /** Preferred audio session usage for the equaliser hookup. */
    val externalEqualizer: Boolean = true,
)

@Serializable
data class LibrarySettings(
    val autoSyncHours: Int = 6,
    val syncOnWifiOnly: Boolean = true,
    val syncOnLaunch: Boolean = true,
    val showCompilationsUnderArtists: Boolean = false,
    val artworkCacheSizeMb: Int = 512,
    val randomAlbumSeed: Long = 0L,
    /** Favourites (hearts) or Ratings (stars); see [FeedbackRepository] for the mode in effect. */
    val feedbackMode: FeedbackMode = FeedbackMode.FAVOURITES,
    /** Ratings mode: the least a song needs to be Top rated. */
    val topRatedMinimum: Int = Feedback.DEFAULT_TOP_RATED,
)
