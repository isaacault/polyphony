package app.polyphony.ui.browse.smart

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.rounded.Add
import androidx.compose.material.icons.rounded.AutoAwesome
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.Delete
import androidx.compose.material.icons.rounded.Edit
import androidx.compose.material.icons.rounded.PushPin
import androidx.compose.material.icons.outlined.PushPin
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.polyphony.R
import app.polyphony.core.domain.model.SmartField
import app.polyphony.core.domain.model.SmartOp
import app.polyphony.core.domain.model.SmartPlaylist
import app.polyphony.core.domain.model.SmartRule
import app.polyphony.core.domain.model.Track
import app.polyphony.core.domain.model.TrackSort
import app.polyphony.core.ui.components.EmptyState
import app.polyphony.core.ui.components.LoadingPlaceholder
import app.polyphony.core.ui.components.PlaceholderStyle
import app.polyphony.core.ui.components.PlayShuffleButtons
import app.polyphony.core.ui.components.TrackRow
import app.polyphony.core.ui.components.listContentPadding
import app.polyphony.navigation.LocalNavigator
import app.polyphony.ui.common.TrackOptionsSheet
import app.polyphony.ui.common.TrackSwipeRow

// ---- labels ----

@Composable
internal fun fieldLabel(field: SmartField): String = stringResource(
    when (field) {
        SmartField.TITLE -> R.string.smart_field_title
        SmartField.ARTIST -> R.string.smart_field_artist
        SmartField.ALBUM -> R.string.smart_field_album
        SmartField.GENRE -> R.string.smart_field_genre
        SmartField.FORMAT -> R.string.smart_field_format
        SmartField.YEAR -> R.string.smart_field_year
        SmartField.RATING -> R.string.smart_field_rating
        SmartField.PLAY_COUNT -> R.string.smart_field_play_count
        SmartField.DURATION_MINUTES -> R.string.smart_field_duration
        SmartField.BITRATE_KBPS -> R.string.smart_field_bitrate
        SmartField.ADDED -> R.string.smart_field_added
        SmartField.LAST_PLAYED -> R.string.smart_field_last_played
        SmartField.FAVOURITE -> R.string.smart_field_favourite
    },
)

@Composable
internal fun opLabel(op: SmartOp): String = stringResource(
    when (op) {
        SmartOp.IS -> R.string.smart_op_is
        SmartOp.IS_NOT -> R.string.smart_op_is_not
        SmartOp.CONTAINS -> R.string.smart_op_contains
        SmartOp.NOT_CONTAINS -> R.string.smart_op_not_contains
        SmartOp.AT_LEAST -> R.string.smart_op_at_least
        SmartOp.AT_MOST -> R.string.smart_op_at_most
        SmartOp.WITHIN_DAYS -> R.string.smart_op_within
        SmartOp.NOT_WITHIN_DAYS -> R.string.smart_op_not_within
        SmartOp.IS_TRUE -> R.string.smart_op_yes
        SmartOp.IS_FALSE -> R.string.smart_op_no
    },
)

@Composable
private fun sortLabel(sort: TrackSort): String = stringResource(
    when (sort) {
        TrackSort.TITLE -> R.string.smart_sort_title
        TrackSort.ARTIST -> R.string.smart_sort_artist
        TrackSort.ALBUM -> R.string.smart_sort_album
        TrackSort.DURATION -> R.string.smart_sort_duration
        TrackSort.RECENTLY_ADDED -> R.string.smart_sort_added
        TrackSort.MOST_PLAYED -> R.string.smart_sort_most_played
        TrackSort.RATING -> R.string.smart_sort_rating
    },
)

/** "Genre contains jazz and Added in the last (days) 30", or "Every song". */
@Composable
internal fun describe(playlist: SmartPlaylist): String {
    val rules = playlist.activeRules
    if (rules.isEmpty()) return stringResource(R.string.smart_every_song)
    val join = " " + stringResource(if (playlist.matchAll) R.string.smart_join_all else R.string.smart_join_any) + " "
    return rules.map { rule ->
        listOfNotNull(fieldLabel(rule.field), opLabel(rule.op), rule.value.trim().takeIf { rule.field.kind != SmartField.Kind.FLAG })
            .joinToString(" ")
    }.joinToString(join)
}

// ---- the playlist ----

