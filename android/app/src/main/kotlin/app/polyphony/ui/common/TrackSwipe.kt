package app.polyphony.ui.common

import androidx.compose.foundation.layout.Box
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.PlaylistAdd
import androidx.compose.material.icons.automirrored.rounded.PlaylistPlay
import androidx.compose.material.icons.automirrored.rounded.QueueMusic
import androidx.compose.material.icons.rounded.Delete
import androidx.compose.material.icons.rounded.Download
import androidx.compose.material.icons.rounded.Favorite
import androidx.compose.material.icons.rounded.FavoriteBorder
import androidx.compose.material.icons.rounded.Info
import androidx.compose.material.icons.rounded.Star
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SnackbarDuration
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.SnackbarResult
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.Stable
import androidx.compose.runtime.State
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalResources
import androidx.compose.ui.res.stringResource
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import app.polyphony.R
import app.polyphony.core.domain.model.Availability
import app.polyphony.core.domain.model.CatalogTrack
import app.polyphony.core.domain.model.ItemKind
import app.polyphony.core.domain.model.Track
import app.polyphony.core.domain.playback.PlaybackController
import app.polyphony.core.domain.repository.DownloadRepository
import app.polyphony.core.domain.repository.FeedbackRepository
import app.polyphony.core.domain.repository.LibraryRepository
import app.polyphony.core.domain.repository.SettingsRepository
import app.polyphony.core.domain.repository.TrackSwipeAction
import app.polyphony.core.ui.components.SwipeActionRow
import app.polyphony.core.ui.components.SwipeActionSpec
import app.polyphony.ui.player.Haptics
import app.polyphony.ui.player.rememberHaptics
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import javax.inject.Inject

/** The user's swipe preferences plus whether haptics are on, as one stable value for rows. */
@Immutable
data class TrackSwipeConfig(
    val leftToRight: TrackSwipeAction = TrackSwipeAction.ADD_TO_QUEUE,
    val rightToLeft: TrackSwipeAction = TrackSwipeAction.NONE,
    val haptics: Boolean = true,
    /** Ratings mode: the favourite action rates instead (it opens the 1–5 picker). */
    val usesRatings: Boolean = false,
)

@HiltViewModel
class TrackSwipeViewModel @Inject constructor(
    private val playback: PlaybackController,
    private val library: LibraryRepository,
    private val downloads: DownloadRepository,
    settings: SettingsRepository,
    feedback: FeedbackRepository,
) : ViewModel() {

    val config: StateFlow<TrackSwipeConfig> = combine(settings.settings, feedback.feedback) { s, f ->
        TrackSwipeConfig(s.appearance.swipeLeftToRight, s.appearance.swipeRightToLeft, s.playback.hapticFeedback, f.usesRatings)
    }
        .distinctUntilChanged()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), TrackSwipeConfig())

    val downloadedIds: StateFlow<Set<String>> = downloads.downloadedTrackIds()
        .distinctUntilChanged()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptySet())

    fun addToQueue(track: Track) = playback.addToQueue(listOf(track))
    fun playNext(track: Track) = playback.playNext(listOf(track))

    /** Undo for a queue add: drops the copy of [track] that sits right after the current item. */
    fun removeNextCopy(track: Track) {
        val state = playback.state.value
        val next = state.currentIndex + 1
        val index = if (state.queue.getOrNull(next)?.id == track.id) next else state.queue.indexOfLast { it.id == track.id }
        if (index >= 0) playback.removeFromQueue(index)
    }

    /** Undo for an append: drops the last copy of [track] in the queue. */
    fun removeLastCopy(track: Track) {
        val index = playback.state.value.queue.indexOfLast { it.id == track.id }
        if (index >= 0) playback.removeFromQueue(index)
    }

    fun rate(track: Track, stars: Int) {
        viewModelScope.launch { runCatching { library.setRating(track.id, ItemKind.TRACK, stars) } }
    }

    fun setStarred(track: Track, starred: Boolean) {
        viewModelScope.launch { runCatching { library.setStarred(track.id, ItemKind.TRACK, starred) } }
    }

    fun download(track: Track) {
        viewModelScope.launch { runCatching { downloads.download(listOf(track), pinned = true) } }
    }

    fun removeDownload(track: Track) {
        viewModelScope.launch { runCatching { downloads.remove(listOf(track.id)) } }
    }
}

/**
 * What a swipeable row needs from its screen: the live config, the downloaded set (so the download
 * action reads "remove" when it applies), haptics, and somewhere to run the action.
 */
@Stable
class TrackSwipeHost internal constructor(
    val config: State<TrackSwipeConfig>,
    val downloadedIds: State<Set<String>>,
    val haptics: Haptics,
    private val perform: State<(Track, TrackSwipeAction) -> Unit>,
) {
    fun perform(track: Track, action: TrackSwipeAction) = perform.value(track, action)
}

