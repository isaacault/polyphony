package app.polyphony.ui.shell

import android.net.Uri
import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.SnackbarDuration
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SnackbarResult
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.navigation.compose.currentBackStackEntryAsState
import app.polyphony.core.ui.components.LocalContentBottomPadding
import app.polyphony.core.ui.motion.Motion
import app.polyphony.core.ui.theme.Dimens
import app.polyphony.R
import app.polyphony.navigation.DeepLink
import app.polyphony.navigation.PolyphonyNavHost
import app.polyphony.navigation.PolyphonyNavigator
import app.polyphony.navigation.Route
import app.polyphony.ui.common.RemovalEvent
import app.polyphony.ui.common.TrackSwipeScope
import app.polyphony.ui.player.PlayerViewModel
import kotlinx.coroutines.launch

/**
 * Bottom space taken by the shell's chrome (bottom bar + mini player), excluding the system
 * navigation bar inset. Content scrolls underneath, so scrolling screens add
 * `LocalContentPadding.current.calculateBottomPadding()` (or `LocalContentBottomPadding`, the
 * same value as a Dp from core:ui) to their list content padding, on top of their normal system
 * bar insets. Do not add both.
 */
val LocalContentPadding = compositionLocalOf { PaddingValues(0.dp) }

/**
 * The shell's snackbar host, shown above the bottom bar and mini player. Screens without their own
 * Scaffold snackbar can post short confirmations here.
 */
val LocalSnackbarHostState = compositionLocalOf { SnackbarHostState() }

/**
 * The app frame: navigation host, bottom bar, mini player and the draggable Now Playing sheet.
 */
