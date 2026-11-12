package app.polyphony.core.scrobble.di

import android.content.Context
import androidx.room.Room
import app.polyphony.core.domain.repository.ScrobbleAccounts
import app.polyphony.core.domain.repository.ScrobbleRepository
import app.polyphony.core.domain.repository.Scrobbler
import app.polyphony.core.scrobble.BuildConfig
import app.polyphony.core.scrobble.ScrobbleRepositoryImpl
import app.polyphony.core.scrobble.db.ScrobbleDao
import app.polyphony.core.scrobble.db.ScrobbleDatabase
import app.polyphony.core.scrobble.http.HttpTransport
import app.polyphony.core.scrobble.http.OkHttpTransport
import app.polyphony.core.scrobble.lastfm.LastFmClient
import app.polyphony.core.scrobble.lastfm.LastFmConfig
import app.polyphony.core.scrobble.listenbrainz.ListenBrainzClient
import app.polyphony.core.scrobble.queue.RoomScrobbleQueue
import app.polyphony.core.scrobble.queue.ScrobbleDrainer
import app.polyphony.core.scrobble.server.ServerPlays
import app.polyphony.core.scrobble.server.ServerReports
import dagger.Binds
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import okhttp3.OkHttpClient
import javax.inject.Singleton

@Module
@InstallIn(SingletonComponent::class)
object ScrobbleProvidesModule {

    @Provides
    @Singleton
    fun provideDatabase(@ApplicationContext context: Context): ScrobbleDatabase =
        Room.databaseBuilder(context, ScrobbleDatabase::class.java, ScrobbleDatabase.NAME).build()

    @Provides
    fun provideDao(db: ScrobbleDatabase): ScrobbleDao = db.scrobbleDao()

    /** The shared base client: no server headers or self-signed trust, and its logging never prints bodies or headers. */
    @Provides
    @Singleton
    fun provideTransport(client: OkHttpClient): HttpTransport = OkHttpTransport(client)

    @Provides
    @Singleton
    fun provideLastFmConfig(): LastFmConfig = LastFmConfig(BuildConfig.LASTFM_API_KEY, BuildConfig.LASTFM_API_SECRET)

    @Provides
    @Singleton
    fun provideListenBrainz(http: HttpTransport): ListenBrainzClient = ListenBrainzClient(http, BuildConfig.APP_VERSION)

    @Provides
    @Singleton
    fun provideLastFm(http: HttpTransport, config: LastFmConfig): LastFmClient = LastFmClient(http, config)

    @Provides
    fun provideDrainer(
        queue: RoomScrobbleQueue,
        listenBrainz: ListenBrainzClient,
        lastFm: LastFmClient,
        config: LastFmConfig,
        server: ServerPlays,
    ): ScrobbleDrainer = ScrobbleDrainer(queue, listOfNotNull(listenBrainz, lastFm.takeIf { config.available }), server = server)
}

@Module
@InstallIn(SingletonComponent::class)
abstract class ScrobbleBindsModule {
    @Binds abstract fun bindRepository(impl: ScrobbleRepositoryImpl): ScrobbleRepository
    @Binds abstract fun bindScrobbler(impl: ScrobbleRepositoryImpl): Scrobbler
    @Binds abstract fun bindAccounts(impl: ScrobbleRepositoryImpl): ScrobbleAccounts
    @Binds abstract fun bindServerPlays(impl: ServerReports): ServerPlays
}
