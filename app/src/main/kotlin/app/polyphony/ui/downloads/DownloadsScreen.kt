package app.polyphony.ui.downloads

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.automirrored.rounded.Rule
import androidx.compose.material.icons.rounded.Add
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.Delete
import androidx.compose.material.icons.rounded.DownloadForOffline
import androidx.compose.material.icons.rounded.Edit
import androidx.compose.material.icons.rounded.ErrorOutline
import androidx.compose.material.icons.rounded.MoreVert
import androidx.compose.material.icons.rounded.Pause
import androidx.compose.material.icons.rounded.PlayArrow
import androidx.compose.material.icons.rounded.Refresh
import androidx.compose.material.icons.rounded.Settings
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExtendedFloatingActionButton
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.ListItem
import androidx.compose.material3.ListItemDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.PrimaryTabRow
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Tab
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.polyphony.R
import app.polyphony.core.domain.model.Track
import app.polyphony.core.domain.repository.DownloadStatus
import app.polyphony.core.domain.repository.StorageUsage
import app.polyphony.core.ui.components.ArtworkImage
import app.polyphony.core.ui.components.EmptyState
import app.polyphony.core.ui.components.LoadingPlaceholder
import app.polyphony.core.ui.components.PlaceholderStyle
import app.polyphony.core.ui.components.TrackRow
import app.polyphony.core.ui.components.listContentPadding
import app.polyphony.core.ui.theme.ArtworkShapes
import app.polyphony.core.ui.theme.Dimens
import app.polyphony.core.ui.util.formatBytes
import app.polyphony.navigation.LocalNavigator
import app.polyphony.ui.common.TrackActionsViewModel
import app.polyphony.ui.common.TrackOptionsSheet
import app.polyphony.ui.settings.components.ConfirmDialog
import app.polyphony.ui.settings.components.relativeTime
import kotlinx.coroutines.launch

private enum class DownloadsTab { ACTIVE, DOWNLOADED, RULES }

@Composable
fun DownloadsScreen(viewModel: DownloadsViewModel = hiltViewModel()) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val navigator = LocalNavigator.current
    val tabs = DownloadsTab.entries
    val pagerState = rememberPagerState(initialPage = if (state.active.isEmpty()) 1 else 0) { tabs.size }
    val scope = rememberCoroutineScope()
    val scrollBehavior = TopAppBarDefaults.pinnedScrollBehavior()
    var menuOpen by remember { mutableStateOf(false) }
    var confirmRemoveAll by remember { mutableStateOf(false) }
    var trackForOptions by remember { mutableStateOf<Track?>(null) }

    Scaffold(
        modifier = Modifier.nestedScroll(scrollBehavior.nestedScrollConnection),
        contentWindowInsets = WindowInsets(0, 0, 0, 0),
        topBar = {
            Column {
                TopAppBar(
                    title = { Text(stringResource(R.string.dl_title)) },
                    navigationIcon = {
                        IconButton(onClick = { navigator.back() }) {
                            Icon(Icons.AutoMirrored.Rounded.ArrowBack, contentDescription = stringResource(R.string.browse_action_back))
                        }
                    },
                    actions = {
                        IconButton(onClick = navigator::openDownloadsSettings) {
                            Icon(Icons.Rounded.Settings, contentDescription = stringResource(R.string.dl_action_settings))
                        }
                        Box {
                            IconButton(onClick = { menuOpen = true }) {
                                Icon(Icons.Rounded.MoreVert, contentDescription = stringResource(R.string.browse_action_more))
                            }
                            DropdownMenu(expanded = menuOpen, onDismissRequest = { menuOpen = false }) {
                                DropdownMenuItem(
                                    text = { Text(stringResource(R.string.dl_action_evaluate_now)) },
                                    leadingIcon = { Icon(Icons.Rounded.Refresh, null) },
                                    onClick = { menuOpen = false; viewModel.evaluateNow() },
                                )
                                DropdownMenuItem(
                                    text = { Text(stringResource(R.string.dl_action_remove_all), color = MaterialTheme.colorScheme.error) },
                                    leadingIcon = { Icon(Icons.Rounded.Delete, null, tint = MaterialTheme.colorScheme.error) },
                                    onClick = { menuOpen = false; confirmRemoveAll = true },
                                )
                            }
                        }
                    },
                    scrollBehavior = scrollBehavior,
                )
                PrimaryTabRow(selectedTabIndex = pagerState.currentPage, divider = {}) {
                    tabs.forEachIndexed { index, tab ->
                        Tab(
                            selected = pagerState.currentPage == index,
                            onClick = { scope.launch { pagerState.animateScrollToPage(index) } },
                            text = {
                                Text(
                                    when (tab) {
                                        DownloadsTab.ACTIVE -> stringResource(R.string.dl_tab_active) + if (state.active.isNotEmpty()) " (${state.active.size})" else ""
                                        DownloadsTab.DOWNLOADED -> stringResource(R.string.dl_tab_downloaded)
                                        DownloadsTab.RULES -> stringResource(R.string.dl_tab_rules)
                                    },
                                )
                            },
                        )
                    }
                }
                if (state.ruleState.isRunning) {
                    LinearProgressIndicator(Modifier.fillMaxWidth().height(2.dp))
                }
            }
        },
        floatingActionButton = {
            if (pagerState.currentPage == DownloadsTab.RULES.ordinal) {
                ExtendedFloatingActionButton(
                    onClick = { navigator.openDownloadRule(null) },
                    icon = { Icon(Icons.Rounded.Add, contentDescription = null) },
                    text = { Text(stringResource(R.string.dl_action_new_rule)) },
                    modifier = Modifier.padding(bottom = Dimens.miniPlayerHeight),
                )
            }
        },
    ) { padding ->
        HorizontalPager(
            state = pagerState,
            modifier = Modifier
                .fillMaxSize()
                .padding(padding),
            beyondViewportPageCount = 1,
            key = { tabs[it].name },
        ) { page ->
            when (tabs[page]) {
                DownloadsTab.ACTIVE -> ActiveTab(state, viewModel, onTrackOptions = { trackForOptions = it })
                DownloadsTab.DOWNLOADED -> DownloadedTab(state, viewModel, onTrackOptions = { trackForOptions = it })
                DownloadsTab.RULES -> RulesTab(state, viewModel)
            }
        }
    }

    trackForOptions?.let { TrackOptionsSheet(track = it, onDismiss = { trackForOptions = null }) }
    if (confirmRemoveAll) {
        ConfirmDialog(
            title = stringResource(R.string.dl_confirm_remove_all_title),
            text = stringResource(R.string.dl_confirm_remove_all_text),
            confirmLabel = stringResource(R.string.dl_action_remove_all),
            onConfirm = { confirmRemoveAll = false; viewModel.removeAll() },
            onDismiss = { confirmRemoveAll = false },
        )
    }
}

