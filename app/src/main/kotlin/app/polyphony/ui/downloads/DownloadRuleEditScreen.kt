package app.polyphony.ui.downloads

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Check
import androidx.compose.material.icons.rounded.Delete
import androidx.compose.material.icons.rounded.LibraryMusic
import androidx.compose.material.icons.rounded.Search
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ListItem
import androidx.compose.material3.ListItemDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.polyphony.R
import app.polyphony.core.ui.components.ArtworkImage
import app.polyphony.core.ui.components.LoadingPlaceholder
import app.polyphony.core.ui.components.PlaceholderStyle
import app.polyphony.core.ui.theme.ArtworkShapes
import app.polyphony.navigation.LocalNavigator
import app.polyphony.ui.settings.components.ActionPreference
import app.polyphony.ui.settings.components.ListOption
import app.polyphony.ui.settings.components.ListPreference
import app.polyphony.ui.settings.components.PreferenceNote
import app.polyphony.ui.settings.components.SettingsScaffold
import app.polyphony.ui.settings.components.SliderPreference
import app.polyphony.ui.settings.components.SwitchPreference
import app.polyphony.ui.settings.components.preferenceCategory

@Composable
fun DownloadRuleEditScreen(viewModel: DownloadRuleEditViewModel = hiltViewModel()) {
    val navigator = LocalNavigator.current
    val draft by viewModel.draft.collectAsStateWithLifecycle()
    val loaded by viewModel.loaded.collectAsStateWithLifecycle()
    val profiles by viewModel.profiles.collectAsStateWithLifecycle()
    val snackbar = remember { SnackbarHostState() }
    var showPicker by rememberSaveable { mutableStateOf(false) }
    var showDelete by rememberSaveable { mutableStateOf(false) }
    val needsTargetMessage = stringResource(R.string.dl_rule_needs_target)

    LaunchedEffect(Unit) {
        viewModel.events.collect { event ->
            when (event) {
                RuleEditEvent.Saved, RuleEditEvent.Deleted -> navigator.back()
                is RuleEditEvent.Error -> snackbar.showSnackbar(if (event.message == "needs_target") needsTargetMessage else event.message)
            }
        }
    }

    val typeOptions = remember {
        listOf(
            ListOption(RuleTargetType.FAVORITES, "Favourite songs"),
            ListOption(RuleTargetType.PLAYLIST, "Playlist"),
            ListOption(RuleTargetType.ALBUM, "Album"),
            ListOption(RuleTargetType.ARTIST, "Artist"),
            ListOption(RuleTargetType.GENRE, "Genre"),
            ListOption(RuleTargetType.RECENTLY_ADDED, "Recently added albums"),
            ListOption(RuleTargetType.MOST_PLAYED, "Most played songs"),
            ListOption(RuleTargetType.RECENTLY_PLAYED, "Recently played songs"),
        )
    }
    val maxOptions = remember {
        listOf(0, 25, 50, 100, 250, 500, 1000).map { ListOption(it, if (it == 0) "No limit" else "$it songs") }
    }

    SettingsScaffold(
        title = stringResource(if (viewModel.isNew) R.string.dl_rule_new_title else R.string.dl_rule_edit_title),
        onBack = { navigator.back() },
        snackbarHostState = snackbar,
        actions = {
            IconButton(onClick = viewModel::save, enabled = loaded && draft.isValid) {
                Icon(Icons.Rounded.Check, contentDescription = stringResource(R.string.dl_action_save))
            }
        },
    ) {
        if (!loaded) {
            item(key = "loading") { LoadingPlaceholder(PlaceholderStyle.LIST, count = 4) }
            return@SettingsScaffold
        }
        preferenceCategory(key = "what", titleRes = R.string.dl_rule_section_what) {
            ListPreference(
                title = stringResource(R.string.dl_rule_section_what),
                options = typeOptions,
                selected = draft.type,
                onSelected = viewModel::setType,
            )
            if (draft.type.needsEntity) {
                ActionPreference(
                    title = stringResource(
                        when (draft.type) {
                            RuleTargetType.PLAYLIST -> R.string.dl_rule_pick_playlist
                            RuleTargetType.ALBUM -> R.string.dl_rule_pick_album
                            RuleTargetType.ARTIST -> R.string.dl_rule_pick_artist
                            else -> R.string.dl_rule_pick_genre
                        },
                    ),
                    summary = draft.entityName ?: stringResource(R.string.dl_rule_nothing_selected),
                    icon = Icons.Rounded.LibraryMusic,
                    onClick = { showPicker = true },
                )
            }
            if (draft.type.needsCount) {
                SliderPreference(
                    title = stringResource(if (draft.type == RuleTargetType.RECENTLY_ADDED) R.string.dl_rule_count_albums else R.string.dl_rule_count_tracks),
                    value = draft.count.toFloat(),
                    valueRange = 5f..500f,
                    steps = 98,
                    valueLabel = { it.toInt().toString() },
                    onValueChangeFinished = { viewModel.setCount(it.toInt()) },
                )
            }
        }
        preferenceCategory(key = "options", titleRes = R.string.dl_rule_section_options) {
            val profileOptions = buildList {
                add(ListOption<String?>(null, stringResource(R.string.dl_rule_quality_use_default)))
                profiles.forEach { add(ListOption<String?>(it.id, it.name, it.summary)) }
            }
            ListPreference(
                title = stringResource(R.string.dl_rule_quality),
                options = profileOptions,
                selected = draft.profileId,
                onSelected = viewModel::setProfileId,
            )
            ListPreference(
                title = stringResource(R.string.dl_rule_max_tracks_title),
                options = maxOptions,
                selected = draft.maxTracks,
                onSelected = viewModel::setMaxTracks,
            )
            SwitchPreference(
                title = stringResource(R.string.dl_rule_enabled),
                summary = stringResource(R.string.dl_rule_enabled_summary),
                checked = draft.enabled,
                onCheckedChange = viewModel::setEnabled,
            )
        }
        if (!viewModel.isNew) {
            preferenceCategory(key = "danger") {
                ActionPreference(
                    title = stringResource(R.string.dl_action_delete),
                    icon = Icons.Rounded.Delete,
                    destructive = true,
                    showChevron = false,
                    onClick = { showDelete = true },
                )
            }
        }
        item(key = "note") { PreferenceNote(stringResource(R.string.dl_rules_empty_body)) }
    }

    if (showPicker) {
        val items by when (draft.type) {
            RuleTargetType.PLAYLIST -> viewModel.playlists
            RuleTargetType.ALBUM -> viewModel.albums
            RuleTargetType.ARTIST -> viewModel.artists
            else -> viewModel.genres
        }.collectAsStateWithLifecycle()
        EntityPickerSheet(
            items = items,
            onPick = { viewModel.setEntity(it); showPicker = false },
            onDismiss = { showPicker = false },
        )
    }
    if (showDelete) {
        DeleteRuleDialog(
            onKeepFiles = { showDelete = false; viewModel.delete(removeFiles = false) },
            onRemoveFiles = { showDelete = false; viewModel.delete(removeFiles = true) },
            onDismiss = { showDelete = false },
        )
    }
}

