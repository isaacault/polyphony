package app.polyphony.navigation

import androidx.compose.runtime.Composable
import androidx.compose.animation.ExitTransition
import androidx.compose.animation.EnterTransition
import androidx.compose.ui.Modifier
import androidx.navigation.compose.NavHost
import app.polyphony.ui.browse.browseGraph
import app.polyphony.ui.onboarding.onboardingGraph
import app.polyphony.ui.settings.settingsGraph

/**
 * The single NavHost. Each feature package contributes destinations via a `NavGraphBuilder`
 * extension so the files stay independent. Screens switch instantly in both directions; the only
 * animated surface is the Now Playing sheet, which the shell owns.
 *
 * The predictive-pop transitions must be set too: they default to a built-in scale animation, so
 * leaving them out makes the system back gesture animate while the in-app back button does not.
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
        enterTransition = { EnterTransition.None },
        exitTransition = { ExitTransition.None },
        popEnterTransition = { EnterTransition.None },
        popExitTransition = { ExitTransition.None },
        predictivePopEnterTransition = { EnterTransition.None },
        predictivePopExitTransition = { ExitTransition.None },
    ) {
        browseGraph(navigator)
        settingsGraph(navigator)
        onboardingGraph(navigator)
    }
}
