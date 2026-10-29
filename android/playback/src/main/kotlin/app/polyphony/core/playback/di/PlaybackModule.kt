package app.polyphony.core.playback.di

import app.polyphony.core.domain.playback.CastController
import app.polyphony.core.domain.playback.PlaybackController
import app.polyphony.core.playback.cast.CastManager
import app.polyphony.core.playback.controller.PlaybackControllerImpl
import app.polyphony.core.playback.util.Clock
import dagger.Binds
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import javax.inject.Singleton

@Module
@InstallIn(SingletonComponent::class)
abstract class PlaybackBindsModule {
    @Binds
    abstract fun bindPlaybackController(impl: PlaybackControllerImpl): PlaybackController

    @Binds
    abstract fun bindCastController(impl: CastManager): CastController
}

@Module
@InstallIn(SingletonComponent::class)
object PlaybackProvidesModule {
    @Provides
    @Singleton
    @PlaybackScope
    fun providePlaybackScope(): CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)

    @Provides
    @Singleton
    fun provideClock(): Clock = Clock.SYSTEM
}
