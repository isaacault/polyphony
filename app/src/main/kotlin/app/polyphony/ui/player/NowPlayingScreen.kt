package app.polyphony.ui.player

import androidx.compose.ui.text.toUpperCase
import android.os.Build
import androidx.compose.animation.Crossfade
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.basicMarquee
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.QueueMusic
import androidx.compose.material.icons.rounded.Album
import androidx.compose.material.icons.rounded.Bedtime
import androidx.compose.material.icons.rounded.CastConnected
import androidx.compose.material.icons.rounded.Info
import androidx.compose.material.icons.rounded.KeyboardArrowDown
import androidx.compose.material.icons.rounded.Lyrics
import androidx.compose.material.icons.rounded.MoreVert
import androidx.compose.material.icons.rounded.Person
import androidx.compose.material.icons.automirrored.rounded.PlaylistAdd
import androidx.compose.material.icons.rounded.Speed
import androidx.compose.material.icons.rounded.Tune
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.BlurredEdgeTreatment
import androidx.compose.ui.draw.blur
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.polyphony.R
import app.polyphony.core.domain.model.Track
import app.polyphony.core.domain.playback.CastState
import app.polyphony.core.domain.repository.PlayerArtworkStyle
import app.polyphony.core.ui.color.ArtworkColors
import app.polyphony.core.ui.color.rememberArtworkColors
import app.polyphony.core.ui.motion.Motion
import app.polyphony.core.ui.theme.ArtworkShapes
import app.polyphony.core.ui.theme.ArtworkTheme
import app.polyphony.core.ui.theme.NowPlayingTitleStyle
import app.polyphony.core.ui.util.formatDurationLong
import app.polyphony.navigation.LocalNavigator
import app.polyphony.ui.shell.PlayerSheetController
import coil3.compose.AsyncImage
import java.util.Locale

private sealed interface PlayerDialog {
    data object SleepTimer : PlayerDialog
    data object Speed : PlayerDialog
    data object AddToPlaylist : PlayerDialog
    data object SongInfo : PlayerDialog
    data object SaveQueue : PlayerDialog
}

private enum class MenuAction { SleepTimer, Speed, GoToAlbum, GoToArtist, AddToPlaylist, SongInfo, Disconnect, StreamingSettings }

