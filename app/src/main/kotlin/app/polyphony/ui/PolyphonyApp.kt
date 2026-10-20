package app.polyphony.ui

import android.net.Uri
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.remember
import androidx.navigation.compose.rememberNavController
import app.polyphony.navigation.HomeRoute
import app.polyphony.navigation.LocalNavigator
import app.polyphony.navigation.OnboardingRoute
import app.polyphony.navigation.PolyphonyNavigator
import app.polyphony.navigation.Route
import app.polyphony.ui.shell.PolyphonyShell

/**
 * Root of the Compose tree: creates the navigator and hands over to the shell (bottom bar, mini
 * player, Now Playing sheet). The start destination is fixed at first composition so finishing
 * onboarding later does not rebuild the nav graph.
 */
@Composable
fun PolyphonyApp(
    onboardingComplete: Boolean,
    deepLink: Uri? = null,
    onDeepLinkConsumed: () -> Unit = {},
) {
    val navController = rememberNavController()
    val navigator = remember(navController) { PolyphonyNavigator(navController) }
    val startDestination: Route = remember { if (onboardingComplete) HomeRoute else OnboardingRoute }
    CompositionLocalProvider(LocalNavigator provides navigator) {
        PolyphonyShell(
            navigator = navigator,
            startDestination = startDestination,
            deepLink = deepLink,
            onDeepLinkConsumed = onDeepLinkConsumed,
        )
    }
}
