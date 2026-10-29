package app.polyphony.ui.common

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.CloudOff
import androidx.compose.material.icons.rounded.Download
import androidx.compose.material.icons.rounded.DownloadDone
import androidx.compose.material.icons.rounded.DownloadForOffline
import androidx.compose.material.icons.rounded.Downloading
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.IconButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.State
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import app.polyphony.R
import app.polyphony.core.domain.model.Track
import app.polyphony.core.domain.repository.CollectionDownloadState
import app.polyphony.core.domain.repository.DownloadRepository
import app.polyphony.core.domain.repository.DownloadStatus
import app.polyphony.core.domain.repository.DownloadedTrack
import app.polyphony.core.domain.repository.SettingsRepository
import app.polyphony.core.domain.repository.TranscodeProfileRepository
import app.polyphony.core.domain.transcode.TranscodeProfile
import app.polyphony.ui.settings.components.ConfirmDialog
import app.polyphony.ui.settings.components.ProfilePickerDialog
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import javax.inject.Inject

/**
 * Download plumbing any screen can host: the set of downloaded ids (for row badges), per-item and
 * per-collection state, the offline-mode switch, and the download / remove actions.
 */
@HiltViewModel
class DownloadActionsViewModel @Inject constructor(
    private val downloads: DownloadRepository,
    private val settings: SettingsRepository,
    profileRepository: TranscodeProfileRepository,
) : ViewModel() {

    val downloadedIds: StateFlow<Set<String>> = downloads.downloadedTrackIds()
        .distinctUntilChanged()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptySet())

    val offlineMode: StateFlow<Boolean> = settings.settings
        .map { it.downloads.offlineMode }
        .distinctUntilChanged()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), false)

    val defaultProfileId: StateFlow<String> = settings.settings
        .map { it.downloads.defaultProfileId }
        .distinctUntilChanged()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), TranscodeProfile.ORIGINAL_ID)

    val profiles: StateFlow<List<TranscodeProfile>> = profileRepository.profiles
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), TranscodeProfile.BUILT_INS)

    fun downloadState(trackId: String): Flow<DownloadedTrack?> = downloads.downloadState(trackId)
    fun collectionState(trackIds: List<String>): Flow<CollectionDownloadState> = downloads.collectionState(trackIds)

    fun setOfflineMode(enabled: Boolean) {
        viewModelScope.launch { settings.update { it.copy(downloads = it.downloads.copy(offlineMode = enabled)) } }
    }

    fun download(tracks: List<Track>, profileId: String? = null) {
        if (tracks.isEmpty()) return
        viewModelScope.launch { runCatching { downloads.download(tracks, profileId = profileId, pinned = true) } }
    }

    fun remove(trackIds: List<String>) {
        if (trackIds.isEmpty()) return
        viewModelScope.launch { runCatching { downloads.remove(trackIds) } }
    }
}

/** Ids of fully downloaded tracks for the active server; cheap `Set` lookups per row. */
@Composable
fun rememberDownloadedIds(): State<Set<String>> {
    val vm: DownloadActionsViewModel = hiltViewModel()
    return vm.downloadedIds.collectAsStateWithLifecycle()
}

