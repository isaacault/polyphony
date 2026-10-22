package app.polyphony.navigation

import kotlinx.serialization.Serializable

/** Type-safe navigation routes. Keep arguments primitive so they serialise cleanly. */
@Serializable
sealed interface Route

// ---- Top-level tabs ----
@Serializable data object HomeRoute : Route
@Serializable data class LibraryRoute(val tab: String? = null) : Route
@Serializable data object SearchRoute : Route

// ---- Browse details ----
@Serializable data class AlbumRoute(val albumId: String) : Route
@Serializable data class ArtistRoute(val artistId: String) : Route

/** An artist with nothing in the library: their discography from a continuo server's catalogue. */
@Serializable data class CatalogArtistRoute(val musicBrainzId: String, val name: String) : Route
@Serializable data class PlaylistRoute(val playlistId: String) : Route
@Serializable data class GenreRoute(val genre: String) : Route
@Serializable data object FavoritesRoute : Route
/** "See all" lists from the Home screen; [kind] is an [AlbumListKind] name. */
@Serializable data class AlbumListRoute(val kind: String) : Route
@Serializable data object DownloadsRoute : Route
/** continuo requests (#117); [focusId] scrolls to one, e.g. from a "needs a look" notification. */
@Serializable data class RequestsRoute(val focusId: Long? = null) : Route
/** continuo's notification inbox (#118) for the active server. */
@Serializable data object InboxRoute : Route

enum class AlbumListKind { RECENTLY_ADDED, RECENTLY_PLAYED, MOST_PLAYED, RANDOM, STARRED }

// ---- Settings ----
@Serializable data object SettingsRoute : Route
@Serializable data object ServersSettingsRoute : Route
@Serializable data class ServerEditRoute(val serverId: String? = null) : Route
/** A continuo-linked server's account (#119): its signed-in devices. */
@Serializable data class ContinuoDevicesRoute(val serverId: String) : Route
@Serializable data object AppearanceSettingsRoute : Route
@Serializable data object PlaybackSettingsRoute : Route
@Serializable data object StreamingSettingsRoute : Route
@Serializable data class TranscodeProfileEditRoute(val profileId: String? = null) : Route
@Serializable data object RemoteDevicesSettingsRoute : Route
@Serializable data object LibrarySettingsRoute : Route
@Serializable data object DownloadsSettingsRoute : Route
@Serializable data class DownloadRuleEditRoute(val ruleId: String? = null) : Route
@Serializable data object BackupSettingsRoute : Route
@Serializable data object AboutRoute : Route

// ---- Onboarding ----
@Serializable data object OnboardingRoute : Route
@Serializable data class ConnectServerRoute(val serverType: String? = null) : Route

/** Routes that show the bottom navigation bar and mini player. */
val TopLevelRoutes: List<Route> = listOf(HomeRoute, LibraryRoute(), SearchRoute)
