package app.polyphony.core.downloads.di

import android.content.Context
import androidx.room.Room
import app.polyphony.core.domain.repository.DownloadRepository
import app.polyphony.core.downloads.DownloadRepositoryImpl
import app.polyphony.core.downloads.db.DownloadDao
import app.polyphony.core.downloads.db.DownloadRuleDao
import app.polyphony.core.downloads.db.DownloadsDatabase
import dagger.Binds
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import javax.inject.Singleton

@Module
@InstallIn(SingletonComponent::class)
object DownloadsProvidesModule {

    @Provides
    @Singleton
    fun provideDatabase(@ApplicationContext context: Context): DownloadsDatabase =
        Room.databaseBuilder(context, DownloadsDatabase::class.java, DownloadsDatabase.NAME)
            .fallbackToDestructiveMigration(dropAllTables = true)
            .build()

    @Provides
    fun provideDownloadDao(db: DownloadsDatabase): DownloadDao = db.downloadDao()

    @Provides
    fun provideDownloadRuleDao(db: DownloadsDatabase): DownloadRuleDao = db.downloadRuleDao()

    @Provides
    @Singleton
    @DownloadsScope
    fun provideScope(): CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
}

@Module
@InstallIn(SingletonComponent::class)
abstract class DownloadsBindsModule {
    @Binds
    @Singleton
    abstract fun bindDownloadRepository(impl: DownloadRepositoryImpl): DownloadRepository
}
