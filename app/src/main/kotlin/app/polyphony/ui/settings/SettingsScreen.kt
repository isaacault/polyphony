package app.polyphony.ui.settings

import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Cast
import androidx.compose.material.icons.rounded.Dns
import androidx.compose.material.icons.rounded.DownloadForOffline
import androidx.compose.material.icons.rounded.Info
import androidx.compose.material.icons.rounded.LibraryMusic
import androidx.compose.material.icons.rounded.Palette
import androidx.compose.material.icons.rounded.PlayCircle
import androidx.compose.material.icons.rounded.Tune
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import app.polyphony.BuildConfig
import app.polyphony.R
import app.polyphony.core.domain.model.ServerType
import app.polyphony.core.domain.playback.PlaybackController
import app.polyphony.core.domain.repository.DownloadRepository
import app.polyphony.core.domain.repository.LibrarySyncManager
import app.polyphony.core.domain.repository.ServerRepository
import app.polyphony.core.ui.util.formatBytes
import app.polyphony.navigation.LocalNavigator
import app.polyphony.ui.settings.components.ActionPreference
import app.polyphony.ui.settings.components.SettingsScaffold
import app.polyphony.ui.settings.components.preferenceCategory
import app.polyphony.ui.settings.components.relativeTime
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import javax.inject.Inject

data class SettingsHubUiState(
    val activeServerName: String? = null,
    val activeServerHost: String? = null,
    val activeServerType: ServerType? = null,
    val decisionSummary: String? = null,
    val lastSyncAt: Long? = null,
    val downloadsUsedBytes: Long = 0L,
    val downloadsFileCount: Int = 0,
)

@HiltViewModel
class SettingsHubViewModel @Inject constructor(
    serverRepository: ServerRepository,
    playbackController: PlaybackController,
    syncManager: LibrarySyncManager,
    downloadRepository: DownloadRepository,
) : ViewModel() {
    val uiState: StateFlow<SettingsHubUiState> = combine(
        serverRepository.activeServer,
        playbackController.state,
        syncManager.state,
        downloadRepository.storageUsage,
    ) { server, playback, sync, storage ->
        SettingsHubUiState(
            downloadsUsedBytes = storage.usedBytes,
            downloadsFileCount = storage.fileCount,
            activeServerName = server?.name,
            activeServerHost = server?.displayHost,
            activeServerType = server?.type,
            decisionSummary = playback.transcode?.let { "${it.profile.name} · ${it.explanation}" },
            lastSyncAt = sync.lastSyncAt,
        )
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), SettingsHubUiState())
}

@Composable
fun SettingsScreen(viewModel: SettingsHubViewModel = hiltViewModel()) {
    val navigator = LocalNavigator.current
    val state by viewModel.uiState.collectAsStateWithLifecycle()

    SettingsScaffold(title = stringResource(R.string.settings_title), onBack = { navigator.back() }) {
        preferenceCategory(key = "connection", titleRes = R.string.settings_cat_connection) {
            ActionPreference(
                title = stringResource(R.string.settings_servers),
                summary = state.activeServerName?.let { "$it · ${state.activeServerHost}" }
                    ?: stringResource(R.string.settings_servers_none),
                icon = Icons.Rounded.Dns,
                onClick = navigator::openServersSettings,
            )
        }
        preferenceCategory(key = "look", titleRes = R.string.settings_cat_look) {
            ActionPreference(
                title = stringResource(R.string.settings_appearance),
                summary = stringResource(R.string.settings_appearance_summary),
                icon = Icons.Rounded.Palette,
                onClick = navigator::openAppearanceSettings,
            )
            ActionPreference(
                title = stringResource(R.string.settings_playback),
                summary = stringResource(R.string.settings_playback_summary),
                icon = Icons.Rounded.PlayCircle,
                onClick = navigator::openPlaybackSettings,
            )
        }
        preferenceCategory(key = "streaming", titleRes = R.string.settings_cat_streaming) {
            ActionPreference(
                title = stringResource(R.string.settings_streaming),
                summary = state.decisionSummary ?: stringResource(R.string.settings_streaming_summary),
                icon = Icons.Rounded.Tune,
                onClick = navigator::openStreamingSettings,
            )
            ActionPreference(
                title = stringResource(R.string.settings_cast_devices),
                summary = stringResource(R.string.settings_cast_devices_summary),
                icon = Icons.Rounded.Cast,
                onClick = navigator::openRemoteDevicesSettings,
            )
        }
        preferenceCategory(key = "data", titleRes = R.string.settings_cat_data) {
            ActionPreference(
                title = stringResource(R.string.settings_library),
                summary = stringResource(R.string.settings_library_last_sync, relativeTime(state.lastSyncAt)),
                icon = Icons.Rounded.LibraryMusic,
                onClick = navigator::openLibrarySettings,
            )
            ActionPreference(
                title = stringResource(R.string.dl_settings_title),
                summary = if (state.downloadsFileCount > 0) {
                    stringResource(R.string.dl_settings_summary_used, formatBytes(state.downloadsUsedBytes))
                } else {
                    stringResource(R.string.dl_settings_summary_none)
                },
                icon = Icons.Rounded.DownloadForOffline,
                onClick = navigator::openDownloadsSettings,
            )
        }
        preferenceCategory(key = "about") {
            ActionPreference(
                title = stringResource(R.string.settings_about),
                summary = stringResource(R.string.settings_about_summary, BuildConfig.VERSION_NAME),
                icon = Icons.Rounded.Info,
                onClick = navigator::openAbout,
            )
        }
        item(key = "version") {
            Text(
                text = stringResource(R.string.settings_version_footer, BuildConfig.VERSION_NAME),
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                textAlign = TextAlign.Center,
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(top = 8.dp),
            )
        }
    }
}