@Composable
fun SmartPlaylistScreen(viewModel: SmartPlaylistViewModel = hiltViewModel()) {
    val navigator = LocalNavigator.current
    val playlist by viewModel.playlist.collectAsStateWithLifecycle()
    val tracks by viewModel.tracks.collectAsStateWithLifecycle()
    val playback by viewModel.playbackState.collectAsStateWithLifecycle()
    var confirmDelete by remember { mutableStateOf(false) }
    var trackForOptions by remember { mutableStateOf<Track?>(null) }

    Scaffold(
        contentWindowInsets = WindowInsets(0, 0, 0, 0),
        topBar = {
            TopAppBar(
                title = { Text(playlist?.name.orEmpty()) },
                navigationIcon = {
                    IconButton(onClick = { navigator.back() }) {
                        Icon(Icons.AutoMirrored.Rounded.ArrowBack, contentDescription = stringResource(R.string.browse_action_back))
                    }
                },
                actions = {
                    val p = playlist
                    if (p != null) {
                        IconButton(onClick = viewModel::togglePinnedToHome) {
                            Icon(
                                if (p.pinnedToHome) Icons.Rounded.PushPin else Icons.Outlined.PushPin,
                                contentDescription = stringResource(if (p.pinnedToHome) R.string.smart_unpin else R.string.smart_pin),
                            )
                        }
                        IconButton(onClick = { navigator.openSmartPlaylistEditor(p.id) }) {
                            Icon(Icons.Rounded.Edit, contentDescription = stringResource(R.string.smart_edit))
                        }
                        IconButton(onClick = { confirmDelete = true }) {
                            Icon(Icons.Rounded.Delete, contentDescription = stringResource(R.string.smart_delete))
                        }
                    }
                },
            )
        },
    ) { padding ->
        val list = tracks
        Column(Modifier.fillMaxSize().padding(padding)) {
            playlist?.let {
                Text(
                    describe(it),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp),
                )
            }
            when {
                list == null -> LoadingPlaceholder(PlaceholderStyle.LIST)
                list.isEmpty() -> EmptyState(
                    title = stringResource(R.string.smart_empty),
                    body = stringResource(R.string.smart_empty_hint),
                    icon = Icons.Rounded.AutoAwesome,
                )
                else -> LazyColumn(modifier = Modifier.fillMaxSize(), contentPadding = listContentPadding(top = 4.dp)) {
                    item(key = "header") {
                        PlayShuffleButtons(
                            onPlay = { viewModel.playAll(false) },
                            onShuffle = { viewModel.playAll(true) },
                            playLabel = stringResource(R.string.browse_action_play_all),
                            shuffleLabel = stringResource(R.string.browse_action_shuffle),
                            modifier = Modifier.padding(vertical = 8.dp),
                        )
                    }
                    itemsIndexed(list, key = { _, track -> track.id }, contentType = { _, _ -> "track" }) { index, track ->
                        TrackSwipeRow(track = track) {
                            TrackRow(
                                track = track,
                                onClick = { viewModel.playTrack(index) },
                                isCurrent = track.id == playback.currentTrack?.id,
                                isPlaying = playback.isPlaying,
                                onLongClick = { trackForOptions = track },
                                onMoreClick = { trackForOptions = track },
                            )
                        }
                    }
                }
            }
        }
    }
    if (confirmDelete) {
        AlertDialog(
            onDismissRequest = { confirmDelete = false },
            title = { Text(stringResource(R.string.smart_delete_title)) },
            text = { Text(stringResource(R.string.smart_delete_body)) },
            confirmButton = {
                TextButton(onClick = {
                    confirmDelete = false
                    viewModel.delete { navigator.back() }
                }) { Text(stringResource(R.string.smart_delete)) }
            },
            dismissButton = { TextButton(onClick = { confirmDelete = false }) { Text(stringResource(android.R.string.cancel)) } },
        )
    }
    trackForOptions?.let { TrackOptionsSheet(track = it, onDismiss = { trackForOptions = null }) }
}

// ---- the editor ----

