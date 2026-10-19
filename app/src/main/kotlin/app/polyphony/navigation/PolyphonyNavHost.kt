package app.polyphony.navigation

import androidx.compose.runtime.Composable
import app.polyphony.core.domain.repository.NavTransitionStyle
import androidx.compose.animation.ExitTransition
import androidx.compose.animation.EnterTransition
import androidx.compose.ui.Modifier
import androidx.navigation.compose.NavHost
import app.polyphony.core.ui.motion.NavMotion
import app.polyphony.ui.browse.browseGraph
import app.polyphony.ui.onboarding.onboardingGraph
import app.polyphony.ui.settings.settingsGraph

/**
 * The single NavHost. Each feature package contributes destinations via a `NavGraphBuilder`
 * extension so the files stay independent. The transition style is a user setting: none, a quick
 * crossfade, or slides (tabs crossfade, pushes slide).
 */
@Composable
fun PolyphonyNavHost(
    navigator: PolyphonyNavigator,
    startDestination: Route,
    modifier: Modifier = Modifier,
    style: NavTransitionStyle = NavTransitionStyle.FADE,
) {
    NavHost(
        navController = navigator.navController,
        startDestination = startDestination,
        modifier = modifier,
        enterTransition = {
            when (style) {
                NavTransitionStyle.NONE -> EnterTransition.None
                NavTransitionStyle.FADE -> NavMotion.quickFadeEnter()
                NavTransitionStyle.SLIDE ->
                    if (isTabSwitch(initialState.destination.routeKind(), targetState.destination.routeKind())) NavMotion.tabEnter()
                    else NavMotion.pushEnter()
            }
        },
        exitTransition = {
            when (style) {
                NavTransitionStyle.NONE -> ExitTransition.None
                NavTransitionStyle.FADE -> NavMotion.quickFadeExit()
                NavTransitionStyle.SLIDE ->
                    if (isTabSwitch(initialState.destination.routeKind(), targetState.destination.routeKind())) NavMotion.tabExit()
                    else NavMotion.pushExit()
            }
        },
        popEnterTransition = {
            when (style) {
                NavTransitionStyle.NONE -> EnterTransition.None
                NavTransitionStyle.FADE -> NavMotion.quickFadeEnter()
                NavTransitionStyle.SLIDE ->
                    if (isTabSwitch(initialState.destination.routeKind(), targetState.destination.routeKind())) NavMotion.tabEnter()
                    else NavMotion.popEnter()
            }
        },
        popExitTransition = {
            when (style) {
                NavTransitionStyle.NONE -> ExitTransition.None
                NavTransitionStyle.FADE -> NavMotion.quickFadeExit()
                NavTransitionStyle.SLIDE ->
                    if (isTabSwitch(initialState.destination.routeKind(), targetState.destination.routeKind())) NavMotion.tabExit()
                    else NavMotion.popExit()
            }
        },
    ) {
        browseGraph(navigator)
        settingsGraph(navigator)
        onboardingGraph(navigator)
    }
}

private fun isTabSwitch(from: RouteKind, to: RouteKind): Boolean =
    from == RouteKind.TOP_LEVEL && to == RouteKind.TOP_LEVEL
