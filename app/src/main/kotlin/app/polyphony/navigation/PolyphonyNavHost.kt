package app.polyphony.navigation

import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.dp
import androidx.navigation.compose.NavHost
import app.polyphony.core.ui.motion.NavMotion
import app.polyphony.ui.browse.browseGraph
import app.polyphony.ui.onboarding.onboardingGraph
import app.polyphony.ui.settings.settingsGraph

/**
 * The single NavHost. Each feature package contributes destinations via a `NavGraphBuilder`
 * extension so the files stay independent. Transitions depend on where you are going:
 * tab ↔ tab fades through, list → detail emerges, everything else is a shared-axis slide.
 */
@Composable
fun PolyphonyNavHost(
    navigator: PolyphonyNavigator,
    startDestination: Route,
    modifier: Modifier = Modifier,
) {
    val axisOffset = with(LocalDensity.current) { 30.dp.roundToPx() }
    NavHost(
        navController = navigator.navController,
        startDestination = startDestination,
        modifier = modifier,
        enterTransition = {
            val from = initialState.destination.routeKind()
            val to = targetState.destination.routeKind()
            when {
                from == RouteKind.TOP_LEVEL && to == RouteKind.TOP_LEVEL -> NavMotion.fadeThroughEnter()
                to == RouteKind.DETAIL -> NavMotion.detailEnter()
                else -> NavMotion.sharedAxisEnter(axisOffset)
            }
        },
        exitTransition = {
            val from = initialState.destination.routeKind()
            val to = targetState.destination.routeKind()
            when {
                from == RouteKind.TOP_LEVEL && to == RouteKind.TOP_LEVEL -> NavMotion.fadeThroughExit()
                to == RouteKind.DETAIL -> NavMotion.detailExitBehind()
                else -> NavMotion.sharedAxisExit(axisOffset)
            }
        },
        popEnterTransition = {
            // On pop, initialState is the screen being removed and targetState the one revealed.
            val leaving = initialState.destination.routeKind()
            val revealed = targetState.destination.routeKind()
            when {
                leaving == RouteKind.TOP_LEVEL && revealed == RouteKind.TOP_LEVEL -> NavMotion.fadeThroughEnter()
                leaving == RouteKind.DETAIL -> NavMotion.detailPopEnter()
                else -> NavMotion.sharedAxisPopEnter(axisOffset)
            }
        },
        popExitTransition = {
            val leaving = initialState.destination.routeKind()
            val revealed = targetState.destination.routeKind()
            when {
                leaving == RouteKind.TOP_LEVEL && revealed == RouteKind.TOP_LEVEL -> NavMotion.fadeThroughExit()
                leaving == RouteKind.DETAIL -> NavMotion.detailPopExit()
                else -> NavMotion.sharedAxisPopExit(axisOffset)
            }
        },
    ) {
        browseGraph(navigator)
        settingsGraph(navigator)
        onboardingGraph(navigator)
    }
}
