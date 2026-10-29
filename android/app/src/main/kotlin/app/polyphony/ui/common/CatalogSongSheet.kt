package app.polyphony.ui.common

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.PlaylistPlay
import androidx.compose.material.icons.automirrored.rounded.QueueMusic
import androidx.compose.material.icons.rounded.Album
import androidx.compose.material.icons.rounded.PlayArrow
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.res.stringResource
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.polyphony.R
import app.polyphony.core.domain.model.CatalogTrack

/**
 * The long-press sheet of a song you don't have (polyphony#164): play it now, or queue it to play
 * next or at the end while continuo fetches it in turn; or open its album. Without Play now on the
 * server only the album is offered.
 */
@Composable
fun CatalogSongSheet(track: CatalogTrack, onOpenAlbum: (() -> Unit)?, onDismiss: () -> Unit) {
    val playNow = hiltViewModel<PlayNowViewModel>().playNow
    val available by playNow.available.collectAsStateWithLifecycle()
    val playable = available && track.recordingId != null
    OptionsSheet(
        onDismiss = onDismiss,
        header = { SheetHeader(null, track.title, listOfNotNull(track.artist, track.albumTitle).joinToString(" · ").ifBlank { null }) },
    ) {
        if (playable) {
            SheetItem(Icons.Rounded.PlayArrow, stringResource(R.string.playnow_action_play)) {
                onDismiss(); playNow.play(track)
            }
            SheetItem(Icons.AutoMirrored.Rounded.PlaylistPlay, stringResource(R.string.browse_action_play_next)) {
                onDismiss(); playNow.queue(track, next = true)
            }
            SheetItem(Icons.AutoMirrored.Rounded.QueueMusic, stringResource(R.string.browse_action_add_to_queue)) {
                onDismiss(); playNow.queue(track, next = false)
            }
        }
        if (onOpenAlbum != null) {
            SheetItem(Icons.Rounded.Album, stringResource(R.string.browse_action_go_to_album)) {
                onDismiss(); onOpenAlbum()
            }
        }
    }
}
