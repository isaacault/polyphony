package app.polyphony.navigation

import androidx.compose.runtime.staticCompositionLocalOf
import androidx.navigation.NavHostController
import androidx.navigation.NavOptionsBuilder
import androidx.navigation.navOptions
import app.polyphony.core.domain.model.ServerType

/**
 * Thin, typed facade over [NavHostController] so screens never build routes by hand.
 * Obtain it with [LocalNavigator].
 */
class PolyphonyNavigator(val navController: NavHostController) {

    fun back(): Boolean = navController.popBackStack()

    /** Switch between bottom-bar tabs, preserving each tab's back stack. */
    fun switchTab(route: Route) {
        navController.navigate(route) {
            popUpTo(navController.graph.startDestinationId) { saveState = true }
            launchSingleTop = true
            restoreState = true
        }
    }

    fun openHome() = switchTab(HomeRoute)
    fun openLibrary(tab: String? = null) = navController.navigate(LibraryRoute(tab), singleTop)
    fun openSearch() = switchTab(SearchRoute)

    fun openAlbum(albumId: String) = navController.navigate(AlbumRoute(albumId), singleTop)
    fun openArtist(artistId: String) = navController.navigate(ArtistRoute(artistId), singleTop)
    fun openPlaylist(playlistId: String) = navController.navigate(PlaylistRoute(playlistId), singleTop)
    fun openGenre(genre: String) = navController.navigate(GenreRoute(genre), singleTop)
    fun openFavorites() = navController.navigate(FavoritesRoute, singleTop)
    fun openAlbumList(kind: AlbumListKind) = navController.navigate(AlbumListRoute(kind.name), singleTop)
    fun openDownloads() = navController.navigate(DownloadsRoute, singleTop)

    fun openSettings() = navController.navigate(SettingsRoute, singleTop)
    fun openServersSettings() = navController.navigate(ServersSettingsRoute, singleTop)
    fun openServerEdit(serverId: String?) = navController.navigate(ServerEditRoute(serverId), singleTop)
    fun openAppearanceSettings() = navController.navigate(AppearanceSettingsRoute, singleTop)
    fun openPlaybackSettings() = navController.navigate(PlaybackSettingsRoute, singleTop)
    fun openStreamingSettings() = navController.navigate(StreamingSettingsRoute, singleTop)
    fun openTranscodeProfile(profileId: String?) = navController.navigate(TranscodeProfileEditRoute(profileId), singleTop)
    fun openRemoteDevicesSettings() = navController.navigate(RemoteDevicesSettingsRoute, singleTop)
    fun openLibrarySettings() = navController.navigate(LibrarySettingsRoute, singleTop)
    fun openDownloadsSettings() = navController.navigate(DownloadsSettingsRoute, singleTop)
    fun openDownloadRule(ruleId: String?) = navController.navigate(DownloadRuleEditRoute(ruleId), singleTop)
    fun openAbout() = navController.navigate(AboutRoute, singleTop)

    fun openOnboarding() = navController.navigate(OnboardingRoute) {
        popUpTo(0) { inclusive = true }
    }
    fun openConnectServer(type: ServerType?) = navController.navigate(ConnectServerRoute(type?.name), singleTop)

    /** After the first server is added: drop onboarding and land on Home. */
    fun finishOnboarding() = navController.navigate(HomeRoute) {
        popUpTo(0) { inclusive = true }
    }

    private val singleTop = navOptions { launchSingleTop = true }
}

val LocalNavigator = staticCompositionLocalOf<PolyphonyNavigator> { error("PolyphonyNavigator not provided") }

internal fun NavOptionsBuilder.singleTop() { launchSingleTop = true }
