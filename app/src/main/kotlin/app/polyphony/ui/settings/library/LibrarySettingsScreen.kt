package app.polyphony.ui.settings.library

import android.content.Context
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.DeleteSweep
import androidx.compose.material.icons.rounded.Sync
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import app.polyphony.R
import app.polyphony.core.domain.model.FeedbackMode
import app.polyphony.core.domain.repository.FeedbackRepository
import app.polyphony.core.domain.repository.LibrarySettings
import app.polyphony.core.domain.repository.LibrarySyncManager
import app.polyphony.core.domain.repository.SettingsRepository
import app.polyphony.core.domain.repository.SyncState
import app.polyphony.core.ui.util.formatBytes
import app.polyphony.navigation.LocalNavigator
import app.polyphony.ui.settings.components.ActionPreference
import app.polyphony.ui.settings.components.ListOption
import app.polyphony.ui.settings.components.ListPreference
import app.polyphony.ui.settings.components.SegmentedPreference
import app.polyphony.ui.settings.components.SettingsScaffold
import app.polyphony.ui.settings.components.SwitchPreference
import app.polyphony.ui.settings.components.preferenceCategory
import app.polyphony.ui.settings.components.relativeTime
import coil3.SingletonImageLoader
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import javax.inject.Inject

data class LibrarySettingsUiState(
    val settings: LibrarySettings,
    val sync: SyncState,
    /** False on a server with likes but no star ratings (Jellyfin): Ratings mode is greyed out. */
    val ratingsSupported: Boolean = true,
)

sealed interface LibrarySettingsMessage {
    data object CacheCleared : LibrarySettingsMessage
    data class SyncFailed(val reason: String?) : LibrarySettingsMessage
}

@HiltViewModel
class LibrarySettingsViewModel @Inject constructor(
    @ApplicationContext private val context: Context,
    private val settingsRepository: SettingsRepository,
    private val syncManager: LibrarySyncManager,
    feedbackRepository: FeedbackRepository,
) : ViewModel() {

    private val _messages = Channel<LibrarySettingsMessage>(Channel.BUFFERED)
    val messages: Flow<LibrarySettingsMessage> = _messages.receiveAsFlow()

    val uiState: StateFlow<LibrarySettingsUiState?> = combine(
        settingsRepository.settings.map { it.library },
        syncManager.state,
        feedbackRepository.feedback,
    ) { library, sync, feedback -> LibrarySettingsUiState(library, sync, feedback.ratingsSupported) }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)

    fun syncNow() {
        viewModelScope.launch {
            runCatching { syncManager.syncNow(full = true) }
                .onFailure { _messages.send(LibrarySettingsMessage.SyncFailed(it.message)) }
        }
    }

    fun update(transform: (LibrarySettings) -> LibrarySettings) {
        viewModelScope.launch {
            val before = settingsRepository.current().library
            settingsRepository.update { it.copy(library = transform(it.library)) }
            val after = settingsRepository.current().library
            if (before.autoSyncHours != after.autoSyncHours || before.syncOnWifiOnly != after.syncOnWifiOnly) {
                if (after.autoSyncHours > 0) syncManager.scheduleAutoSync(after.autoSyncHours, after.syncOnWifiOnly)
                else syncManager.cancelAutoSync()
            }
        }
    }

    fun clearArtworkCache() {
        viewModelScope.launch {
            val loader = SingletonImageLoader.get(context)
            withContext(Dispatchers.IO) { loader.diskCache?.clear() }
            loader.memoryCache?.clear()
            _messages.send(LibrarySettingsMessage.CacheCleared)
        }
    }
}