// ---- Storage card ----

@Composable
private fun StorageCard(storage: StorageUsage?, fileCount: Int, onRules: () -> Unit, onSettings: () -> Unit) {
    Surface(
        shape = MaterialTheme.shapes.large,
        color = MaterialTheme.colorScheme.surfaceContainer,
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = Dimens.screenPadding, vertical = 8.dp),
    ) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(stringResource(R.string.dl_storage_title), style = MaterialTheme.typography.titleMedium)
            val used = storage?.usedBytes ?: 0L
            val limit = storage?.limitBytes ?: 0L
            Text(
                text = if (limit > 0) stringResource(R.string.dl_storage_used_of, formatBytes(used), formatBytes(limit))
                else stringResource(R.string.dl_storage_used_unlimited, formatBytes(used)),
                style = MaterialTheme.typography.bodyLarge,
            )
            val fraction = when {
                limit > 0 -> (used.toFloat() / limit).coerceIn(0f, 1f)
                storage != null && storage.deviceFreeBytes + used > 0 -> (used.toFloat() / (storage.deviceFreeBytes + used)).coerceIn(0f, 1f)
                else -> 0f
            }
            LinearProgressIndicator(progress = { fraction }, modifier = Modifier.fillMaxWidth())
            Text(
                text = listOf(
                    stringResource(R.string.dl_storage_free, formatBytes(storage?.deviceFreeBytes ?: 0L)),
                    stringResource(R.string.dl_storage_files, storage?.fileCount ?: fileCount),
                ).joinToString(" · "),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                FilledTonalButton(onClick = onRules) {
                    Icon(Icons.AutoMirrored.Rounded.Rule, contentDescription = null, modifier = Modifier.size(18.dp))
                    Spacer(Modifier.width(8.dp))
                    Text(stringResource(R.string.dl_action_manage_rules))
                }
                TextButton(onClick = onSettings) { Text(stringResource(R.string.dl_action_settings)) }
            }
        }
    }
}

// ---- Active tab ----