@Composable
fun PolyphonyShell(
    navigator: PolyphonyNavigator,
    startDestination: Route,
    deepLink: Uri?,
    onDeepLinkConsumed: () -> Unit,
) {
    val shellViewModel: ShellViewModel = hiltViewModel()
    val playerViewModel: PlayerViewModel = hiltViewModel()

    val playback by shellViewModel.playback.collectAsStateWithLifecycle()
    val hasServers by shellViewModel.hasServers.collectAsStateWithLifecycle()
    val continuoLinked by shellViewModel.continuoLinked.collectAsStateWithLifecycle()
    val backStackEntry by navigator.navController.currentBackStackEntryAsState()
    val destination = backStackEntry?.destination

    val showBottomBar = destination?.isBrowseRoute() == true
    val onOnboarding = destination?.isOnboardingRoute() == true
    val showPlayer = playback.isActive && !onOnboarding

    val sheetState = rememberPlayerSheetState()
    val snackbarHostState = remember { SnackbarHostState() }
    val density = LocalDensity.current
    val navBarInset = with(density) { WindowInsets.navigationBars.getBottom(this).toDp() }

    val barTotal = if (showBottomBar) Dimens.bottomBarHeight + navBarInset else 0.dp
    val miniExtraBottom = if (showBottomBar) 0.dp else navBarInset
    val peekHeight = barTotal + miniExtraBottom + Dimens.miniPlayerHeight

    val chromeTarget = (if (showBottomBar) Dimens.bottomBarHeight else 0.dp) +
        (if (showPlayer) Dimens.miniPlayerHeight else 0.dp)
    val animatedChrome by animateDpAsState(chromeTarget, animationSpec = Motion.sheetSpring(), label = "chrome")
    // The sheet spring is underdamped, so on the way down to 0 dp (leaving a browse route while the
    // player stops, or entering onboarding/add-server) it briefly overshoots negative, and
    // PaddingValues rejects a negative padding. Clamp before it reaches any layout.
    val chrome = animatedChrome.coerceAtLeast(0.dp)

    LaunchedEffect(hasServers, onOnboarding) {
        if (hasServers == false && !onOnboarding) navigator.openOnboarding()
    }
    LaunchedEffect(showPlayer) {
        if (!showPlayer) sheetState.snapCollapsed()
    }
    val linkScope = rememberCoroutineScope()
    val missingServerText = stringResource(R.string.browse_link_server_missing)
    val context = LocalContext.current
    // Album removals outlive the sheet that started them: their outcome shows here, with Undo.
    LaunchedEffect(shellViewModel) {
        shellViewModel.removalEvents.collect { event ->
            when (event) {
                is RemovalEvent.Removed -> linkScope.launch {
                    val result = snackbarHostState.showSnackbar(
                        message = context.getString(R.string.library_removed, event.album.title),
                        actionLabel = context.getString(R.string.library_undo),
                        duration = SnackbarDuration.Long,
                    )
                    if (result == SnackbarResult.ActionPerformed) shellViewModel.undoRemoval(event.album)
                }
                is RemovalEvent.Restored -> linkScope.launch {
                    snackbarHostState.showSnackbar(context.getString(R.string.library_restored, event.title))
                }
                is RemovalEvent.Failed -> linkScope.launch {
                    snackbarHostState.showSnackbar(context.getString(event.text, event.detail), duration = SnackbarDuration.Long)
                }
            }
        }
    }
    // polyphony:// deep links, also fired by the launcher app shortcuts (see res/xml/shortcuts.xml)
    // and by continuo's "Ready to play" notifications.
    LaunchedEffect(deepLink) {
        if (deepLink != null) {
            // Finish before consuming: clearing the link recomposes and cancels this effect.
            val receipt = DeepLink.receiptOf(deepLink.toString())
            if (receipt != null) shellViewModel.markNotificationRead(receipt)
            when (val link = DeepLink.parse(deepLink.toString())) {
                DeepLink.Player -> if (playback.isActive) sheetState.expand()
                DeepLink.Search -> navigator.openSearch()
                DeepLink.Shuffle -> shellViewModel.shuffleLibrary()
                is DeepLink.Album -> if (!onOnboarding) {
                    if (shellViewModel.activateServerFor(link)) {
                        sheetState.snapCollapsed()
                        navigator.openAlbum(link.albumId)
                    } else {
                        linkScope.launch { snackbarHostState.showSnackbar(missingServerText.format(link.server)) }
                    }
                }
                is DeepLink.Requests -> if (!onOnboarding) {
                    receipt?.let { shellViewModel.activateServer(it.serverId) }
                    sheetState.snapCollapsed()
                    navigator.openRequests(link.id)
                }
                DeepLink.Inbox -> if (!onOnboarding) {
                    receipt?.let { shellViewModel.activateServer(it.serverId) }
                    sheetState.snapCollapsed()
                    navigator.openInbox()
                }
                null -> Unit
            }
            onDeepLinkConsumed()
        }
    }
    BackHandler(enabled = sheetState.isExpanded) { sheetState.collapse() }
    // Playback's media notification, and continuo's request notifications (#118).
    NotificationPermissionEffect(active = playback.isActive || continuoLinked)

    val scrimVisible by remember(sheetState) { derivedStateOf { sheetState.fraction > 0.001f } }

    CompositionLocalProvider(
        LocalPlayerSheet provides sheetState,
        LocalContentPadding provides PaddingValues(bottom = chrome),
        LocalContentBottomPadding provides chrome,
        LocalSnackbarHostState provides snackbarHostState,
        // Screens that are not built on Scaffold/Surface would otherwise inherit Compose's default
        // black content colour, which is unreadable in dark theme.
        LocalContentColor provides MaterialTheme.colorScheme.onBackground,
    ) {
        Box(
            Modifier
                .fillMaxSize()
                .background(MaterialTheme.colorScheme.background),
        ) {
            TrackSwipeScope(snackbarHostState = snackbarHostState) {
                PolyphonyNavHost(
                    navigator = navigator,
                    startDestination = startDestination,
                    modifier = Modifier.fillMaxSize(),
                )
            }

            AnimatedVisibility(
                visible = showBottomBar,
                modifier = Modifier
                    .align(Alignment.BottomCenter)
                    .graphicsLayer { translationY = size.height * sheetState.fraction },
                enter = slideInVertically { it } + fadeIn(),
                exit = slideOutVertically { it } + fadeOut(),
            ) {
                PolyphonyBottomBar(navigator = navigator, destination = destination)
            }

            SnackbarHost(
                hostState = snackbarHostState,
                modifier = Modifier
                    .align(Alignment.BottomCenter)
                    .padding(bottom = chrome + navBarInset)
                    .graphicsLayer { alpha = 1f - sheetState.fraction },
            )

            if (scrimVisible) {
                Box(
                    Modifier
                        .fillMaxSize()
                        .graphicsLayer { alpha = sheetState.fraction * 0.45f }
                        .background(Color.Black)
                        .pointerInput(sheetState) { detectTapGestures { sheetState.collapse() } },
                )
            }

            AnimatedVisibility(
                visible = showPlayer,
                enter = slideInVertically { it } + fadeIn(),
                exit = slideOutVertically { it } + fadeOut(),
            ) {
                NowPlayingSheet(
                    state = sheetState,
                    peekHeight = peekHeight,
                    miniExtraBottom = miniExtraBottom,
                    playerViewModel = playerViewModel,
                )
            }
        }
    }
}
