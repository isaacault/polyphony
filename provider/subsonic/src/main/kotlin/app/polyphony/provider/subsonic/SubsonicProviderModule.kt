package app.polyphony.provider.subsonic

import app.polyphony.core.domain.provider.MediaProviderFactory
import dagger.Binds
import dagger.Module
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import dagger.multibindings.IntoSet

@Module
@InstallIn(SingletonComponent::class)
abstract class SubsonicProviderModule {
    @Binds
    @IntoSet
    abstract fun bindSubsonicFactory(factory: SubsonicProviderFactory): MediaProviderFactory
}
