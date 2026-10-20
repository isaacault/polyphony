package app.polyphony

import android.content.Intent
import android.net.Uri
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.runtime.getValue
import androidx.compose.ui.graphics.Color
import androidx.core.splashscreen.SplashScreen.Companion.installSplashScreen
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.polyphony.core.domain.repository.AppSettings
import app.polyphony.core.domain.repository.SettingsRepository
import app.polyphony.core.ui.theme.PolyphonyTheme
import app.polyphony.ui.PolyphonyApp
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.flow.MutableStateFlow
import javax.inject.Inject

@AndroidEntryPoint
class MainActivity : ComponentActivity() {

    @Inject lateinit var settingsRepository: SettingsRepository

    /** `polyphony://player` opens the Now Playing sheet; other links are ignored for now. */
    private val deepLink = MutableStateFlow<Uri?>(null)

    override fun onCreate(savedInstanceState: Bundle?) {
        val splash = installSplashScreen()
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        if (savedInstanceState == null) deepLink.value = intent?.data

        setContent {
            val settings: AppSettings? by settingsRepository.settings.collectAsStateWithLifecycle(initialValue = null)
            splash.setKeepOnScreenCondition { settings == null }
            val current = settings ?: return@setContent
            val link by deepLink.collectAsStateWithLifecycle()
            PolyphonyTheme(
                themeMode = current.appearance.themeMode,
                dynamicColor = current.appearance.dynamicColor,
                seedColor = Color(current.appearance.accentSeedArgb),
                pureBlack = current.appearance.pureBlack,
            ) {
                PolyphonyApp(
                    onboardingComplete = current.onboardingComplete,
                    deepLink = link,
                    onDeepLinkConsumed = { deepLink.value = null },
                )
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        deepLink.value = intent.data
    }
}
