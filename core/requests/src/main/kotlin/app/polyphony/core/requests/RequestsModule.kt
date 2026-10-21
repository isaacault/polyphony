package app.polyphony.core.requests

import app.polyphony.core.domain.repository.RequestRepository
import dagger.Binds
import dagger.Module
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent

@Module
@InstallIn(SingletonComponent::class)
abstract class RequestsModule {
    @Binds
    abstract fun bindRequestRepository(impl: ContinuoRequestRepository): RequestRepository
}
