package app.polyphony.ui.shell

import androidx.annotation.StringRes
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Home
import androidx.compose.material.icons.outlined.LibraryMusic
import androidx.compose.material.icons.outlined.Search
import androidx.compose.material.icons.rounded.Home
import androidx.compose.material.icons.rounded.LibraryMusic
import androidx.compose.material.icons.rounded.Search
import androidx.compose.material3.Icon
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.stringResource
import androidx.navigation.NavDestination
import androidx.navigation.NavDestination.Companion.hasRoute
import app.polyphony.R
import app.polyphony.navigation.AlbumListRoute
import app.polyphony.navigation.AlbumRoute
import app.polyphony.navigation.ArtistRoute
import app.polyphony.navigation.ConnectServerRoute
import app.polyphony.navigation.FavoritesRoute
import app.polyphony.navigation.GenreRoute
import app.polyphony.navigation.HomeRoute
import app.polyphony.navigation.LibraryRoute
import app.polyphony.navigation.OnboardingRoute
import app.polyphony.navigation.PlaylistRoute
import app.polyphony.navigation.PolyphonyNavigator
import app.polyphony.navigation.Route
import app.polyphony.navigation.SearchRoute
import kotlin.reflect.KClass

private data class Tab(
    val route: Route,
    val routeClass: KClass<out Route>,
    @StringRes val label: Int,
    val icon: ImageVector,
    val selectedIcon: ImageVector,
)

private val Tabs = listOf(
    Tab(HomeRoute, HomeRoute::class, R.string.tab_home, Icons.Outlined.Home, Icons.Rounded.Home),
    Tab(LibraryRoute(), LibraryRoute::class, R.string.tab_library, Icons.Outlined.LibraryMusic, Icons.Rounded.LibraryMusic),
    Tab(SearchRoute, SearchRoute::class, R.string.tab_search, Icons.Outlined.Search, Icons.Rounded.Search),
)

@Composable
fun PolyphonyBottomBar(
    navigator: PolyphonyNavigator,
    destination: NavDestination?,
    modifier: Modifier = Modifier,
) {
    NavigationBar(modifier = modifier) {
        Tabs.forEach { tab ->
            val selected = destination?.hasRoute(tab.routeClass) == true
            NavigationBarItem(
                selected = selected,
                onClick = { navigator.switchTab(tab.route) },
                icon = { Icon(if (selected) tab.selectedIcon else tab.icon, contentDescription = null) },
                label = { Text(stringResource(tab.label)) },
                alwaysShowLabel = true,
            )
        }
    }
}

/** Destinations that show the bottom navigation bar. */
internal fun NavDestination.isBrowseRoute(): Boolean =
    hasRoute<HomeRoute>() || hasRoute<LibraryRoute>() || hasRoute<SearchRoute>() ||
        hasRoute<AlbumRoute>() || hasRoute<ArtistRoute>() || hasRoute<PlaylistRoute>() ||
        hasRoute<GenreRoute>() || hasRoute<FavoritesRoute>() || hasRoute<AlbumListRoute>()

/** Destinations where no player chrome at all is shown. */
internal fun NavDestination.isOnboardingRoute(): Boolean =
    hasRoute<OnboardingRoute>() || hasRoute<ConnectServerRoute>()