@Composable
private fun ActiveTab(state: DownloadsUiState, vm: DownloadsViewModel, onTrackOptions: (Track) -> Unit) {
    val navigator = LocalNavigator.current
    LazyColumn(Modifier.fillMaxSize(), contentPadding = listContentPadding(top = 4.dp)) {
        item(key = "storage") {
            StorageCard(state.storage, state.downloadedCount, onRules = { }, onSettings = navigator::openDownloadsSettings)
        }
        if (state.active.isNotEmpty()) {
            item(key = "actions") {
                Row(
                    Modifier
                        .fillMaxWidth()
                        .padding(horizontal = Dimens.screenPadding, vertical = 4.dp),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    if (state.hasPaused) {
                        FilledTonalButton(onClick = vm::resumeAll) {
                            Icon(Icons.Rounded.PlayArrow, null, Modifier.size(18.dp)); Spacer(Modifier.width(6.dp))
                            Text(stringResource(R.string.dl_action_resume_all))
                        }
                    } else {
                        FilledTonalButton(onClick = vm::pauseAll) {
                            Icon(Icons.Rounded.Pause, null, Modifier.size(18.dp)); Spacer(Modifier.width(6.dp))
                            Text(stringResource(R.string.dl_action_pause_all))
                        }
                    }
                    if (state.hasFailed) {
                        TextButton(onClick = vm::retryFailed) { Text(stringResource(R.string.dl_action_retry_failed)) }
                    }
                }
            }
        }
        when {
            !state.isLoaded -> item(key = "loading") { LoadingPlaceholder(PlaceholderStyle.LIST, count = 4) }
            state.active.isEmpty() -> item(key = "empty") {
                EmptyState(
                    title = stringResource(R.string.dl_active_empty_title),
                    body = stringResource(R.string.dl_active_empty_body),
                    icon = Icons.Rounded.DownloadForOffline,
                )
            }
            else -> items(state.active, key = { it.id }, contentType = { "active" }) { item ->
                ActiveRow(item, onRemove = { vm.remove(listOf(item.id)) }, onOptions = { item.track?.let(onTrackOptions) })
            }
        }
    }
}

@Composable
private fun ActiveRow(item: ActiveDownloadItem, onRemove: () -> Unit, onOptions: () -> Unit) {
    val d = item.download
    val statusText = when (d.status) {
        DownloadStatus.QUEUED -> stringResource(R.string.dl_status_queued)
        DownloadStatus.DOWNLOADING -> stringResource(R.string.dl_status_downloading) + " · ${(d.progress * 100).toInt()}%"
        DownloadStatus.PAUSED -> stringResource(R.string.dl_status_paused)
        DownloadStatus.FAILED -> stringResource(R.string.dl_status_failed) + (d.error?.let { " · $it" } ?: "")
        DownloadStatus.COMPLETED -> stringResource(R.string.dl_status_completed)
    }
    Column {
        ListItem(
            headlineContent = { Text(item.track?.title ?: d.trackId, maxLines = 1, overflow = TextOverflow.Ellipsis) },
            supportingContent = {
                Text(
                    text = listOfNotNull(item.track?.displayArtist, statusText).joinToString(" · "),
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                    color = if (d.status == DownloadStatus.FAILED) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant,
                )
            },
            leadingContent = {
                Box(contentAlignment = Alignment.Center) {
                    ArtworkImage(
                        url = item.track?.artworkUrl,
                        contentDescription = null,
                        modifier = Modifier.size(Dimens.listArtwork),
                        shape = ArtworkShapes.listThumb,
                    )
                    if (d.status == DownloadStatus.FAILED) {
                        Icon(Icons.Rounded.ErrorOutline, null, tint = MaterialTheme.colorScheme.error)
                    }
                }
            },
            trailingContent = {
                IconButton(onClick = onRemove) {
                    Icon(Icons.Rounded.Close, contentDescription = stringResource(R.string.dl_action_cancel_download))
                }
            },
            colors = ListItemDefaults.colors(containerColor = Color.Transparent),
            modifier = Modifier.clickable(onClick = onOptions),
        )
        if (d.status == DownloadStatus.DOWNLOADING || d.status == DownloadStatus.QUEUED) {
            LinearProgressIndicator(
                progress = { d.progress.coerceIn(0f, 1f) },
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = Dimens.screenPadding)
                    .height(3.dp),
            )
        }
    }
}

// ---- Downloaded tab ----

