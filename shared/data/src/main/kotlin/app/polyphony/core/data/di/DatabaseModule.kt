package app.polyphony.core.data.di

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.core.DataStoreFactory
import androidx.datastore.core.handlers.ReplaceFileCorruptionHandler
import androidx.datastore.dataStoreFile
import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.emptyPreferences
import androidx.datastore.preferences.preferencesDataStoreFile
import androidx.room.Room
import app.polyphony.core.data.db.Migrations
import app.polyphony.core.data.db.PolyphonyDatabase
import app.polyphony.core.data.db.dao.AlbumDao
import app.polyphony.core.data.db.dao.ArtistDao
import app.polyphony.core.data.db.dao.GenreDao
import app.polyphony.core.data.db.dao.PlayContextDao
import app.polyphony.core.data.db.dao.PlayHistoryDao
import app.polyphony.core.data.db.dao.PlaylistDao
import app.polyphony.core.data.db.dao.RemoteDeviceDao
import app.polyphony.core.data.db.dao.ServerDao
import app.polyphony.core.data.db.dao.TrackDao
import app.polyphony.core.data.db.dao.TranscodeProfileDao
import app.polyphony.core.data.settings.AppSettingsSerializer
import app.polyphony.core.domain.repository.AppSettings
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.serialization.json.Json
import javax.inject.Singleton

@Module
@InstallIn(SingletonComponent::class)
object DatabaseModule {

    @Provides
    @Singleton
    fun provideDatabase(@ApplicationContext context: Context): PolyphonyDatabase =
        Room.databaseBuilder(context, PolyphonyDatabase::class.java, PolyphonyDatabase.NAME)
            .addMigrations(*Migrations.ALL)
            // This database also holds the configured servers, so a forgotten upgrade migration must
            // fail loudly (MigrationsChainTest catches it before release) rather than silently drop
            // every account. Only a downgrade, i.e. installing an older build, rebuilds from scratch.
            .fallbackToDestructiveMigrationOnDowngrade(dropAllTables = true)
            .build()

    @Provides fun provideServerDao(db: PolyphonyDatabase): ServerDao = db.serverDao()
    @Provides fun provideArtistDao(db: PolyphonyDatabase): ArtistDao = db.artistDao()
    @Provides fun provideAlbumDao(db: PolyphonyDatabase): AlbumDao = db.albumDao()
    @Provides fun provideTrackDao(db: PolyphonyDatabase): TrackDao = db.trackDao()
    @Provides fun providePlaylistDao(db: PolyphonyDatabase): PlaylistDao = db.playlistDao()
    @Provides fun provideGenreDao(db: PolyphonyDatabase): GenreDao = db.genreDao()
    @Provides fun provideTranscodeProfileDao(db: PolyphonyDatabase): TranscodeProfileDao = db.transcodeProfileDao()
    @Provides fun provideRemoteDeviceDao(db: PolyphonyDatabase): RemoteDeviceDao = db.remoteDeviceDao()
    @Provides fun providePlayHistoryDao(db: PolyphonyDatabase): PlayHistoryDao = db.playHistoryDao()
    @Provides fun providePlayContextDao(db: PolyphonyDatabase): PlayContextDao = db.playContextDao()

    @Provides
    @Singleton
    fun provideSettingsDataStore(@ApplicationContext context: Context, json: Json): DataStore<AppSettings> =
        DataStoreFactory.create(
            serializer = AppSettingsSerializer(json),
            corruptionHandler = ReplaceFileCorruptionHandler { AppSettings() },
            scope = CoroutineScope(Dispatchers.IO + SupervisorJob()),
            produceFile = { context.dataStoreFile("settings.json") },
        )

    @Provides
    @Singleton
    fun providePreferencesDataStore(@ApplicationContext context: Context): DataStore<Preferences> =
        PreferenceDataStoreFactory.create(
            corruptionHandler = ReplaceFileCorruptionHandler { emptyPreferences() },
            scope = CoroutineScope(Dispatchers.IO + SupervisorJob()),
            produceFile = { context.preferencesDataStoreFile("polyphony") },
        )
}
