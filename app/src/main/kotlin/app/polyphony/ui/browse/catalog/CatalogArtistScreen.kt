package app.polyphony.ui.browse.catalog

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.rounded.Album
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.polyphony.R
import app.polyphony.core.domain.model.Availability
import app.polyphony.core.ui.components.ErrorState
import app.polyphony.core.ui.components.MediaRow
import app.polyphony.core.ui.components.SectionHeader
import app.polyphony.core.ui.components.listContentPadding
import app.polyphony.navigation.LocalNavigator
import app.polyphony.ui.shell.LocalSnackbarHostState

/** An artist with nothing in the library (#144): their discography, grouped by release type, with Request. */
@Composable
fun CatalogArtistScreen(viewModel: CatalogArtistViewModel = hiltViewModel()) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val navigator = LocalNavigator.current
    val snackbar = LocalSnackbarHostState.current
    val context = LocalContext.current
    val scrollBehavior = TopAppBarDefaults.pinnedScrollBehavior()

    LaunchedEffect(viewModel) {
        viewModel.events.collect { m ->
            snackbar.showSnackbar(if (m.arg == null) context.getString(m.text) else context.getString(m.text, m.arg))
        }
    }

    Scaffold(
        modifier = Modifier.nestedScroll(scrollBehavior.nestedScrollConnection),
        contentWindowInsets = WindowInsets(0, 0, 0, 0),
        topBar = {
            Column {
                TopAppBar(
                    title = { Text(state.name, maxLines = 1, overflow = TextOverflow.Ellipsis) },
                    navigationIcon = {
                        IconButton(onClick = { navigator.back() }) {
                            Icon(Icons.AutoMirrored.Rounded.ArrowBack, contentDescription = stringResource(R.string.browse_action_back))
                        }
                    },
                    scrollBehavior = scrollBehavior,
                )
                if (state.loading) LinearProgressIndicator(Modifier.fillMaxWidth().height(2.dp))
            }
        },
    ) { padding ->
        when {
            state.failed -> Column(Modifier.fillMaxSize().padding(padding).padding(top = 96.dp)) {
                ErrorState(message = stringResource(R.string.browse_catalog_failed), onRetry = viewModel::load)
            }
            else -> LazyColumn(
                modifier = Modifier.fillMaxSize().padding(padding),
                contentPadding = listContentPadding(top = 4.dp),
            ) {
                if (state.loading) item(key = "loading") { CatalogNote(R.string.browse_catalog_loading) }
                if (state.isEmpty) item(key = "empty") { CatalogNote(R.string.browse_catalog_empty) }
                // Releases continuo matched to the library under another artist name: yours, so first.
                if (state.inLibrary.isNotEmpty()) {
                    item(key = "h-library") { SectionHeader(title = stringResource(R.string.browse_catalog_in_library)) }
                    items(state.inLibrary, key = { "l-" + it.key }, contentType = { "album" }) { album ->
                        val libraryId = when (val a = album.availability) {
                            is Availability.InLibrary -> a.libraryAlbumId
                            is Availability.Partial -> a.libraryAlbumId
                            else -> null
                        }
                        MediaRow(
                            title = album.title,
                            subtitle = album.year?.toString(),
                            artworkUrl = album.artworkUrl,
                            onClick = { libraryId?.let(navigator::openAlbum) },
                            placeholder = Icons.Rounded.Album,
                        )
                    }
                }
                discographyItems(state.groups, state.requesting, onRequest = viewModel::request) { type ->
                    SectionHeader(title = stringResource(releaseTypeLabel(type)))
                }
            }
        }
    }
}
