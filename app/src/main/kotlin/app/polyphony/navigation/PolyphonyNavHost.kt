package app.polyphony.navigation

import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.navigation.compose.NavHost
import app.polyphony.core.ui.motion.NavMotion
import app.polyphony.ui.browse.browseGraph
import app.polyphony.ui.onboarding.onboardingGraph
import app.polyphony.ui.settings.settingsGraph

/**
 * The single NavHost. Each feature package contributes destinations via a `NavGraphBuilder`
 * extension so the files stay independent. Tab switches crossfade; every other move slides.
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
        enterTransition = {
            if (isTabSwitch(initialState.destination.routeKind(), targetState.destination.routeKind())) NavMotion.tabEnter()
            else NavMotion.pushEnter()
        },
        exitTransition = {
            if (isTabSwitch(initialState.destination.routeKind(), targetState.destination.routeKind())) NavMotion.tabExit()
            else NavMotion.pushExit()
        },
        popEnterTransition = {
            if (isTabSwitch(initialState.destination.routeKind(), targetState.destination.routeKind())) NavMotion.tabEnter()
            else NavMotion.popEnter()
        },
        popExitTransition = {
            if (isTabSwitch(initialState.destination.routeKind(), targetState.destination.routeKind())) NavMotion.tabExit()
            else NavMotion.popExit()
        },
    ) {
        browseGraph(navigator)
        settingsGraph(navigator)
        onboardingGraph(navigator)
    }
}

private fun isTabSwitch(from: RouteKind, to: RouteKind): Boolean =
    from == RouteKind.TOP_LEVEL && to == RouteKind.TOP_LEVEL
