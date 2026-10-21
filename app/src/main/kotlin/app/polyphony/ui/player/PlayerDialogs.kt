package app.polyphony.ui.player

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Add
import androidx.compose.material.icons.rounded.Bedtime
import androidx.compose.material.icons.automirrored.rounded.PlaylistPlay
import androidx.compose.material.icons.rounded.Speed
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.ListItem
import androidx.compose.material3.ListItemDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Slider
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import app.polyphony.R
import app.polyphony.core.domain.model.Playlist
import app.polyphony.core.domain.model.Track
import app.polyphony.core.domain.transcode.OutputTarget
import app.polyphony.core.domain.transcode.TranscodeDecision
import app.polyphony.core.ui.util.formatAudioQuality
import app.polyphony.core.ui.util.formatBytes
import app.polyphony.core.ui.util.formatDuration
import app.polyphony.core.ui.util.formatDurationLong
import java.util.Locale
import kotlin.math.roundToInt

@Composable
fun SleepTimerDialog(
    endsAtEpochMs: Long?,
    onPickMinutes: (Int, Boolean) -> Unit,
    onEndOfTrack: () -> Unit,
    onCancelTimer: () -> Unit,
    onDismiss: () -> Unit,
) {
    val presets = listOf(5, 10, 15, 30, 45, 60, 90)
    var finishTrack by remember { mutableStateOf(false) }
    AlertDialog(
        onDismissRequest = onDismiss,
        icon = { Icon(Icons.Rounded.Bedtime, null) },
        title = { Text(stringResource(R.string.player_sleep_timer)) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                endsAtEpochMs?.let {
                    val remaining = (it - System.currentTimeMillis()).coerceAtLeast(0L)
                    Text(
                        text = stringResource(R.string.player_sleep_timer_ends, formatDurationLong(remaining)),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.primary,
                    )
                }
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
                    presets.take(4).forEach { m ->
                        FilterChip(selected = false, onClick = { onPickMinutes(m, finishTrack); onDismiss() }, label = { Text(stringResource(R.string.sleep_timer_minutes, m)) })
                    }
                }
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
                    presets.drop(4).forEach { m ->
                        FilterChip(selected = false, onClick = { onPickMinutes(m, finishTrack); onDismiss() }, label = { Text(stringResource(R.string.sleep_timer_minutes, m)) })
                    }
                    FilterChip(selected = false, onClick = { onEndOfTrack(); onDismiss() }, label = { Text(stringResource(R.string.sleep_timer_end_of_track)) })
                }
                Row(
                    modifier = Modifier.fillMaxWidth().clickable { finishTrack = !finishTrack },
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween,
                ) {
                    Text(stringResource(R.string.sleep_timer_finish_track), style = MaterialTheme.typography.bodyMedium, modifier = Modifier.weight(1f))
                    Switch(checked = finishTrack, onCheckedChange = { finishTrack = it })
                }
            }
        },
        confirmButton = {
            if (endsAtEpochMs != null) {
                TextButton(onClick = { onCancelTimer(); onDismiss() }) { Text(stringResource(R.string.sleep_timer_cancel)) }
            }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.dialog_cancel)) } },
    )
}

@Composable
fun SpeedDialog(current: Float, onChange: (Float) -> Unit, onDismiss: () -> Unit) {
    var value by remember { mutableFloatStateOf(current) }
    val presets = listOf(0.75f, 1f, 1.25f, 1.5f, 2f)
    AlertDialog(
        onDismissRequest = onDismiss,
        icon = { Icon(Icons.Rounded.Speed, null) },
        title = { Text(stringResource(R.string.player_speed)) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(
                    text = stringResource(R.string.player_speed_value, String.format(Locale.US, "%.2f", value)),
                    style = MaterialTheme.typography.headlineSmall,
                    modifier = Modifier.align(Alignment.CenterHorizontally),
                )
                Slider(
                    value = value,
                    onValueChange = { value = (it * 20).roundToInt() / 20f },
                    onValueChangeFinished = { onChange(value) },
                    valueRange = 0.5f..2f,
                )
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
                    presets.forEach { p ->
                        FilterChip(
                            selected = value == p,
                            onClick = {
                                value = p
                                onChange(p)
                            },
                            label = { Text(stringResource(R.string.player_speed_value, String.format(Locale.US, if (p % 1f == 0f) "%.0f" else "%.2f", p))) },
                        )
                    }
                }
            }
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.dialog_done)) } },
    )
}

