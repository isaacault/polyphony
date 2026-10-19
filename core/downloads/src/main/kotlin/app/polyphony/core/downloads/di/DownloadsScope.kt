package app.polyphony.core.downloads.di

import javax.inject.Qualifier

/** Long-lived scope for download bookkeeping (observers, rule triggers). */
@Qualifier
@Retention(AnnotationRetention.RUNTIME)
annotation class DownloadsScope