/** Null outside [TrackSwipeScope]; rows then render without gestures (previews, tests). */
val LocalTrackSwipe = staticCompositionLocalOf<TrackSwipeHost?> { null }

/**
 * Hosts the swipe-action plumbing once for everything beneath it: the view model, the snackbar
 * confirmations with Undo, and the sheet / dialog that two of the actions open. Place it at the
 * shell so screens only wrap their rows in [TrackSwipeRow].
 */
@Composable
fun TrackSwipeScope(
    snackbarHostState: SnackbarHostState,
    viewModel: TrackSwipeViewModel = hiltViewModel(),
    content: @Composable () -> Unit,
) {
    val config = viewModel.config.collectAsStateWithLifecycle()
    val downloaded = viewModel.downloadedIds.collectAsStateWithLifecycle()
    val haptics = rememberHaptics(config.value.haptics)
    val scope = rememberCoroutineScope()
    val resources = LocalResources.current
    var playlistTrack by remember { mutableStateOf<Track?>(null) }
    var infoTrack by remember { mutableStateOf<Track?>(null) }
    var rateTrack by remember { mutableStateOf<Track?>(null) }

    fun snack(messageRes: Int, undo: (() -> Unit)? = null) {
        scope.launch {
            snackbarHostState.currentSnackbarData?.dismiss()
            val result = snackbarHostState.showSnackbar(
                message = resources.getString(messageRes),
                actionLabel = undo?.let { resources.getString(R.string.browse_action_undo) },
                duration = SnackbarDuration.Short,
            )
            if (result == SnackbarResult.ActionPerformed) undo?.invoke()
        }
    }

    val perform = rememberUpdatedState<(Track, TrackSwipeAction) -> Unit> { track, action ->
        when (action) {
            TrackSwipeAction.NONE -> Unit
            TrackSwipeAction.ADD_TO_QUEUE -> {
                viewModel.addToQueue(track)
                snack(R.string.browse_snack_added_to_queue) { viewModel.removeLastCopy(track) }
            }
            TrackSwipeAction.PLAY_NEXT -> {
                viewModel.playNext(track)
                snack(R.string.browse_snack_playing_next) { viewModel.removeNextCopy(track) }
            }
            TrackSwipeAction.ADD_TO_PLAYLIST -> { playlistTrack = track }
            TrackSwipeAction.TOGGLE_FAVORITE -> if (config.value.usesRatings) {
                rateTrack = track
            } else {
                val starred = !track.starred
                viewModel.setStarred(track, starred)
                snack(if (starred) R.string.browse_snack_added_favorite else R.string.browse_snack_removed_favorite) {
                    viewModel.setStarred(track, !starred)
                }
            }
            TrackSwipeAction.DOWNLOAD -> if (track.id in downloaded.value) {
                viewModel.removeDownload(track)
                snack(R.string.browse_snack_download_removed) { viewModel.download(track) }
            } else {
                viewModel.download(track)
                snack(R.string.browse_snack_download_started) { viewModel.removeDownload(track) }
            }
            TrackSwipeAction.SONG_INFO -> { infoTrack = track }
        }
    }
    val host = remember(config, downloaded, haptics, perform) { TrackSwipeHost(config, downloaded, haptics, perform) }

    CompositionLocalProvider(LocalTrackSwipe provides host) { content() }

    playlistTrack?.let { AddToPlaylistSheet(trackIds = listOf(it.id), onDismiss = { playlistTrack = null }) }
    infoTrack?.let { SongInfoDialog(track = it, onDismiss = { infoTrack = null }) }
    rateTrack?.let { track ->
        RatingDialog(title = track.title, rating = track.rating, onRate = { viewModel.rate(track, it) }, onDismiss = { rateTrack = null })
    }
}

/**
 * Gives a song row the user's swipe actions. Wrap the row, not the whole list item, so the
 * revealed surface matches the row's height.
 */
@Composable
fun TrackSwipeRow(
    track: Track,
    modifier: Modifier = Modifier,
    containerColor: Color = MaterialTheme.colorScheme.surface,
    content: @Composable () -> Unit,
) {
    val host = LocalTrackSwipe.current
    if (host == null) {
        Box(modifier) { content() }
        return
    }
    val config = host.config.value
    val downloaded = track.id in host.downloadedIds.value
    val leftToRight = swipeSpec(config.leftToRight, track.starred, downloaded, config.usesRatings)
    val rightToLeft = swipeSpec(config.rightToLeft, track.starred, downloaded, config.usesRatings)
    SwipeActionRow(
        leftToRight = leftToRight,
        rightToLeft = rightToLeft,
        onLeftToRight = { host.perform(track, config.leftToRight) },
        onRightToLeft = { host.perform(track, config.rightToLeft) },
        modifier = modifier,
        containerColor = containerColor,
        onThresholdCrossed = { host.haptics.thresholdActivate() },
        content = content,
    )
}

