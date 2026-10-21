package app.polyphony.ui.browse

import androidx.navigation.NavGraphBuilder
import androidx.navigation.compose.composable
import androidx.navigation.toRoute
import app.polyphony.navigation.AlbumListRoute
import app.polyphony.navigation.AlbumRoute
import app.polyphony.navigation.ArtistRoute
import app.polyphony.navigation.DownloadsRoute
import app.polyphony.navigation.FavoritesRoute
import app.polyphony.navigation.GenreRoute
import app.polyphony.navigation.HomeRoute
import app.polyphony.navigation.LibraryRoute
import app.polyphony.navigation.PlaylistRoute
import app.polyphony.navigation.PolyphonyNavigator
import app.polyphony.navigation.RequestsRoute
import app.polyphony.navigation.SearchRoute
import app.polyphony.ui.browse.album.AlbumScreen
import app.polyphony.ui.browse.albumlist.AlbumListScreen
import app.polyphony.ui.browse.artist.ArtistScreen
import app.polyphony.ui.browse.favorites.FavoritesScreen
import app.polyphony.ui.browse.genre.GenreScreen
import app.polyphony.ui.browse.home.HomeScreen
import app.polyphony.ui.browse.library.LibraryScreen
import app.polyphony.ui.browse.playlist.PlaylistScreen
import app.polyphony.ui.browse.search.SearchScreen
import app.polyphony.ui.downloads.DownloadsScreen
import app.polyphony.ui.requests.RequestsScreen

/** Browse destinations: tabs plus every detail screen. Screens read [app.polyphony.navigation.LocalNavigator]. */
fun NavGraphBuilder.browseGraph(navigator: PolyphonyNavigator) {
    composable<HomeRoute> { HomeScreen() }
    composable<LibraryRoute> { entry -> LibraryScreen(initialTab = entry.toRoute<LibraryRoute>().tab) }
    composable<SearchRoute> { SearchScreen() }
    composable<AlbumRoute> { AlbumScreen() }
    composable<ArtistRoute> { ArtistScreen() }
    composable<PlaylistRoute> { PlaylistScreen() }
    composable<GenreRoute> { GenreScreen() }
    composable<FavoritesRoute> { FavoritesScreen() }
    composable<AlbumListRoute> { AlbumListScreen() }
    composable<DownloadsRoute> { DownloadsScreen() }
    composable<RequestsRoute> { RequestsScreen() }
}
