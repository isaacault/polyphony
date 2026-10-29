package app.polyphony.ui.common

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp
import app.polyphony.R
import app.polyphony.core.domain.model.Track
import app.polyphony.core.ui.util.formatBytes
import app.polyphony.core.ui.util.formatDuration

@Composable
fun ConfirmDialog(
    title: String,
    text: String,
    onConfirm: () -> Unit,
    onDismiss: () -> Unit,
    confirmLabel: String = stringResource(R.string.browse_action_confirm),
    destructive: Boolean = false,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = { Text(text) },
        confirmButton = {
            TextButton(
                onClick = { onConfirm(); onDismiss() },
                colors = if (destructive) ButtonDefaults.textButtonColors(contentColor = MaterialTheme.colorScheme.error) else ButtonDefaults.textButtonColors(),
            ) { Text(confirmLabel) }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.browse_action_cancel)) } },
    )
}

/** Single text-field dialog used for rename and create flows. */
@Composable
fun RenameDialog(
    title: String,
    onConfirm: (String) -> Unit,
    onDismiss: () -> Unit,
    initialValue: String = "",
    label: String = stringResource(R.string.browse_label_name),
    confirmLabel: String = stringResource(R.string.browse_action_save),
) {
    var value by rememberSaveable(initialValue) { mutableStateOf(initialValue) }
    val focusRequester = remember { FocusRequester() }
    LaunchedEffect(Unit) { focusRequester.requestFocus() }
    val canConfirm = value.isNotBlank()
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = {
            OutlinedTextField(
                value = value,
                onValueChange = { value = it },
                label = { Text(label) },
                singleLine = true,
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done),
                keyboardActions = KeyboardActions(onDone = { if (canConfirm) { onConfirm(value.trim()); onDismiss() } }),
                modifier = Modifier
                    .fillMaxWidth()
                    .focusRequester(focusRequester),
            )
        },
        confirmButton = {
            TextButton(onClick = { onConfirm(value.trim()); onDismiss() }, enabled = canConfirm) { Text(confirmLabel) }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.browse_action_cancel)) } },
    )
}

@Composable
fun SongInfoDialog(track: Track, onDismiss: () -> Unit) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(track.title) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                InfoRow(stringResource(R.string.browse_info_artist), track.displayArtist)
                track.albumName?.let { InfoRow(stringResource(R.string.browse_info_album), it) }
                track.albumArtistName?.takeIf { it != track.artistName }?.let { InfoRow(stringResource(R.string.browse_info_album_artist), it) }
                track.year?.let { InfoRow(stringResource(R.string.browse_info_year), it.toString()) }
                track.genre?.let { InfoRow(stringResource(R.string.browse_info_genre), it) }
                val position = listOfNotNull(track.discNumber?.let { "Disc $it" }, track.trackNumber?.let { "Track $it" })
                if (position.isNotEmpty()) InfoRow(stringResource(R.string.browse_info_position), position.joinToString(" · "))
                if (track.durationMs > 0) InfoRow(stringResource(R.string.browse_info_duration), formatDuration(track.durationMs))
                track.container?.let { InfoRow(stringResource(R.string.browse_info_format), it.uppercase()) }
                track.bitrateKbps?.takeIf { it > 0 }?.let { InfoRow(stringResource(R.string.browse_info_bitrate), "$it kbps") }
                track.sampleRateHz?.let { InfoRow(stringResource(R.string.browse_info_sample_rate), "${it / 1000f} kHz".replace(".0 kHz", " kHz")) }
                track.bitDepth?.let { InfoRow(stringResource(R.string.browse_info_bit_depth), "$it-bit") }
                track.channels?.let { InfoRow(stringResource(R.string.browse_info_channels), it.toString()) }
                track.sizeBytes?.let { InfoRow(stringResource(R.string.browse_info_size), formatBytes(it)) }
                if (track.playCount > 0) InfoRow(stringResource(R.string.browse_info_play_count), track.playCount.toString())
                track.replayGainTrackDb?.let { InfoRow(stringResource(R.string.browse_info_replay_gain), "%.2f dB".format(it)) }
                track.path?.let { InfoRow(stringResource(R.string.browse_info_path), it) }
            }
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.browse_action_close)) } },
    )
}

@Composable
private fun InfoRow(label: String, value: String) {
    Row(Modifier.fillMaxWidth().padding(vertical = 2.dp)) {
        Text(
            label,
            style = MaterialTheme.typography.labelLarge,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.weight(0.4f),
        )
        Text(value, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.weight(0.6f))
    }
}