/**
 * Swipe actions on a song you don't have (polyphony#164): Add to queue and Play next queue it
 * through Play now, which fetches it in turn; the other actions are about songs in the library, so
 * a direction set to one of them stays still, as if set to Nothing. No swipes where the server
 * can't play songs you don't have, nor on a song whose album is already yours.
 */
@Composable
fun CatalogSwipeRow(
    track: CatalogTrack,
    modifier: Modifier = Modifier,
    containerColor: Color = MaterialTheme.colorScheme.surface,
    content: @Composable () -> Unit,
) {
    val host = LocalTrackSwipe.current
    val playNow = hiltViewModel<PlayNowViewModel>().playNow
    val available by playNow.available.collectAsStateWithLifecycle()
    // A song that's yours (its album is in the library) opens there instead.
    if (host == null || !available || track.recordingId == null || track.availability is Availability.InLibrary) {
        Box(modifier) { content() }
        return
    }
    val config = host.config.value
    val leftToRight = config.leftToRight.takeIf { it.queues() }
    val rightToLeft = config.rightToLeft.takeIf { it.queues() }
    SwipeActionRow(
        leftToRight = leftToRight?.let { swipeSpec(it, starred = false, downloaded = false, usesRatings = false) },
        rightToLeft = rightToLeft?.let { swipeSpec(it, starred = false, downloaded = false, usesRatings = false) },
        onLeftToRight = { leftToRight?.let { playNow.queue(track, next = it == TrackSwipeAction.PLAY_NEXT) } },
        onRightToLeft = { rightToLeft?.let { playNow.queue(track, next = it == TrackSwipeAction.PLAY_NEXT) } },
        modifier = modifier,
        containerColor = containerColor,
        onThresholdCrossed = { host.haptics.thresholdActivate() },
        content = content,
    )
}

private fun TrackSwipeAction.queues() = this == TrackSwipeAction.ADD_TO_QUEUE || this == TrackSwipeAction.PLAY_NEXT

@Composable
private fun swipeSpec(action: TrackSwipeAction, starred: Boolean, downloaded: Boolean, usesRatings: Boolean): SwipeActionSpec? {
    val scheme = MaterialTheme.colorScheme
    return when (action) {
        TrackSwipeAction.NONE -> null
        TrackSwipeAction.ADD_TO_QUEUE -> spec(
            Icons.AutoMirrored.Rounded.QueueMusic, stringResource(R.string.browse_action_add_to_queue),
            scheme.primaryContainer, scheme.onPrimaryContainer,
        )
        TrackSwipeAction.PLAY_NEXT -> spec(
            Icons.AutoMirrored.Rounded.PlaylistPlay, stringResource(R.string.browse_action_play_next),
            scheme.primaryContainer, scheme.onPrimaryContainer,
        )
        TrackSwipeAction.ADD_TO_PLAYLIST -> spec(
            Icons.AutoMirrored.Rounded.PlaylistAdd, stringResource(R.string.browse_action_add_to_playlist),
            scheme.secondaryContainer, scheme.onSecondaryContainer,
        )
        TrackSwipeAction.TOGGLE_FAVORITE -> if (usesRatings) spec(
            Icons.Rounded.Star, stringResource(R.string.feedback_swipe_rate),
            scheme.tertiaryContainer, scheme.onTertiaryContainer,
        ) else if (starred) spec(
            Icons.Rounded.FavoriteBorder, stringResource(R.string.browse_swipe_unfavorite),
            scheme.tertiaryContainer, scheme.onTertiaryContainer,
        ) else spec(
            Icons.Rounded.Favorite, stringResource(R.string.browse_swipe_favorite),
            scheme.tertiaryContainer, scheme.onTertiaryContainer,
        )
        TrackSwipeAction.DOWNLOAD -> if (downloaded) spec(
            Icons.Rounded.Delete, stringResource(R.string.dl_action_remove_download),
            scheme.errorContainer, scheme.onErrorContainer,
        ) else spec(
            Icons.Rounded.Download, stringResource(R.string.dl_action_download),
            scheme.primaryContainer, scheme.onPrimaryContainer,
        )
        TrackSwipeAction.SONG_INFO -> spec(
            Icons.Rounded.Info, stringResource(R.string.browse_swipe_info),
            scheme.secondaryContainer, scheme.onSecondaryContainer,
        )
    }
}

@Composable
private fun spec(icon: androidx.compose.ui.graphics.vector.ImageVector, label: String, container: Color, content: Color): SwipeActionSpec =
    remember(icon, label, container, content) { SwipeActionSpec(icon, label, container, content) }