@Composable
fun LibrarySettingsScreen(viewModel: LibrarySettingsViewModel = hiltViewModel()) {
    val navigator = LocalNavigator.current
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    val snackbar = remember { SnackbarHostState() }
    val cacheCleared = stringResource(R.string.settings_library_cache_cleared)
    val syncFailed = stringResource(R.string.settings_library_sync_failed)

    LaunchedEffect(Unit) {
        viewModel.messages.collect { message ->
            when (message) {
                LibrarySettingsMessage.CacheCleared -> snackbar.showSnackbar(cacheCleared)
                is LibrarySettingsMessage.SyncFailed -> snackbar.showSnackbar(message.reason ?: syncFailed)
            }
        }
    }

    SettingsScaffold(
        title = stringResource(R.string.settings_library),
        onBack = { navigator.back() },
        snackbarHostState = snackbar,
    ) {
        val ui = state ?: return@SettingsScaffold
        val s = ui.settings

        item(key = "sync") {
            SyncCard(sync = ui.sync, onSyncNow = viewModel::syncNow)
        }

        preferenceCategory(key = "auto", titleRes = R.string.settings_library_auto_sync) {
            ListPreference(
                title = stringResource(R.string.settings_library_auto_sync_interval),
                options = listOf(
                    ListOption(0, stringResource(R.string.settings_off)),
                    ListOption(1, stringResource(R.string.settings_hours, 1)),
                    ListOption(3, stringResource(R.string.settings_hours, 3)),
                    ListOption(6, stringResource(R.string.settings_hours, 6)),
                    ListOption(12, stringResource(R.string.settings_hours, 12)),
                    ListOption(24, stringResource(R.string.settings_hours, 24)),
                ),
                selected = s.autoSyncHours,
                onSelected = { h -> viewModel.update { it.copy(autoSyncHours = h) } },
            )
            SwitchPreference(
                title = stringResource(R.string.settings_library_wifi_only),
                summary = stringResource(R.string.settings_library_wifi_only_summary),
                checked = s.syncOnWifiOnly,
                onCheckedChange = { on -> viewModel.update { it.copy(syncOnWifiOnly = on) } },
            )
            SwitchPreference(
                title = stringResource(R.string.settings_library_sync_on_launch),
                summary = stringResource(R.string.settings_library_sync_on_launch_summary),
                checked = s.syncOnLaunch,
                onCheckedChange = { on -> viewModel.update { it.copy(syncOnLaunch = on) } },
            )
        }

        preferenceCategory(key = "display", titleRes = R.string.settings_library_browsing) {
            SwitchPreference(
                title = stringResource(R.string.settings_library_compilations),
                summary = stringResource(R.string.settings_library_compilations_summary),
                checked = s.showCompilationsUnderArtists,
                onCheckedChange = { on -> viewModel.update { it.copy(showCompilationsUnderArtists = on) } },
            )
        }

        preferenceCategory(key = "likes", titleRes = R.string.feedback_settings_title) {
            // On a server without star ratings the choice is kept but has no effect: favourites apply.
            val ratings = s.feedbackMode == FeedbackMode.RATINGS && ui.ratingsSupported
            SegmentedPreference(
                title = stringResource(R.string.feedback_settings_mode),
                options = listOf(
                    ListOption(FeedbackMode.FAVOURITES, stringResource(R.string.feedback_mode_favourites)),
                    ListOption(FeedbackMode.RATINGS, stringResource(R.string.feedback_mode_ratings)),
                ),
                selected = if (ratings) FeedbackMode.RATINGS else FeedbackMode.FAVOURITES,
                onSelected = { mode -> viewModel.update { it.copy(feedbackMode = mode) } },
                summary = when {
                    !ui.ratingsSupported -> stringResource(R.string.feedback_settings_unsupported)
                    ratings -> stringResource(R.string.feedback_settings_ratings_summary)
                    else -> stringResource(R.string.feedback_settings_favourites_summary)
                },
                enabled = ui.ratingsSupported,
            )
            if (ratings) {
                ListPreference(
                    title = stringResource(R.string.feedback_settings_top_rated),
                    options = (1..5).map { ListOption(it, stringResource(R.string.feedback_settings_top_rated_value, it)) },
                    selected = s.topRatedMinimum,
                    onSelected = { n -> viewModel.update { it.copy(topRatedMinimum = n) } },
                )
            }
        }

        preferenceCategory(key = "cache", titleRes = R.string.settings_library_artwork) {
            ListPreference(
                title = stringResource(R.string.settings_library_artwork_cache),
                options = listOf(128, 256, 512, 1024, 2048).map { ListOption(it, formatBytes(it * 1024L * 1024L)) },
                selected = s.artworkCacheSizeMb,
                onSelected = { mb -> viewModel.update { it.copy(artworkCacheSizeMb = mb) } },
                summary = stringResource(R.string.settings_library_artwork_cache_summary, formatBytes(s.artworkCacheSizeMb * 1024L * 1024L)),
            )
            ActionPreference(
                title = stringResource(R.string.settings_library_clear_artwork),
                summary = stringResource(R.string.settings_library_clear_artwork_summary),
                icon = Icons.Rounded.DeleteSweep,
                showChevron = false,
                onClick = viewModel::clearArtworkCache,
            )
        }
    }
}

@Composable
private fun SyncCard(sync: SyncState, onSyncNow: () -> Unit) {
    Card(
        shape = MaterialTheme.shapes.large,
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.primaryContainer,
            contentColor = MaterialTheme.colorScheme.onPrimaryContainer,
        ),
        modifier = Modifier.fillMaxWidth(),
    ) {
        Column(Modifier.padding(20.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Text(stringResource(R.string.settings_library_sync_title), style = MaterialTheme.typography.titleMedium)
            when (sync.status) {
                SyncState.Status.RUNNING -> {
                    Text(
                        text = sync.phase ?: stringResource(R.string.settings_library_syncing),
                        style = MaterialTheme.typography.bodyMedium,
                    )
                    val progress = sync.progress
                    if (progress != null) {
                        LinearProgressIndicator(progress = { progress }, modifier = Modifier.fillMaxWidth())
                    } else {
                        LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
                    }
                }
                SyncState.Status.FAILED -> Text(
                    text = sync.lastError ?: stringResource(R.string.settings_library_sync_failed),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.error,
                )
                SyncState.Status.IDLE -> Unit
            }
            Text(
                text = stringResource(R.string.settings_library_last_sync, relativeTime(sync.lastSyncAt)),
                style = MaterialTheme.typography.bodyMedium,
            )
            Row {
                FilledTonalButton(onClick = onSyncNow, enabled = !sync.isRunning) {
                    Icon(Icons.Rounded.Sync, contentDescription = null, modifier = Modifier.size(18.dp))
                    Spacer(Modifier.width(8.dp))
                    Text(stringResource(if (sync.isRunning) R.string.settings_library_syncing else R.string.settings_library_sync_now))
                }
            }
        }
    }
}
