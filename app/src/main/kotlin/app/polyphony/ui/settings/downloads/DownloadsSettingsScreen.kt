package app.polyphony.ui.settings.downloads

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.DownloadForOffline
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.res.stringResource
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import app.polyphony.R
import app.polyphony.core.domain.repository.DownloadLocation
import app.polyphony.core.domain.repository.DownloadRepository
import app.polyphony.core.domain.repository.DownloadSettings
import app.polyphony.core.domain.repository.SettingsRepository
import app.polyphony.core.domain.repository.StorageUsage
import app.polyphony.core.domain.repository.TranscodeProfileRepository
import app.polyphony.core.domain.transcode.TranscodeProfile
import app.polyphony.core.ui.util.formatBytes
import app.polyphony.navigation.LocalNavigator
import app.polyphony.ui.settings.components.ActionPreference
import app.polyphony.ui.settings.components.ListOption
import app.polyphony.ui.settings.components.ListPreference
import app.polyphony.ui.settings.components.PreferenceNote
import app.polyphony.ui.settings.components.SegmentedPreference
import app.polyphony.ui.settings.components.SettingsScaffold
import app.polyphony.ui.settings.components.SwitchPreference
import app.polyphony.ui.settings.components.preferenceCategory
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import javax.inject.Inject

@HiltViewModel
class DownloadsSettingsViewModel @Inject constructor(
    private val settingsRepository: SettingsRepository,
    downloads: DownloadRepository,
    profileRepository: TranscodeProfileRepository,
) : ViewModel() {
    val settings: StateFlow<DownloadSettings> = settingsRepository.settings
        .map { it.downloads }.distinctUntilChanged()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), DownloadSettings())

    val storage: StateFlow<StorageUsage?> = downloads.storageUsage
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)

    val profiles: StateFlow<List<TranscodeProfile>> = profileRepository.profiles
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), TranscodeProfile.BUILT_INS)

    fun update(transform: (DownloadSettings) -> DownloadSettings) {
        viewModelScope.launch { settingsRepository.update { it.copy(downloads = transform(it.downloads)) } }
    }
}

@Composable
fun DownloadsSettingsScreen(viewModel: DownloadsSettingsViewModel = hiltViewModel()) {
    val navigator = LocalNavigator.current
    val s by viewModel.settings.collectAsStateWithLifecycle()
    val storage by viewModel.storage.collectAsStateWithLifecycle()
    val profiles by viewModel.profiles.collectAsStateWithLifecycle()

    val capOptions = remember {
        listOf(0L, 1L, 2L, 5L, 10L, 20L, 50L).map { gb ->
            ListOption(gb * 1024L, if (gb == 0L) "Unlimited" else "$gb GB")
        }
    }
    val intervalOptions = remember { listOf(1, 3, 6, 12, 24).map { ListOption(it, "$it h") } }
    val concurrentOptions = remember { (1..4).map { ListOption(it, it.toString()) } }

    SettingsScaffold(title = stringResource(R.string.dl_settings_title), onBack = { navigator.back() }) {
        preferenceCategory(key = "open") {
            ActionPreference(
                title = stringResource(R.string.dl_action_open_downloads),
                summary = storage?.let {
                    stringResource(R.string.dl_settings_summary_used, formatBytes(it.usedBytes)) + " · " + stringResource(R.string.dl_storage_files, it.fileCount)
                },
                icon = Icons.Rounded.DownloadForOffline,
                onClick = navigator::openDownloads,
            )
        }
        preferenceCategory(key = "network", titleRes = R.string.dl_settings_cat_network) {
            SwitchPreference(
                title = stringResource(R.string.dl_settings_wifi_only),
                checked = s.wifiOnly,
                onCheckedChange = { v -> viewModel.update { it.copy(wifiOnly = v) } },
            )
            SwitchPreference(
                title = stringResource(R.string.dl_settings_charging_only),
                checked = s.chargingOnly,
                onCheckedChange = { v -> viewModel.update { it.copy(chargingOnly = v) } },
            )
            ListPreference(
                title = stringResource(R.string.dl_settings_concurrent),
                options = concurrentOptions,
                selected = s.concurrentDownloads.coerceIn(1, 4),
                onSelected = { v -> viewModel.update { it.copy(concurrentDownloads = v) } },
            )
        }
        preferenceCategory(key = "storage", titleRes = R.string.dl_settings_cat_storage) {
            ListPreference(
                title = stringResource(R.string.dl_settings_storage_cap),
                options = capOptions,
                selected = s.maxStorageMb,
                summary = if (s.maxStorageMb == 0L) stringResource(R.string.dl_settings_storage_unlimited) else formatBytes(s.maxStorageMb * 1024L * 1024L),
                onSelected = { v -> viewModel.update { it.copy(maxStorageMb = v) } },
            )
            val profileOptions = profiles.map { ListOption(it.id, it.name, it.summary) }
            ListPreference(
                title = stringResource(R.string.dl_settings_default_quality),
                options = profileOptions,
                selected = s.defaultProfileId,
                onSelected = { v -> viewModel.update { it.copy(defaultProfileId = v) } },
            )
            SegmentedPreference(
                title = stringResource(R.string.dl_settings_location),
                summary = storage?.let { stringResource(R.string.dl_settings_location_free, formatBytes(it.deviceFreeBytes)) },
                options = listOf(
                    ListOption(DownloadLocation.INTERNAL, stringResource(R.string.dl_settings_location_internal)),
                    ListOption(DownloadLocation.EXTERNAL, stringResource(R.string.dl_settings_location_external)),
                ),
                selected = s.location,
                onSelected = { v -> viewModel.update { it.copy(location = v) } },
            )
        }
        preferenceCategory(key = "rules", titleRes = R.string.dl_settings_cat_rules) {
            SwitchPreference(
                title = stringResource(R.string.dl_settings_remove_unmatched),
                summary = stringResource(R.string.dl_settings_remove_unmatched_summary),
                checked = s.removeWhenRuleNoLongerMatches,
                onCheckedChange = { v -> viewModel.update { it.copy(removeWhenRuleNoLongerMatches = v) } },
            )
            SwitchPreference(
                title = stringResource(R.string.dl_settings_evict_lrp),
                summary = stringResource(R.string.dl_settings_evict_lrp_summary),
                checked = s.evictLeastRecentlyPlayed,
                onCheckedChange = { v -> viewModel.update { it.copy(evictLeastRecentlyPlayed = v) } },
            )
            ListPreference(
                title = stringResource(R.string.dl_settings_interval),
                options = intervalOptions,
                selected = s.ruleEvaluationHours,
                onSelected = { v -> viewModel.update { it.copy(ruleEvaluationHours = v) } },
            )
            ActionPreference(
                title = stringResource(R.string.dl_action_manage_rules),
                onClick = navigator::openDownloads,
            )
        }
        preferenceCategory(key = "playback", titleRes = R.string.dl_settings_cat_playback) {
            SwitchPreference(
                title = stringResource(R.string.dl_settings_offline_mode),
                summary = stringResource(R.string.dl_settings_offline_mode_summary),
                checked = s.offlineMode,
                onCheckedChange = { v -> viewModel.update { it.copy(offlineMode = v) } },
            )
            SwitchPreference(
                title = stringResource(R.string.dl_settings_prefer_downloaded),
                summary = stringResource(R.string.dl_settings_prefer_downloaded_summary),
                checked = s.preferDownloadedForPlayback,
                onCheckedChange = { v -> viewModel.update { it.copy(preferDownloadedForPlayback = v) } },
            )
            PreferenceNote(stringResource(R.string.dl_downloaded_empty_body))
        }
    }
}