/** The full-screen player. Lives inside the draggable sheet; [sheet] lets it collapse itself. */
@Composable
fun NowPlayingScreen(
    viewModel: PlayerViewModel,
    sheet: PlayerSheetController,
    modifier: Modifier = Modifier,
) {
    val playback by viewModel.playback.collectAsStateWithLifecycle()
    val position by viewModel.position.collectAsStateWithLifecycle()
    val cast by viewModel.cast.collectAsStateWithLifecycle()
    val appearance by viewModel.appearance.collectAsStateWithLifecycle()
    val hapticsEnabled by viewModel.hapticsEnabled.collectAsStateWithLifecycle()
    val isFavorite by viewModel.isFavorite.collectAsStateWithLifecycle()
    val lyricsState by viewModel.lyrics.collectAsStateWithLifecycle()
    val showLyrics by viewModel.showLyrics.collectAsStateWithLifecycle()
    val providers by viewModel.providers.collectAsStateWithLifecycle()
    val playlists by viewModel.playlists.collectAsStateWithLifecycle()
    val navigator = LocalNavigator.current
    val haptics = rememberHaptics(hapticsEnabled)
    val track = playback.currentTrack

    val artworkColors by rememberArtworkColors(if (appearance.artworkColorsInPlayer) track?.artworkUrl else null)

    var dialog by remember { mutableStateOf<PlayerDialog?>(null) }
    var showQueue by rememberSaveable { mutableStateOf(false) }
    var showCast by rememberSaveable { mutableStateOf(false) }
    val snackbarHost = remember { SnackbarHostState() }

    val addedTemplate = stringResource(R.string.playlist_added)
    val savedTemplate = stringResource(R.string.queue_saved)
    val sleepSet = stringResource(R.string.sleep_timer_set)
    val sleepCleared = stringResource(R.string.sleep_timer_cleared)
    val qualityRestored = stringResource(R.string.player_quality_restored)

    LaunchedEffect(viewModel) { viewModel.messages.collect { snackbarHost.showSnackbar(it) } }
    LaunchedEffect(playback.error) { playback.error?.let { snackbarHost.showSnackbar(it) } }

    val artworkShape: Shape = remember(appearance.playerArtworkStyle) {
        when (appearance.playerArtworkStyle) {
            PlayerArtworkStyle.ROUNDED -> ArtworkShapes.player
            PlayerArtworkStyle.SQUARE -> RoundedCornerShape(6.dp)
            PlayerArtworkStyle.CIRCLE -> CircleShape
        }
    }

    ArtworkTheme(seed = artworkColors?.seed) {
        Box(
            modifier
                .fillMaxSize()
                .background(MaterialTheme.colorScheme.surface),
        ) {
            PlayerBackground(
                colors = artworkColors,
                artworkUrl = track?.artworkUrl,
                blurred = appearance.blurredPlayerBackground,
            )
            if (track != null) {
                BoxWithConstraints(
                    Modifier
                        .fillMaxSize()
                        .statusBarsPadding()
                        .navigationBarsPadding(),
                ) {
                    val landscape = maxWidth > maxHeight

                    val topBar: @Composable () -> Unit = {
                        PlayerTopBar(
                            track = track,
                            queueTitle = playback.queueTitle,
                            cast = cast,
                            sleepTimerEndsAt = playback.sleepTimerEndsAtEpochMs,
                            playbackSpeed = playback.playbackSpeed,
                            onCollapse = sheet::collapse,
                            onOutputClick = { showCast = true },
                            onAction = { action ->
                                when (action) {
                                    MenuAction.SleepTimer -> dialog = PlayerDialog.SleepTimer
                                    MenuAction.Speed -> dialog = PlayerDialog.Speed
                                    MenuAction.GoToAlbum -> track.albumId?.let {
                                        navigator.openAlbum(it)
                                        sheet.collapse()
                                    }
                                    MenuAction.GoToArtist -> track.artistId?.let {
                                        navigator.openArtist(it)
                                        sheet.collapse()
                                    }
                                    MenuAction.AddToPlaylist -> dialog = PlayerDialog.AddToPlaylist
                                    MenuAction.SongInfo -> dialog = PlayerDialog.SongInfo
                                    MenuAction.Disconnect -> viewModel.disconnectCast()
                                    MenuAction.StreamingSettings -> {
                                        navigator.openStreamingSettings()
                                        sheet.collapse()
                                    }
                                }
                            },
                        )
                    }
                    val artworkArea: @Composable (Modifier) -> Unit = { areaModifier ->
                        Crossfade(
                            targetState = showLyrics,
                            animationSpec = tween(Motion.DurationMedium),
                            label = "artworkLyrics",
                            modifier = areaModifier,
                        ) { lyrics ->
                            if (lyrics) {
                                LyricsView(
                                    state = lyricsState,
                                    positionMs = position.positionMs,
                                    onSeek = viewModel::seekTo,
                                    modifier = Modifier.fillMaxSize(),
                                )
                            } else {
                                PlayerArtworkPager(
                                    queue = playback.queue,
                                    currentIndex = playback.currentIndex,
                                    artworkUrlFor = { largeArtworkUrl(it, providers) },
                                    shape = artworkShape,
                                    onPageSettled = viewModel::skipToQueueItem,
                                    modifier = Modifier.fillMaxSize(),
                                )
                            }
                        }
                    }
                    val info: @Composable () -> Unit = {
                        PlayerTrackInfo(
                            track = track,
                            isFavorite = isFavorite,
                            onArtist = {
                                track.artistId?.let {
                                    navigator.openArtist(it)
                                    sheet.collapse()
                                }
                            },
                            onToggleFavorite = {
                                haptics.toggle(!isFavorite)
                                viewModel.toggleFavorite()
                            },
                        )
                    }
                    val slider: @Composable () -> Unit = {
                        PlayerSlider(
                            position = position,
                            onSeek = viewModel::seekTo,
                            modifier = Modifier.padding(horizontal = 20.dp),
                        )
                    }
                    val controls: @Composable () -> Unit = {
                        PlayerControls(
                            isPlaying = playback.isPlaying,
                            isBuffering = playback.isBuffering,
                            shuffleEnabled = playback.shuffleEnabled,
                            repeatMode = playback.repeatMode,
                            hasPrevious = playback.hasPrevious,
                            hasNext = playback.hasNext,
                            onShuffle = {
                                haptics.toggle(!playback.shuffleEnabled)
                                viewModel.toggleShuffle()
                            },
                            onPrevious = viewModel::previous,
                            onPlayPause = {
                                haptics.tap()
                                viewModel.togglePlayPause()
                            },
                            onNext = viewModel::next,
                            onRepeat = {
                                haptics.tap()
                                viewModel.cycleRepeat()
                            },
                        )
                    }
                    val secondary: @Composable () -> Unit = {
                        PlayerSecondaryRow(
                            showLyrics = showLyrics,
                            cast = cast,
                            sleepTimerActive = playback.sleepTimerEndsAtEpochMs != null,
                            decision = playback.transcode,
                            latencyDegraded = playback.latencyDegraded,
                            onLyrics = viewModel::toggleLyrics,
                            onQueue = { showQueue = true },
                            onCast = { showCast = true },
                            onSleepTimer = { dialog = PlayerDialog.SleepTimer },
                            onRestoreQuality = {
                                viewModel.restoreQuality()
                                viewModel.showMessage(qualityRestored)
                            },
                            onOpenStreamingSettings = {
                                navigator.openStreamingSettings()
                                sheet.collapse()
                            },
                        )
                    }

                    if (landscape) {
                        Column(Modifier.fillMaxSize()) {
                            topBar()
                            Row(
                                Modifier
                                    .fillMaxWidth()
                                    .weight(1f),
                                verticalAlignment = Alignment.CenterVertically,
                            ) {
                                artworkArea(
                                    Modifier
                                        .weight(1f)
                                        .fillMaxHeight()
                                        .padding(vertical = 8.dp),
                                )
                                Column(
                                    Modifier
                                        .weight(1f)
                                        .fillMaxHeight(),
                                    verticalArrangement = Arrangement.Center,
                                ) {
                                    info()
                                    Spacer(Modifier.height(12.dp))
                                    slider()
                                    Spacer(Modifier.height(4.dp))
                                    controls()
                                    Spacer(Modifier.height(8.dp))
                                    secondary()
                                }
                            }
                        }
                    } else {
                        Column(Modifier.fillMaxSize()) {
                            topBar()
                            artworkArea(
                                Modifier
                                    .fillMaxWidth()
                                    .weight(1f)
                                    .padding(vertical = 12.dp),
                            )
                            Spacer(Modifier.height(16.dp))
                            info()
                            Spacer(Modifier.height(12.dp))
                            slider()
                            Spacer(Modifier.height(4.dp))
                            controls()
                            Spacer(Modifier.height(12.dp))
                            secondary()
                            Spacer(Modifier.height(12.dp))
                        }
                    }
                }
            }
            SnackbarHost(
                hostState = snackbarHost,
                modifier = Modifier
                    .align(Alignment.BottomCenter)
                    .navigationBarsPadding()
                    .padding(bottom = 96.dp),
            )
        }

        when (dialog) {
            PlayerDialog.SleepTimer -> SleepTimerDialog(
                endsAtEpochMs = playback.sleepTimerEndsAtEpochMs,
                onPickMinutes = { minutes, finishTrack ->
                    viewModel.setSleepTimerMinutes(minutes, finishTrack)
                    viewModel.showMessage(sleepSet)
                },
                onEndOfTrack = {
                    viewModel.setSleepTimerEndOfTrack()
                    viewModel.showMessage(sleepSet)
                },
                onCancelTimer = {
                    viewModel.setSleepTimerMinutes(null)
                    viewModel.showMessage(sleepCleared)
                },
                onDismiss = { dialog = null },
            )
            PlayerDialog.Speed -> SpeedDialog(
                current = playback.playbackSpeed,
                onChange = viewModel::setPlaybackSpeed,
                onDismiss = { dialog = null },
            )
            PlayerDialog.AddToPlaylist -> AddToPlaylistDialog(
                playlists = playlists,
                onPick = { playlist ->
                    viewModel.addCurrentToPlaylist(playlist) { name -> viewModel.showMessage(addedTemplate.format(name)) }
                },
                onCreate = { name ->
                    viewModel.createPlaylistWithCurrent(name) { created -> viewModel.showMessage(addedTemplate.format(created)) }
                },
                onDismiss = { dialog = null },
            )
            PlayerDialog.SongInfo -> if (track != null) {
                SongInfoDialog(
                    track = track,
                    decision = playback.transcode,
                    outputTarget = playback.outputTarget,
                    onDismiss = { dialog = null },
                )
            }
            PlayerDialog.SaveQueue -> NamePromptDialog(
                title = stringResource(R.string.queue_save),
                confirmLabel = stringResource(R.string.dialog_save),
                onConfirm = { name ->
                    viewModel.saveQueueAsPlaylist(name) { created -> viewModel.showMessage(savedTemplate.format(created)) }
                    dialog = null
                },
                onDismiss = { dialog = null },
            )
            null -> Unit
        }
        if (showQueue) {
            QueueSheet(
                viewModel = viewModel,
                onDismiss = { showQueue = false },
                onSaveAsPlaylist = {
                    showQueue = false
                    dialog = PlayerDialog.SaveQueue
                },
            )
        }
        if (showCast) {
            CastRouteSheet(
                castState = cast,
                onDisconnect = viewModel::disconnectCast,
                onDismiss = { showCast = false },
            )
        }
    }
}