/** Top-bar toggle for "Downloaded only" (bound to `DownloadSettings.offlineMode`). */
@Composable
fun OfflineModeIconButton(modifier: Modifier = Modifier) {
    val vm: DownloadActionsViewModel = hiltViewModel()
    val offline by vm.offlineMode.collectAsStateWithLifecycle()
    val haptics = LocalHapticFeedback.current
    IconButton(
        onClick = {
            haptics.performHapticFeedback(if (offline) HapticFeedbackType.ToggleOff else HapticFeedbackType.ToggleOn)
            vm.setOfflineMode(!offline)
        },
        modifier = modifier,
        colors = if (offline) IconButtonDefaults.filledTonalIconButtonColors() else IconButtonDefaults.iconButtonColors(),
    ) {
        Icon(
            imageVector = if (offline) Icons.Rounded.DownloadForOffline else Icons.Rounded.CloudOff,
            contentDescription = stringResource(R.string.dl_downloaded_only),
            tint = if (offline) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

/** Slim persistent banner shown under app bars while offline mode is on. */
@Composable
fun OfflineModeBanner(modifier: Modifier = Modifier) {
    val vm: DownloadActionsViewModel = hiltViewModel()
    val offline by vm.offlineMode.collectAsStateWithLifecycle()
    AnimatedVisibility(visible = offline, modifier = modifier) {
        Surface(
            color = MaterialTheme.colorScheme.tertiaryContainer,
            contentColor = MaterialTheme.colorScheme.onTertiaryContainer,
            modifier = Modifier
                .fillMaxWidth()
                .clickable { vm.setOfflineMode(false) },
        ) {
            Row(
                Modifier.padding(start = 16.dp, end = 4.dp, top = 2.dp, bottom = 2.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Icon(Icons.Rounded.DownloadForOffline, contentDescription = null, modifier = Modifier.size(16.dp))
                Spacer(Modifier.width(10.dp))
                Text(
                    text = stringResource(R.string.dl_offline_banner),
                    style = MaterialTheme.typography.labelMedium,
                    modifier = Modifier.weight(1f),
                )
                TextButton(onClick = { vm.setOfflineMode(false) }) {
                    Text(stringResource(R.string.dl_offline_banner_action), style = MaterialTheme.typography.labelMedium)
                }
            }
        }
    }
}

/**
 * Header download button for a collection (album / playlist / artist). Tap downloads, or asks to
 * remove / cancel; long-press picks a quality profile. [tracks] is called on tap and may fetch.
 */
@Composable
fun CollectionDownloadButton(
    trackIds: List<String>,
    tracks: suspend () -> List<Track>,
    modifier: Modifier = Modifier,
    overlay: Boolean = true,
) {
    val vm: DownloadActionsViewModel = hiltViewModel()
    val state by remember(trackIds) { vm.collectionState(trackIds) }
        .collectAsStateWithLifecycle(CollectionDownloadState(trackIds.size, 0, 0, 0))
    val profiles by vm.profiles.collectAsStateWithLifecycle()
    val defaultProfileId by vm.defaultProfileId.collectAsStateWithLifecycle()
    val haptics = LocalHapticFeedback.current
    val scope = rememberCoroutineScope()
    var showQualityPicker by remember { mutableStateOf(false) }
    var confirmRemove by remember { mutableStateOf(false) }
    var confirmCancel by remember { mutableStateOf(false) }

    fun startDownload(profileId: String?) {
        haptics.performHapticFeedback(HapticFeedbackType.Confirm)
        scope.launch { vm.download(tracks(), profileId) }
    }

    val enabled = trackIds.isNotEmpty()
    val containerColor = if (overlay) {
        MaterialTheme.colorScheme.surface.copy(alpha = 0.55f)
    } else {
        MaterialTheme.colorScheme.surfaceContainerHigh
    }
    Box(
        modifier = modifier
            .size(40.dp)
            .clip(CircleShape)
            .combinedClickable(
                enabled = enabled,
                onClick = {
                    when {
                        state.isDownloading -> confirmCancel = true
                        state.isFullyDownloaded -> confirmRemove = true
                        else -> startDownload(null)
                    }
                },
                onLongClick = { if (!state.isFullyDownloaded) showQualityPicker = true },
            ),
        contentAlignment = Alignment.Center,
    ) {
        Surface(color = containerColor, shape = CircleShape, modifier = Modifier.size(40.dp)) {}
        when {
            state.isDownloading -> {
                CircularProgressIndicator(
                    progress = { state.progress.coerceIn(0.02f, 1f) },
                    modifier = Modifier.size(28.dp),
                    strokeWidth = 2.5.dp,
                )
                Icon(
                    Icons.Rounded.Downloading,
                    contentDescription = stringResource(R.string.dl_state_downloading, (state.progress * 100).toInt()),
                    modifier = Modifier.size(14.dp),
                    tint = MaterialTheme.colorScheme.primary,
                )
            }
            state.isFullyDownloaded -> Icon(
                Icons.Rounded.DownloadDone,
                contentDescription = stringResource(R.string.dl_state_downloaded),
                tint = MaterialTheme.colorScheme.primary,
            )
            state.isPartiallyDownloaded -> Icon(
                Icons.Rounded.DownloadForOffline,
                contentDescription = stringResource(R.string.dl_state_partial),
                tint = MaterialTheme.colorScheme.onSurface,
            )
            else -> Icon(
                Icons.Rounded.Download,
                contentDescription = stringResource(R.string.dl_state_not_downloaded),
                tint = if (enabled) MaterialTheme.colorScheme.onSurface else MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }

    if (showQualityPicker) {
        ProfilePickerDialog(
            title = stringResource(R.string.dl_action_pick_quality),
            profiles = profiles,
            selectedId = defaultProfileId,
            noneLabel = null,
            onSelect = { id ->
                showQualityPicker = false
                startDownload(id)
            },
            onDismiss = { showQualityPicker = false },
        )
    }
    if (confirmRemove) {
        ConfirmDialog(
            title = stringResource(R.string.dl_confirm_remove_title),
            text = stringResource(R.string.dl_confirm_remove_text),
            confirmLabel = stringResource(R.string.dl_action_remove),
            onConfirm = { confirmRemove = false; vm.remove(trackIds) },
            onDismiss = { confirmRemove = false },
        )
    }
    if (confirmCancel) {
        ConfirmDialog(
            title = stringResource(R.string.dl_confirm_cancel_title),
            text = stringResource(R.string.dl_confirm_cancel_text),
            confirmLabel = stringResource(R.string.dl_action_cancel_download),
            onConfirm = { confirmCancel = false; vm.remove(trackIds) },
            onDismiss = { confirmCancel = false },
        )
    }
}

/** Sheet items for a single track: Download / Download in… / Remove download, state-aware. */
@Composable
fun TrackDownloadSheetItems(track: Track, dismiss: () -> Unit) {
    val vm: DownloadActionsViewModel = hiltViewModel()
    val state by remember(track.id) { vm.downloadState(track.id) }.collectAsStateWithLifecycle(null)
    val profiles by vm.profiles.collectAsStateWithLifecycle()
    val defaultProfileId by vm.defaultProfileId.collectAsStateWithLifecycle()
    val haptics = LocalHapticFeedback.current
    var showQualityPicker by remember { mutableStateOf(false) }

    when (state?.status) {
        DownloadStatus.COMPLETED -> SheetItem(Icons.Rounded.DownloadDone, stringResource(R.string.dl_action_remove_download)) {
            vm.remove(listOf(track.id)); dismiss()
        }
        DownloadStatus.QUEUED, DownloadStatus.DOWNLOADING, DownloadStatus.PAUSED ->
            SheetItem(Icons.Rounded.Downloading, stringResource(R.string.dl_action_cancel_download)) {
                vm.remove(listOf(track.id)); dismiss()
            }
        DownloadStatus.FAILED -> SheetItem(Icons.Rounded.Download, stringResource(R.string.dl_action_retry)) {
            haptics.performHapticFeedback(HapticFeedbackType.Confirm)
            vm.download(listOf(track)); dismiss()
        }
        null -> {
            SheetItem(Icons.Rounded.Download, stringResource(R.string.dl_action_download)) {
                haptics.performHapticFeedback(HapticFeedbackType.Confirm)
                vm.download(listOf(track)); dismiss()
            }
            SheetItem(Icons.Rounded.DownloadForOffline, stringResource(R.string.dl_action_download_quality)) {
                showQualityPicker = true
            }
        }
    }
    if (showQualityPicker) {
        ProfilePickerDialog(
            title = stringResource(R.string.dl_action_pick_quality),
            profiles = profiles,
            selectedId = defaultProfileId,
            noneLabel = null,
            onSelect = { id ->
                showQualityPicker = false
                haptics.performHapticFeedback(HapticFeedbackType.Confirm)
                vm.download(listOf(track), id)
                dismiss()
            },
            onDismiss = { showQualityPicker = false },
        )
    }
}