@Composable
private fun EntityPickerSheet(
    items: List<PickableEntity>,
    onPick: (PickableEntity) -> Unit,
    onDismiss: () -> Unit,
) {
    var query by rememberSaveable { mutableStateOf("") }
    val filtered = remember(items, query) {
        if (query.isBlank()) items else items.filter { it.name.contains(query, ignoreCase = true) || it.subtitle?.contains(query, ignoreCase = true) == true }
    }
    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)) {
        Column(Modifier.navigationBarsPadding()) {
            OutlinedTextField(
                value = query,
                onValueChange = { query = it },
                placeholder = { Text(stringResource(R.string.dl_rule_pick_search)) },
                leadingIcon = { Icon(Icons.Rounded.Search, contentDescription = null) },
                singleLine = true,
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp, vertical = 4.dp),
            )
            LazyColumn(Modifier.fillMaxWidth()) {
                items(filtered, key = { it.id }) { entity ->
                    ListItem(
                        headlineContent = { Text(entity.name, maxLines = 1, overflow = TextOverflow.Ellipsis) },
                        supportingContent = entity.subtitle?.let { { Text(it, maxLines = 1, overflow = TextOverflow.Ellipsis) } },
                        leadingContent = {
                            ArtworkImage(url = entity.artworkUrl, contentDescription = null, modifier = Modifier.size(44.dp), shape = ArtworkShapes.listThumb)
                        },
                        colors = ListItemDefaults.colors(containerColor = Color.Transparent),
                        modifier = Modifier.clickable { onPick(entity) },
                    )
                }
                item { Spacer(Modifier.height(24.dp)) }
            }
            if (filtered.isEmpty()) {
                Text(
                    text = stringResource(R.string.dl_rule_nothing_selected),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(24.dp),
                )
            }
        }
    }
}
