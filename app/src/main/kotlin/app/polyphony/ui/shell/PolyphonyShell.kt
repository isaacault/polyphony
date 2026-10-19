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
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.navigation.compose.currentBackStackEntryAsState
import app.polyphony.core.ui.components.LocalContentBottomPadding
import app.polyphony.core.ui.motion.Motion
import app.polyphony.core.ui.theme.Dimens
import app.polyphony.navigation.PolyphonyNavHost
import app.polyphony.navigation.PolyphonyNavigator
import app.polyphony.navigation.Route
import app.polyphony.ui.player.PlayerViewModel

/**
 * Bottom space taken by the shell's chrome (bottom bar + mini player), excluding the system
 * navigation bar inset. Content scrolls underneath, so scrolling screens add
 * `LocalContentPadding.current.calculateBottomPadding()` (or `LocalContentBottomPadding`, the
 * same value as a Dp from core:ui) to their list content padding, on top of their normal system
 * bar insets. Do not add both.
 */
val LocalContentPadding = compositionLocalOf { PaddingValues(0.dp) }

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
    val backStackEntry by navigator.navController.currentBackStackEntryAsState()
    val destination = backStackEntry?.destination

    val showBottomBar = destination?.isBrowseRoute() == true
    val onOnboarding = destination?.isOnboardingRoute() == true
    val showPlayer = playback.isActive && !onOnboarding

    val sheetState = rememberPlayerSheetState()
    val density = LocalDensity.current
    val navBarInset = with(density) { WindowInsets.navigationBars.getBottom(this).toDp() }

    val barTotal = if (showBottomBar) Dimens.bottomBarHeight + navBarInset else 0.dp
    val miniExtraBottom = if (showBottomBar) 0.dp else navBarInset
    val peekHeight = barTotal + miniExtraBottom + Dimens.miniPlayerHeight

    val chromeTarget = (if (showBottomBar) Dimens.bottomBarHeight else 0.dp) +
        (if (showPlayer) Dimens.miniPlayerHeight else 0.dp)
    val chrome by animateDpAsState(chromeTarget, animationSpec = Motion.sheetSpring(), label = "chrome")

    LaunchedEffect(hasServers, onOnboarding) {
        if (hasServers == false && !onOnboarding) navigator.openOnboarding()
    }
    LaunchedEffect(showPlayer) {
        if (!showPlayer) sheetState.snapCollapsed()
    }
    LaunchedEffect(deepLink) {
        if (deepLink != null) {
            if (deepLink.host == "player" && playback.isActive) sheetState.expand()
            onDeepLinkConsumed()
        }
    }
    BackHandler(enabled = sheetState.isExpanded) { sheetState.collapse() }
    NotificationPermissionEffect(active = playback.isActive)

    val scrimVisible by remember(sheetState) { derivedStateOf { sheetState.fraction > 0.001f } }

    CompositionLocalProvider(
        LocalPlayerSheet provides sheetState,
        LocalContentPadding provides PaddingValues(bottom = chrome),
        LocalContentBottomPadding provides chrome,
    ) {
        Box(
            Modifier
                .fillMaxSize()
                .background(MaterialTheme.colorScheme.background),
        ) {
            PolyphonyNavHost(
                navigator = navigator,
                startDestination = startDestination,
                modifier = Modifier.fillMaxSize(),
            )

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
