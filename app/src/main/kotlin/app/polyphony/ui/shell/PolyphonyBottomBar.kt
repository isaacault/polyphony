package app.polyphony.ui.shell

import androidx.annotation.StringRes
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Home
import androidx.compose.material.icons.outlined.LibraryMusic
import androidx.compose.material.icons.outlined.Search
import androidx.compose.material.icons.rounded.Home
import androidx.compose.material.icons.rounded.LibraryMusic
import androidx.compose.material.icons.rounded.Search
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import app.polyphony.core.ui.theme.Dimens
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

/**
 * Compact bottom bar. Material 3's NavigationBar is locked to 80 dp; this condensed version
 * (icon over a small label, no pill) keeps the chrome low like Spotify's.
 */
@Composable
fun PolyphonyBottomBar(
    navigator: PolyphonyNavigator,
    destination: NavDestination?,
    modifier: Modifier = Modifier,
) {
    Surface(
        modifier = modifier.fillMaxWidth(),
        color = MaterialTheme.colorScheme.surfaceContainer,
        tonalElevation = 2.dp,
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .navigationBarsPadding()
                .height(Dimens.bottomBarHeight),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Tabs.forEach { tab ->
                val selected = destination?.hasRoute(tab.routeClass) == true
                val tint = if (selected) MaterialTheme.colorScheme.onSurface else MaterialTheme.colorScheme.onSurfaceVariant
                Column(
                    modifier = Modifier
                        .weight(1f)
                        .fillMaxHeight()
                        .clickable(
                            interactionSource = remember { MutableInteractionSource() },
                            indication = null,
                            onClick = { navigator.switchTab(tab.route) },
                        ),
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.Center,
                ) {
                    Icon(
                        imageVector = if (selected) tab.selectedIcon else tab.icon,
                        contentDescription = null,
                        tint = tint,
                        modifier = Modifier.size(24.dp),
                    )
                    Spacer(Modifier.height(2.dp))
                    Text(
                        text = stringResource(tab.label),
                        style = MaterialTheme.typography.labelSmall,
                        color = tint,
                    )
                }
            }
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
