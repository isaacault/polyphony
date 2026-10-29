package app.polyphony.ui.onboarding

import androidx.navigation.NavGraphBuilder
import androidx.navigation.compose.composable
import app.polyphony.navigation.ConnectServerRoute
import app.polyphony.navigation.OnboardingRoute
import app.polyphony.navigation.PolyphonyNavigator

/** Welcome → pick a server type → connect. Also reused from Settings to add another server. */
fun NavGraphBuilder.onboardingGraph(navigator: PolyphonyNavigator) {
    composable<OnboardingRoute> {
        WelcomeScreen(onGetStarted = { navigator.openConnectServer(null) })
    }
    composable<ConnectServerRoute> {
        ConnectServerScreen(navigator = navigator)
    }
}
