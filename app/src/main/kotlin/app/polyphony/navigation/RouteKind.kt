package app.polyphony.navigation

import androidx.navigation.NavDestination
import androidx.navigation.NavDestination.Companion.hasRoute

/** Coarse classification of destinations, used to pick navigation transitions. */
enum class RouteKind { TOP_LEVEL, DETAIL, SETTINGS, ONBOARDING }

fun NavDestination.routeKind(): RouteKind = when {
    hasRoute<HomeRoute>() || hasRoute<LibraryRoute>() || hasRoute<SearchRoute>() -> RouteKind.TOP_LEVEL
    hasRoute<AlbumRoute>() || hasRoute<ArtistRoute>() || hasRoute<PlaylistRoute>() ||
        hasRoute<GenreRoute>() || hasRoute<FavoritesRoute>() || hasRoute<AlbumListRoute>() ||
        hasRoute<DownloadsRoute>() -> RouteKind.DETAIL
    hasRoute<OnboardingRoute>() || hasRoute<ConnectServerRoute>() -> RouteKind.ONBOARDING
    else -> RouteKind.SETTINGS
}
