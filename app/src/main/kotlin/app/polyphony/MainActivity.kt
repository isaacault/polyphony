package app.polyphony

import android.content.Intent
import android.net.Uri
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.runtime.CompositionLocalProvider
import androidx.activity.enableEdgeToEdge
import androidx.compose.runtime.getValue
import androidx.compose.ui.graphics.Color
import androidx.core.splashscreen.SplashScreen.Companion.installSplashScreen
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.polyphony.core.domain.repository.AppSettings
import app.polyphony.core.domain.repository.FeedbackRepository
import app.polyphony.core.domain.repository.SettingsRepository
import app.polyphony.core.ui.components.LocalRatingStyle
import app.polyphony.core.ui.components.LocalUsesRatings
import app.polyphony.core.ui.theme.PolyphonyTheme
import app.polyphony.inbox.InboxScheduler
import app.polyphony.ui.PolyphonyApp
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.flow.MutableStateFlow
import javax.inject.Inject

@AndroidEntryPoint
class MainActivity : ComponentActivity() {

    @Inject lateinit var settingsRepository: SettingsRepository
    @Inject lateinit var feedbackRepository: FeedbackRepository
    @Inject lateinit var inboxScheduler: InboxScheduler

    /**
     * `polyphony://` deep links, also fired by the launcher app shortcuts and by continuo's "Ready
     * to play" notifications: `player` opens the Now Playing sheet, `search` the Search tab,
     * `shuffle` starts shuffle-all playback, `album/{server}/{albumId}` opens an album. Parsed by
     * [app.polyphony.navigation.DeepLink], routed by the shell ([PolyphonyShell]); unknown links
     * are ignored.
     */
    private val deepLink = MutableStateFlow<Uri?>(null)

    override fun onCreate(savedInstanceState: Bundle?) {
        val splash = installSplashScreen()
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        if (savedInstanceState == null) {
            deepLink.value = intent?.data
            // Catch up on continuo notifications (#118) without waiting for the periodic poll.
            inboxScheduler.pollOnOpen(lifecycleScope)
        }

        setContent {
            val settings: AppSettings? by settingsRepository.settings.collectAsStateWithLifecycle(initialValue = null)
            splash.setKeepOnScreenCondition { settings == null }
            val current = settings ?: return@setContent
            val link by deepLink.collectAsStateWithLifecycle()
            val feedback by feedbackRepository.feedback.collectAsStateWithLifecycle()
            PolyphonyTheme(
                themeMode = current.appearance.themeMode,
                dynamicColor = current.appearance.dynamicColor,
                seedColor = Color(current.appearance.accentSeedArgb),
                pureBlack = current.appearance.pureBlack,
            ) {
                CompositionLocalProvider(
                    LocalRatingStyle provides current.appearance.ratingStyle,
                    LocalUsesRatings provides feedback.usesRatings,
                ) {
                    PolyphonyApp(
                        onboardingComplete = current.onboardingComplete,
                        deepLink = link,
                        onDeepLinkConsumed = { deepLink.value = null },
                    )
                }
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        deepLink.value = intent.data
    }
}
