package app.polyphony.core.data.di

import app.polyphony.core.data.network.NetworkMonitorImpl
import app.polyphony.core.data.repository.LibraryRepositoryImpl
import app.polyphony.core.data.repository.ListeningHistoryRepositoryImpl
import app.polyphony.core.data.repository.ProviderRegistryImpl
import app.polyphony.core.data.repository.ServerRepositoryImpl
import app.polyphony.core.data.repository.SettingsRepositoryImpl
import app.polyphony.core.data.repository.TranscodeProfileRepositoryImpl
import app.polyphony.core.data.sync.LibrarySyncManagerImpl
import app.polyphony.core.domain.provider.MediaProviderFactory
import app.polyphony.core.domain.repository.LibraryRepository
import app.polyphony.core.domain.repository.LibrarySyncManager
import app.polyphony.core.domain.repository.ListeningHistoryRepository
import app.polyphony.core.domain.repository.NetworkMonitor
import app.polyphony.core.domain.repository.ProviderRegistry
import app.polyphony.core.domain.repository.ServerRepository
import app.polyphony.core.domain.repository.SettingsRepository
import app.polyphony.core.domain.repository.TranscodeProfileRepository
import dagger.Binds
import dagger.Module
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import dagger.multibindings.Multibinds

@Module
@InstallIn(SingletonComponent::class)
abstract class DataModule {

    /** Providers register themselves with `@Binds @IntoSet`; declare the set so it exists even if empty. */
    @Multibinds
    abstract fun providerFactories(): Set<MediaProviderFactory>

    @Binds abstract fun bindServerRepository(impl: ServerRepositoryImpl): ServerRepository
    @Binds abstract fun bindProviderRegistry(impl: ProviderRegistryImpl): ProviderRegistry
    @Binds abstract fun bindLibraryRepository(impl: LibraryRepositoryImpl): LibraryRepository
    @Binds abstract fun bindLibrarySyncManager(impl: LibrarySyncManagerImpl): LibrarySyncManager
    @Binds abstract fun bindSettingsRepository(impl: SettingsRepositoryImpl): SettingsRepository
    @Binds abstract fun bindTranscodeProfileRepository(impl: TranscodeProfileRepositoryImpl): TranscodeProfileRepository
    @Binds abstract fun bindNetworkMonitor(impl: NetworkMonitorImpl): NetworkMonitor
    @Binds abstract fun bindListeningHistoryRepository(impl: ListeningHistoryRepositoryImpl): ListeningHistoryRepository
}
