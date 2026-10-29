package app.polyphony.core.data.repository

import app.polyphony.core.data.di.ApplicationScope
import app.polyphony.core.domain.model.Capability
import app.polyphony.core.domain.model.Feedback
import app.polyphony.core.domain.model.FeedbackMode
import app.polyphony.core.domain.repository.FeedbackRepository
import app.polyphony.core.domain.repository.ProviderRegistry
import app.polyphony.core.domain.repository.SettingsRepository
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Ratings mode only takes effect on a server that stores ratings; on one that cannot (Jellyfin)
 * the app falls back to favourites, and the setting itself stays as chosen for the next server.
 */
@Singleton
class FeedbackRepositoryImpl @Inject constructor(
    settings: SettingsRepository,
    providers: ProviderRegistry,
    @ApplicationScope scope: CoroutineScope,
) : FeedbackRepository {

    override val feedback: StateFlow<Feedback> = combine(
        settings.settings.map { it.library.feedbackMode to it.library.topRatedMinimum }.distinctUntilChanged(),
        // No server yet: nothing to rate either way, so do not grey the choice out.
        providers.activeProvider.map { it == null || Capability.RATINGS in it.capabilities }.distinctUntilChanged(),
    ) { (mode, minimum), supported ->
        Feedback(
            mode = if (supported) mode else FeedbackMode.FAVOURITES,
            topRatedMinimum = minimum.coerceIn(1, 5),
            ratingsSupported = supported,
        )
    }
        .distinctUntilChanged()
        .stateIn(scope, SharingStarted.Eagerly, Feedback())
}
