package app.polyphony.ui.requests

import android.text.format.DateUtils
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
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.ListItem
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import app.polyphony.R
import app.polyphony.core.domain.model.RemovedAlbum
import app.polyphony.core.domain.repository.RequestRepository
import app.polyphony.core.domain.repository.ServerRepository
import app.polyphony.core.ui.components.EmptyState
import app.polyphony.core.ui.components.ErrorState
import app.polyphony.core.ui.components.listContentPadding
import app.polyphony.navigation.LocalNavigator
import app.polyphony.ui.common.LibraryRemoval
import app.polyphony.ui.common.RemovalEvent
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.collections.immutable.ImmutableList
import kotlinx.collections.immutable.persistentListOf
import kotlinx.collections.immutable.toImmutableList
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import javax.inject.Inject

data class RemovedAlbumsUiState(
    val items: ImmutableList<RemovedAlbum> = persistentListOf(),
    val loading: Boolean = true,
    val error: String? = null,
)

/** Albums removed from the library and still in continuo's cache (continuo#23), with Restore. */
@HiltViewModel
class RemovedAlbumsViewModel @Inject constructor(
    private val servers: ServerRepository,
    private val requests: RequestRepository,
    private val removal: LibraryRemoval,
) : ViewModel() {

    private val _state = MutableStateFlow(RemovedAlbumsUiState())
    val state: StateFlow<RemovedAlbumsUiState> = _state.asStateFlow()

    init {
        load()
        viewModelScope.launch {
            removal.events.collect { if (it is RemovalEvent.Restored || it is RemovalEvent.Removed) load() }
        }
    }

    fun load() {
        viewModelScope.launch {
            _state.update { it.copy(loading = true, error = null) }
            val server = servers.getActive()?.takeIf { requests.supports(it) }
            _state.value = try {
                RemovedAlbumsUiState(items = server?.let { requests.removedAlbums(it) }.orEmpty().toImmutableList(), loading = false)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                RemovedAlbumsUiState(loading = false, error = e.message ?: e.javaClass.simpleName)
            }
        }
    }

    fun restore(album: RemovedAlbum) {
        _state.update { s -> s.copy(items = s.items.filterNot { it.id == album.id }.toImmutableList()) }
        removal.restore(album)
    }
}

@Composable
fun RemovedAlbumsScreen(viewModel: RemovedAlbumsViewModel = hiltViewModel()) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val navigator = LocalNavigator.current
    val context = LocalContext.current
    Scaffold(
        contentWindowInsets = WindowInsets(0, 0, 0, 0),
        topBar = {
            Column {
                TopAppBar(
                    title = { Text(stringResource(R.string.library_removed_title)) },
                    navigationIcon = {
                        IconButton(onClick = { navigator.back() }) {
                            Icon(Icons.AutoMirrored.Rounded.ArrowBack, contentDescription = stringResource(R.string.browse_action_back))
                        }
                    },
                )
                if (state.loading) LinearProgressIndicator(Modifier.fillMaxWidth().height(2.dp))
            }
        },
    ) { padding ->
        when {
            state.error != null -> Column(Modifier.fillMaxSize().padding(padding).padding(top = 96.dp)) {
                ErrorState(message = stringResource(R.string.library_removed_failed, state.error.orEmpty()), onRetry = viewModel::load)
            }
            !state.loading && state.items.isEmpty() -> Column(Modifier.fillMaxSize().padding(padding)) {
                EmptyState(title = stringResource(R.string.library_removed_empty))
            }
            else -> LazyColumn(Modifier.fillMaxSize().padding(padding), contentPadding = listContentPadding(top = 4.dp)) {
                items(state.items, key = { it.id }) { album ->
                    val until = album.restorableUntilMillis?.let {
                        stringResource(R.string.library_removed_until, DateUtils.formatDateTime(context, it, DateUtils.FORMAT_SHOW_DATE))
                    }
                    ListItem(
                        headlineContent = { Text(album.title) },
                        supportingContent = { Text(listOfNotNull(album.artist, until).joinToString(" · ")) },
                        trailingContent = {
                            FilledTonalButton(onClick = { viewModel.restore(album) }) { Text(stringResource(R.string.library_restore)) }
                        },
                    )
                }
            }
        }
    }
}