@Composable
private fun PlayerBackground(colors: ArtworkColors?, artworkUrl: String?, blurred: Boolean) {
    val scheme = MaterialTheme.colorScheme
    val topTarget = colors?.let { lerp(it.darkMuted, scheme.surface, 0.2f) } ?: scheme.surfaceContainerHigh
    val midTarget = colors?.let { lerp(it.muted, scheme.surface, 0.7f) } ?: scheme.surface
    val top by animateColorAsState(topTarget, tween(600), label = "bgTop")
    val mid by animateColorAsState(midTarget, tween(600), label = "bgMid")
    val canBlur = blurred && artworkUrl != null && Build.VERSION.SDK_INT >= Build.VERSION_CODES.S
    Box(Modifier.fillMaxSize()) {
        if (canBlur) {
            AsyncImage(
                model = artworkUrl,
                contentDescription = null,
                contentScale = ContentScale.Crop,
                modifier = Modifier
                    .fillMaxSize()
                    .graphicsLayer {
                        scaleX = 1.4f
                        scaleY = 1.4f
                        alpha = 0.5f
                    }
                    .blur(72.dp, BlurredEdgeTreatment.Unbounded),
            )
        }
        Box(
            Modifier
                .fillMaxSize()
                .background(
                    Brush.verticalGradient(
                        0f to top.copy(alpha = if (canBlur) 0.8f else 1f),
                        0.5f to mid.copy(alpha = if (canBlur) 0.85f else 1f),
                        1f to scheme.surface,
                    ),
                ),
        )
    }
}