@Composable
fun SmartPlaylistEditScreen(viewModel: SmartPlaylistEditViewModel = hiltViewModel()) {
    val navigator = LocalNavigator.current
    val draft by viewModel.draft.collectAsStateWithLifecycle()
    val matches by viewModel.matches.collectAsStateWithLifecycle()
    val defaultName = stringResource(R.string.smart_default_name)

    Scaffold(
        contentWindowInsets = WindowInsets(0, 0, 0, 0),
        topBar = {
            TopAppBar(
                title = { Text(stringResource(if (viewModel.isNew) R.string.smart_title_new else R.string.smart_title_edit)) },
                navigationIcon = {
                    IconButton(onClick = { navigator.back() }) {
                        Icon(Icons.Rounded.Close, contentDescription = stringResource(R.string.browse_action_back))
                    }
                },
                actions = {
                    TextButton(onClick = {
                        viewModel.save(defaultName) { id ->
                            navigator.back()
                            if (viewModel.isNew) navigator.openSmartPlaylist(id)
                        }
                    }) { Text(stringResource(R.string.smart_save)) }
                },
            )
        },
    ) { padding ->
        LazyColumn(
            modifier = Modifier.fillMaxSize().padding(padding),
            contentPadding = listContentPadding(top = 8.dp, horizontal = 16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            item(key = "name") {
                OutlinedTextField(
                    value = draft.name,
                    onValueChange = viewModel::setName,
                    label = { Text(stringResource(R.string.smart_name)) },
                    placeholder = { Text(defaultName) },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
            }
            item(key = "match") {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(stringResource(R.string.smart_rules), style = MaterialTheme.typography.titleSmall)
                    FilterChip(selected = draft.matchAll, onClick = { viewModel.setMatchAll(true) }, label = { Text(stringResource(R.string.smart_match_all)) })
                    FilterChip(selected = !draft.matchAll, onClick = { viewModel.setMatchAll(false) }, label = { Text(stringResource(R.string.smart_match_any)) })
                }
            }
            itemsIndexed(draft.rules, key = { index, _ -> "rule-$index" }) { index, rule ->
                RuleEditor(
                    rule = rule,
                    onField = { viewModel.setField(index, it) },
                    onOp = { viewModel.setOp(index, it) },
                    onValue = { viewModel.setValue(index, it) },
                    onRemove = { viewModel.removeRule(index) },
                )
            }
            item(key = "add") {
                OutlinedButton(onClick = viewModel::addRule) {
                    Icon(Icons.Rounded.Add, contentDescription = null)
                    Text(stringResource(R.string.smart_add_rule), modifier = Modifier.padding(start = 8.dp))
                }
            }
            item(key = "order") {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(stringResource(R.string.smart_order), style = MaterialTheme.typography.titleSmall)
                    Picker(TrackSort.entries, draft.sort, { sortLabel(it) }, viewModel::setSort)
                    Text(stringResource(R.string.smart_reversed), style = MaterialTheme.typography.bodyMedium)
                    Switch(checked = !draft.ascending, onCheckedChange = { viewModel.setAscending(!it) })
                }
            }
            item(key = "limit") {
                OutlinedTextField(
                    value = if (draft.limit > 0) draft.limit.toString() else "",
                    onValueChange = viewModel::setLimit,
                    label = { Text(stringResource(R.string.smart_limit)) },
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                    modifier = Modifier.fillMaxWidth(),
                )
            }
            item(key = "matches") {
                val n = matches
                Text(
                    if (n == null) stringResource(R.string.smart_counting) else pluralStringResource(R.plurals.smart_matches, n, n),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}

@Composable
private fun RuleEditor(
    rule: SmartRule,
    onField: (SmartField) -> Unit,
    onOp: (SmartOp) -> Unit,
    onValue: (String) -> Unit,
    onRemove: () -> Unit,
) {
    Surface(shape = MaterialTheme.shapes.medium, color = MaterialTheme.colorScheme.surfaceContainer, modifier = Modifier.fillMaxWidth()) {
        Column(Modifier.padding(start = 12.dp, top = 4.dp, bottom = 8.dp, end = 4.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Picker(SmartField.entries, rule.field, { fieldLabel(it) }, onField)
                Picker(rule.field.ops, rule.op, { opLabel(it) }, onOp)
                Box(Modifier.weight(1f))
                IconButton(onClick = onRemove) {
                    Icon(Icons.Rounded.Close, contentDescription = stringResource(R.string.smart_remove_rule))
                }
            }
            if (rule.field.kind != SmartField.Kind.FLAG) {
                val numeric = rule.field.kind != SmartField.Kind.TEXT
                OutlinedTextField(
                    value = rule.value,
                    onValueChange = { onValue(if (numeric) it.filter(Char::isDigit).take(6) else it) },
                    label = { Text(stringResource(R.string.smart_value)) },
                    singleLine = true,
                    isError = rule.value.isNotEmpty() && !rule.isValid,
                    keyboardOptions = KeyboardOptions(keyboardType = if (numeric) KeyboardType.Number else KeyboardType.Text),
                    modifier = Modifier.fillMaxWidth().padding(end = 8.dp),
                )
            }
        }
    }
}

/** A button showing [selected] that opens a menu of [options]. */
@Composable
private fun <T> Picker(options: List<T>, selected: T, label: @Composable (T) -> String, onSelect: (T) -> Unit) {
    var open by remember { mutableStateOf(false) }
    Box {
        TextButton(onClick = { open = true }) { Text(label(selected)) }
        DropdownMenu(expanded = open, onDismissRequest = { open = false }) {
            for (option in options) {
                DropdownMenuItem(
                    text = { Text(label(option)) },
                    onClick = {
                        open = false
                        onSelect(option)
                    },
                )
            }
        }
    }
}
