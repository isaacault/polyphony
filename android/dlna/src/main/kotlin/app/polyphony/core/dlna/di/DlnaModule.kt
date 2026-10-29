package app.polyphony.core.dlna.di

import app.polyphony.core.dlna.DlnaManager
import app.polyphony.core.domain.playback.DlnaController
import dagger.Binds
import dagger.Module
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent

@Module
@InstallIn(SingletonComponent::class)
abstract class DlnaModule {
    @Binds
    abstract fun bindDlnaController(impl: DlnaManager): DlnaController
}