@Composable
private fun PlayerTopBar(
    track: Track,
    queueTitle: String?,
    cast: CastState,
    sleepTimerEndsAt: Long?,
    playbackSpeed: Float,
    onCollapse: () -> Unit,
    onOutputClick: () -> Unit,
    onAction: (MenuAction) -> Unit,
) {
    var menuOpen by remember { mutableStateOf(false) }
    val casting = cast.isActiveSession && cast.target != null
    val overline = when {
        casting -> stringResource(R.string.player_playing_on)
        queueTitle != null -> stringResource(R.string.player_playing_from)
        else -> stringResource(R.string.player_now_playing)
    }
    val subtitle = when {
        casting -> cast.target?.displayName.orEmpty()
        queueTitle != null -> queueTitle
        else -> track.albumName.orEmpty()
    }
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 8.dp, vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        IconButton(onClick = onCollapse) {
            Icon(Icons.Rounded.KeyboardArrowDown, contentDescription = stringResource(R.string.player_collapse))
        }
        Column(
            modifier = Modifier
                .weight(1f)
                .clickable(
                    interactionSource = remember { MutableInteractionSource() },
                    indication = null,
                    onClick = onOutputClick,
                ),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Text(
                text = overline.toUpperCase(androidx.compose.ui.text.intl.Locale.current),
                style = MaterialTheme.typography.labelSmall.copy(letterSpacing = 1.2.sp),
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                if (casting) {
                    Icon(
                        Icons.Rounded.CastConnected,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.height(16.dp),
                    )
                }
                Text(
                    text = subtitle,
                    style = MaterialTheme.typography.titleSmall,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    textAlign = TextAlign.Center,
                    modifier = Modifier.widthIn(max = 240.dp),
                )
            }
        }
        Box {
            IconButton(onClick = { menuOpen = true }) {
                Icon(Icons.Rounded.MoreVert, contentDescription = stringResource(R.string.player_more))
            }
            DropdownMenu(expanded = menuOpen, onDismissRequest = { menuOpen = false }) {
                val remaining = sleepTimerEndsAt?.let { (it - System.currentTimeMillis()).coerceAtLeast(0L) }
                DropdownMenuItem(
                    text = {
                        Column {
                            Text(stringResource(R.string.player_sleep_timer))
                            remaining?.let {
                                Text(
                                    stringResource(R.string.player_sleep_timer_ends, formatDurationLong(it)),
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.primary,
                                )
                            }
                        }
                    },
                    leadingIcon = { Icon(Icons.Rounded.Bedtime, null) },
                    onClick = {
                        menuOpen = false
                        onAction(MenuAction.SleepTimer)
                    },
                )
                DropdownMenuItem(
                    text = {
                        Text(
                            stringResource(R.string.player_speed) + " · " +
                                stringResource(R.string.player_speed_value, String.format(Locale.US, "%.2f", playbackSpeed).trimEnd('0').trimEnd('.')),
                        )
                    },
                    leadingIcon = { Icon(Icons.Rounded.Speed, null) },
                    onClick = {
                        menuOpen = false
                        onAction(MenuAction.Speed)
                    },
                )
                DropdownMenuItem(
                    text = { Text(stringResource(R.string.player_go_to_album)) },
                    leadingIcon = { Icon(Icons.Rounded.Album, null) },
                    enabled = track.albumId != null,
                    onClick = {
                        menuOpen = false
                        onAction(MenuAction.GoToAlbum)
                    },
                )
                DropdownMenuItem(
                    text = { Text(stringResource(R.string.player_go_to_artist)) },
                    leadingIcon = { Icon(Icons.Rounded.Person, null) },
                    enabled = track.artistId != null,
                    onClick = {
                        menuOpen = false
                        onAction(MenuAction.GoToArtist)
                    },
                )
                DropdownMenuItem(
                    text = { Text(stringResource(R.string.player_add_to_playlist)) },
                    leadingIcon = { Icon(Icons.AutoMirrored.Rounded.PlaylistAdd, null) },
                    onClick = {
                        menuOpen = false
                        onAction(MenuAction.AddToPlaylist)
                    },
                )
                DropdownMenuItem(
                    text = { Text(stringResource(R.string.player_song_info)) },
                    leadingIcon = { Icon(Icons.Rounded.Info, null) },
                    onClick = {
                        menuOpen = false
                        onAction(MenuAction.SongInfo)
                    },
                )
                if (casting) {
                    DropdownMenuItem(
                        text = { Text(stringResource(R.string.player_disconnect_from, cast.target?.displayName.orEmpty())) },
                        leadingIcon = { Icon(Icons.Rounded.CastConnected, null) },
                        onClick = {
                            menuOpen = false
                            onAction(MenuAction.Disconnect)
                        },
                    )
                }
                DropdownMenuItem(
                    text = { Text(stringResource(R.string.player_streaming_settings)) },
                    leadingIcon = { Icon(Icons.Rounded.Tune, null) },
                    onClick = {
                        menuOpen = false
                        onAction(MenuAction.StreamingSettings)
                    },
                )
            }
        }
    }
}

