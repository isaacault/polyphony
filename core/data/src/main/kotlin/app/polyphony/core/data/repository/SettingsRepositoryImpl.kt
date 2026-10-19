package app.polyphony.core.data.repository

import androidx.datastore.core.DataStore
import app.polyphony.core.data.di.ApplicationScope
import app.polyphony.core.domain.repository.AppSettings
import app.polyphony.core.domain.repository.SettingsRepository
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.shareIn
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class SettingsRepositoryImpl @Inject constructor(
    private val dataStore: DataStore<AppSettings>,
    @ApplicationScope scope: CoroutineScope,
) : SettingsRepository {

    override val settings: Flow<AppSettings> = dataStore.data
        .catch { emit(AppSettings()) }
        .distinctUntilChanged()
        .shareIn(scope, SharingStarted.WhileSubscribed(5_000), replay = 1)

    override suspend fun current(): AppSettings = dataStore.data.catch { emit(AppSettings()) }.first()

    override suspend fun update(transform: (AppSettings) -> AppSettings) {
        dataStore.updateData(transform)
    }
}