@Composable
private fun DownloadedTab(state: DownloadsUiState, vm: DownloadsViewModel, onTrackOptions: (Track) -> Unit) {
    val navigator = LocalNavigator.current
    val actions: TrackActionsViewModel = hiltViewModel()
    var groupToRemove by remember { mutableStateOf<DownloadedAlbumGroup?>(null) }
    LazyColumn(Modifier.fillMaxSize(), contentPadding = listContentPadding(top = 4.dp)) {
        item(key = "storage") {
            StorageCard(state.storage, state.downloadedCount, onRules = { }, onSettings = navigator::openDownloadsSettings)
        }
        when {
            !state.isLoaded -> item(key = "loading") { LoadingPlaceholder(PlaceholderStyle.LIST, count = 6) }
            state.downloaded.isEmpty() -> item(key = "empty") {
                EmptyState(
                    title = stringResource(R.string.dl_downloaded_empty_title),
                    body = stringResource(R.string.dl_downloaded_empty_body),
                    icon = Icons.Rounded.DownloadForOffline,
                )
            }
            else -> state.downloaded.forEach { group ->
                item(key = "g-" + group.key, contentType = "group") {
                    var menuOpen by remember { mutableStateOf(false) }
                    ListItem(
                        headlineContent = { Text(group.albumName, maxLines = 1, overflow = TextOverflow.Ellipsis, style = MaterialTheme.typography.titleMedium) },
                        supportingContent = {
                            Text(
                                listOfNotNull(
                                    group.artistName,
                                    pluralStringResource(R.plurals.dl_count_songs, group.tracks.size, group.tracks.size),
                                    formatBytes(group.sizeBytes),
                                ).joinToString(" · "),
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                            )
                        },
                        leadingContent = {
                            ArtworkImage(url = group.artworkUrl, contentDescription = null, modifier = Modifier.size(Dimens.listArtwork), shape = ArtworkShapes.listThumb)
                        },
                        trailingContent = {
                            Box {
                                IconButton(onClick = { menuOpen = true }) { Icon(Icons.Rounded.MoreVert, contentDescription = stringResource(R.string.browse_action_more)) }
                                DropdownMenu(expanded = menuOpen, onDismissRequest = { menuOpen = false }) {
                                    DropdownMenuItem(
                                        text = { Text(stringResource(R.string.browse_action_play)) },
                                        leadingIcon = { Icon(Icons.Rounded.PlayArrow, null) },
                                        onClick = { menuOpen = false; actions.play(group.tracks, title = group.albumName) },
                                    )
                                    DropdownMenuItem(
                                        text = { Text(stringResource(R.string.dl_action_remove_download), color = MaterialTheme.colorScheme.error) },
                                        leadingIcon = { Icon(Icons.Rounded.Delete, null, tint = MaterialTheme.colorScheme.error) },
                                        onClick = { menuOpen = false; groupToRemove = group },
                                    )
                                }
                            }
                        },
                        colors = ListItemDefaults.colors(containerColor = Color.Transparent),
                        modifier = Modifier.clickable(enabled = group.albumId != null) { group.albumId?.let(navigator::openAlbum) },
                    )
                }
                items(group.tracks, key = { "t-" + group.key + "-" + it.id }, contentType = { "track" }) { track ->
                    TrackRow(
                        track = track,
                        onClick = { actions.play(group.tracks, group.tracks.indexOf(track), title = group.albumName) },
                        showArtwork = false,
                        subtitle = track.displayArtist,
                        isDownloaded = true,
                        onLongClick = { onTrackOptions(track) },
                        onMoreClick = { onTrackOptions(track) },
                        modifier = Modifier.padding(start = 56.dp),
                    )
                }
                item(key = "d-" + group.key) { HorizontalDivider(Modifier.padding(horizontal = Dimens.screenPadding, vertical = 4.dp)) }
            }
        }
    }
    groupToRemove?.let { group ->
        ConfirmDialog(
            title = stringResource(R.string.dl_confirm_remove_title),
            text = stringResource(R.string.dl_confirm_remove_text),
            confirmLabel = stringResource(R.string.dl_action_remove),
            onConfirm = { vm.remove(group.trackIds); groupToRemove = null },
            onDismiss = { groupToRemove = null },
        )
    }
}

// ---- Rules tab ----

