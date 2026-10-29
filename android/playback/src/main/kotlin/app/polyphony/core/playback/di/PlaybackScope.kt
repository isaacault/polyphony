package app.polyphony.core.playback.di

import javax.inject.Qualifier

/** Application-lifetime coroutine scope for playback singletons (main-immediate dispatcher). */
@Qualifier
@Retention(AnnotationRetention.BINARY)
annotation class PlaybackScope
