package app.polyphony.navigation

import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.navigation.compose.NavHost
import app.polyphony.core.ui.motion.Motion
import app.polyphony.core.ui.motion.Motion.sharedAxisEnter
import app.polyphony.core.ui.motion.Motion.sharedAxisExit
import app.polyphony.core.ui.motion.Motion.sharedAxisPopEnter
import app.polyphony.core.ui.motion.Motion.sharedAxisPopExit
import app.polyphony.ui.browse.browseGraph
import app.polyphony.ui.onboarding.onboardingGraph
import app.polyphony.ui.settings.settingsGraph

/**
 * The single NavHost. Each feature package contributes destinations via a `NavGraphBuilder`
 * extension so the files stay independent.
 */
@Composable
fun PolyphonyNavHost(
    navigator: PolyphonyNavigator,
    startDestination: Route,
    modifier: Modifier = Modifier,
) {
    NavHost(
        navController = navigator.navController,
        startDestination = startDestination,
        modifier = modifier,
        enterTransition = { sharedAxisEnter() },
        exitTransition = { sharedAxisExit() },
        popEnterTransition = { sharedAxisPopEnter() },
        popExitTransition = { sharedAxisPopExit() },
    ) {
        browseGraph(navigator)
        settingsGraph(navigator)
        onboardingGraph(navigator)
    }
}

/** Transition pair used when switching bottom-bar tabs. */
internal val tabEnter = Motion.fadeThroughEnter()
internal val tabExit = Motion.fadeThroughExit()