@Composable
private fun RulesTab(state: DownloadsUiState, vm: DownloadsViewModel) {
    val navigator = LocalNavigator.current
    var ruleToDelete by remember { mutableStateOf<String?>(null) }
    LazyColumn(Modifier.fillMaxSize(), contentPadding = listContentPadding(top = 4.dp, extraBottom = 96.dp)) {
        item(key = "evaluate") {
            Row(
                Modifier
                    .fillMaxWidth()
                    .padding(horizontal = Dimens.screenPadding, vertical = 8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Column(Modifier.weight(1f)) {
                    Text(
                        text = if (state.ruleState.isRunning) {
                            state.ruleState.phase ?: stringResource(R.string.dl_rule_evaluating)
                        } else {
                            stringResource(R.string.dl_rule_last_evaluated, relativeTime(state.ruleState.lastSyncAt))
                        },
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    state.ruleState.lastError?.let {
                        Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error, maxLines = 2)
                    }
                }
                FilledTonalButton(onClick = vm::evaluateNow, enabled = !state.ruleState.isRunning) {
                    Icon(Icons.Rounded.Refresh, null, Modifier.size(18.dp)); Spacer(Modifier.width(6.dp))
                    Text(stringResource(R.string.dl_action_evaluate_now))
                }
            }
        }
        when {
            !state.isLoaded -> item(key = "loading") { LoadingPlaceholder(PlaceholderStyle.LIST, count = 3) }
            state.rules.isEmpty() -> item(key = "empty") {
                EmptyState(
                    title = stringResource(R.string.dl_rules_empty_title),
                    body = stringResource(R.string.dl_rules_empty_body),
                    icon = Icons.AutoMirrored.Rounded.Rule,
                    actionLabel = stringResource(R.string.dl_action_new_rule),
                    onAction = { navigator.openDownloadRule(null) },
                )
            }
            else -> items(state.rules, key = { it.rule.id }, contentType = { "rule" }) { item ->
                var menuOpen by remember { mutableStateOf(false) }
                val rule = item.rule
                ListItem(
                    headlineContent = { Text(rule.target.label, maxLines = 2, overflow = TextOverflow.Ellipsis) },
                    supportingContent = {
                        Text(
                            listOfNotNull(
                                item.profileName ?: stringResource(R.string.dl_rule_quality_default),
                                if (rule.maxTracks > 0) stringResource(R.string.dl_rule_max_tracks, rule.maxTracks) else null,
                                if (!rule.enabled) stringResource(R.string.dl_rule_disabled)
                                else stringResource(R.string.dl_rule_last_evaluated, relativeTime(rule.lastEvaluatedAt)),
                            ).joinToString(" · "),
                            maxLines = 2,
                            overflow = TextOverflow.Ellipsis,
                        )
                    },
                    leadingContent = { Icon(Icons.AutoMirrored.Rounded.Rule, contentDescription = null, tint = if (rule.enabled) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant) },
                    trailingContent = {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Switch(checked = rule.enabled, onCheckedChange = { vm.setRuleEnabled(rule, it) })
                            Box {
                                IconButton(onClick = { menuOpen = true }) { Icon(Icons.Rounded.MoreVert, contentDescription = stringResource(R.string.browse_action_more)) }
                                DropdownMenu(expanded = menuOpen, onDismissRequest = { menuOpen = false }) {
                                    DropdownMenuItem(
                                        text = { Text(stringResource(R.string.dl_action_edit)) },
                                        leadingIcon = { Icon(Icons.Rounded.Edit, null) },
                                        onClick = { menuOpen = false; navigator.openDownloadRule(rule.id) },
                                    )
                                    DropdownMenuItem(
                                        text = { Text(stringResource(R.string.dl_action_delete), color = MaterialTheme.colorScheme.error) },
                                        leadingIcon = { Icon(Icons.Rounded.Delete, null, tint = MaterialTheme.colorScheme.error) },
                                        onClick = { menuOpen = false; ruleToDelete = rule.id },
                                    )
                                }
                            }
                        }
                    },
                    colors = ListItemDefaults.colors(containerColor = Color.Transparent),
                    modifier = Modifier.clickable { navigator.openDownloadRule(rule.id) },
                )
            }
        }
    }
    ruleToDelete?.let { id ->
        DeleteRuleDialog(
            onKeepFiles = { vm.deleteRule(id, removeFiles = false); ruleToDelete = null },
            onRemoveFiles = { vm.deleteRule(id, removeFiles = true); ruleToDelete = null },
            onDismiss = { ruleToDelete = null },
        )
    }
}

@Composable
internal fun DeleteRuleDialog(onKeepFiles: () -> Unit, onRemoveFiles: () -> Unit, onDismiss: () -> Unit) {
    androidx.compose.material3.AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.dl_delete_rule_title)) },
        text = { Text(stringResource(R.string.dl_delete_rule_text)) },
        confirmButton = {
            TextButton(onClick = onRemoveFiles) { Text(stringResource(R.string.dl_action_remove_files), color = MaterialTheme.colorScheme.error) }
        },
        dismissButton = {
            Row {
                TextButton(onClick = onDismiss) { Text(stringResource(R.string.dl_action_cancel)) }
                TextButton(onClick = onKeepFiles) { Text(stringResource(R.string.dl_action_keep_files)) }
            }
        },
    )
}