@Composable
fun NamePromptDialog(
    title: String,
    confirmLabel: String,
    onConfirm: (String) -> Unit,
    onDismiss: () -> Unit,
) {
    var name by rememberSaveable { mutableStateOf("") }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = {
            OutlinedTextField(
                value = name,
                onValueChange = { name = it },
                label = { Text(stringResource(R.string.playlist_name)) },
                singleLine = true,
                modifier = Modifier.fillMaxWidth(),
            )
        },
        confirmButton = {
            TextButton(onClick = { onConfirm(name.trim()) }, enabled = name.isNotBlank()) { Text(confirmLabel) }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.dialog_cancel)) } },
    )
}

@Composable
fun AddToPlaylistDialog(
    playlists: List<Playlist>,
    onPick: (Playlist) -> Unit,
    onCreate: (String) -> Unit,
    onDismiss: () -> Unit,
) {
    var creating by rememberSaveable { mutableStateOf(false) }
    if (creating) {
        NamePromptDialog(
            title = stringResource(R.string.playlist_new),
            confirmLabel = stringResource(R.string.dialog_create),
            onConfirm = {
                onCreate(it)
                onDismiss()
            },
            onDismiss = { creating = false },
        )
        return
    }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.player_add_to_playlist)) },
        text = {
            Column {
                ListItem(
                    headlineContent = { Text(stringResource(R.string.playlist_new)) },
                    leadingContent = { Icon(Icons.Rounded.Add, null) },
                    colors = ListItemDefaults.colors(containerColor = Color.Transparent),
                    modifier = Modifier.clickable { creating = true },
                )
                if (playlists.isEmpty()) {
                    Text(
                        text = stringResource(R.string.playlist_none),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(16.dp),
                    )
                } else {
                    LazyColumn(Modifier.heightIn(max = 360.dp)) {
                        items(playlists, key = { it.id }) { playlist ->
                            ListItem(
                                headlineContent = { Text(playlist.name, maxLines = 1, overflow = TextOverflow.Ellipsis) },
                                supportingContent = { Text("${playlist.trackCount} songs") },
                                leadingContent = { Icon(Icons.AutoMirrored.Rounded.PlaylistPlay, null) },
                                colors = ListItemDefaults.colors(containerColor = Color.Transparent),
                                modifier = Modifier.clickable {
                                    onPick(playlist)
                                    onDismiss()
                                },
                            )
                        }
                    }
                }
            }
        },
        confirmButton = {},
        dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.dialog_cancel)) } },
    )
}

@Composable
fun SongInfoDialog(
    track: Track,
    decision: TranscodeDecision?,
    outputTarget: OutputTarget,
    onDismiss: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.player_song_info)) },
        text = {
            Column(
                modifier = Modifier.verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                InfoRow(stringResource(R.string.info_title), track.title)
                InfoRow(stringResource(R.string.info_artist), track.displayArtist)
                track.albumName?.let { InfoRow(stringResource(R.string.info_album), it) }
                track.albumArtistName?.takeIf { it != track.artistName }?.let { InfoRow(stringResource(R.string.info_album_artist), it) }
                track.year?.let { InfoRow(stringResource(R.string.info_year), it.toString()) }
                track.genre?.let { InfoRow(stringResource(R.string.info_genre), it) }
                track.trackNumber?.let { n ->
                    InfoRow(stringResource(R.string.info_track), track.discNumber?.let { d -> "$d-$n" } ?: n.toString())
                }
                InfoRow(stringResource(R.string.info_duration), formatDuration(track.durationMs))
                formatAudioQuality(track.container, track.bitrateKbps, track.sampleRateHz, track.bitDepth)?.let {
                    InfoRow(stringResource(R.string.info_format), it)
                }
                track.bitrateKbps?.takeIf { it > 0 }?.let { InfoRow(stringResource(R.string.info_bitrate), "$it kbps") }
                track.sizeBytes?.let { InfoRow(stringResource(R.string.info_size), formatBytes(it)) }
                if (track.playCount > 0) InfoRow(stringResource(R.string.info_plays), track.playCount.toString())
                InfoRow(
                    stringResource(R.string.info_output),
                    if (outputTarget is OutputTarget.Local) stringResource(R.string.info_this_device) else outputTarget.displayName,
                )
                decision?.let {
                    InfoRow(stringResource(R.string.info_streaming_as), "${it.profile.qualityLabel()} (${it.explanation})")
                }
                track.path?.let { InfoRow(stringResource(R.string.info_path), it) }
            }
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.dialog_done)) } },
    )
}

@Composable
private fun InfoRow(label: String, value: String) {
    Column {
        Text(label, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Text(value, style = MaterialTheme.typography.bodyMedium)
    }
}