@Composable
private fun PlayerTrackInfo(
    track: Track,
    isFavorite: Boolean,
    onArtist: () -> Unit,
    onToggleFavorite: () -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(start = 24.dp, end = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f)) {
            Text(
                text = track.title,
                style = NowPlayingTitleStyle,
                color = MaterialTheme.colorScheme.onSurface,
                maxLines = 1,
                modifier = Modifier.basicMarquee(iterations = Int.MAX_VALUE, initialDelayMillis = 2_000),
            )
            Text(
                text = track.displayArtist,
                style = MaterialTheme.typography.titleMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier
                    .padding(top = 2.dp)
                    .clickable(
                        interactionSource = remember { MutableInteractionSource() },
                        indication = null,
                        enabled = track.artistId != null,
                        onClick = onArtist,
                    ),
            )
        }
        FavoriteButton(isFavorite = isFavorite, onToggle = onToggleFavorite)
    }
}

@Composable
private fun PlayerSecondaryRow(
    showLyrics: Boolean,
    cast: CastState,
    sleepTimerActive: Boolean,
    decision: app.polyphony.core.domain.transcode.TranscodeDecision?,
    latencyDegraded: Boolean,
    onLyrics: () -> Unit,
    onQueue: () -> Unit,
    onCast: () -> Unit,
    onSleepTimer: () -> Unit,
    onRestoreQuality: () -> Unit,
    onOpenStreamingSettings: () -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        IconButton(onClick = onLyrics) {
            Icon(
                Icons.Rounded.Lyrics,
                contentDescription = stringResource(R.string.player_lyrics),
                tint = if (showLyrics) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        IconButton(onClick = onQueue) {
            Icon(
                Icons.AutoMirrored.Rounded.QueueMusic,
                contentDescription = stringResource(R.string.player_queue),
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        Box(
            Modifier
                .weight(1f)
                .padding(horizontal = 4.dp),
            contentAlignment = Alignment.Center,
        ) {
            QualityChip(
                decision = decision,
                latencyDegraded = latencyDegraded,
                onRestore = onRestoreQuality,
                onOpenSettings = onOpenStreamingSettings,
            )
        }
        CastIconButton(castState = cast, onClick = onCast)
        IconButton(onClick = onSleepTimer) {
            Icon(
                Icons.Rounded.Bedtime,
                contentDescription = stringResource(R.string.player_sleep_timer),
                tint = if (sleepTimerActive) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}
