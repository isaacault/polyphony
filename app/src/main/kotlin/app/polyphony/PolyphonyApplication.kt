package app.polyphony

import android.app.Application
import androidx.hilt.work.HiltWorkerFactory
import androidx.work.Configuration
import coil3.ImageLoader
import coil3.PlatformContext
import coil3.SingletonImageLoader
import coil3.disk.DiskCache
import coil3.disk.directory
import coil3.memory.MemoryCache
import coil3.network.okhttp.OkHttpNetworkFetcherFactory
import coil3.request.crossfade
import app.polyphony.core.domain.repository.DownloadRepository
import app.polyphony.core.domain.repository.NetworkMonitor
import app.polyphony.core.domain.repository.SettingsRepository
import app.polyphony.core.playback.transcode.TranscodeCapabilityProbe
import app.polyphony.diagnostics.CrashRecorder
import app.polyphony.image.ArtworkSizeInterceptor
import app.polyphony.image.DataSaver
import app.polyphony.inbox.InboxScheduler
import dagger.hilt.android.HiltAndroidApp
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import okhttp3.OkHttpClient
import javax.inject.Inject

@HiltAndroidApp
class PolyphonyApplication : Application(), Configuration.Provider, SingletonImageLoader.Factory {

    @Inject lateinit var workerFactory: HiltWorkerFactory
    @Inject lateinit var okHttpClient: dagger.Lazy<OkHttpClient>

    /** Injected eagerly so download rule observers and scheduling start with the process. */
    @Inject lateinit var downloadRepository: DownloadRepository

    /** Injected eagerly so servers get their transcoding support probed after the first sync. */
    @Inject lateinit var transcodeProbe: TranscodeCapabilityProbe

    @Inject lateinit var settingsRepository: SettingsRepository
    @Inject lateinit var networkMonitor: NetworkMonitor

    /** Keeps the continuo inbox poll scheduled while a continuo-linked server exists (#118). */
    @Inject lateinit var inboxScheduler: InboxScheduler

    private val appScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    override fun onCreate() {
        super.onCreate()
        CrashRecorder.install(this)
        inboxScheduler.start(appScope)
        // Keep the artwork data saver in step with the setting and whether the network is metered.
        appScope.launch {
            combine(
                settingsRepository.settings.map { it.appearance.reduceImageDataOnMobile }.distinctUntilChanged(),
                networkMonitor.state.map { it.isMetered }.distinctUntilChanged(),
            ) { reduce, metered -> reduce && metered }
                .distinctUntilChanged()
                .collect { DataSaver.reduceArtwork = it }
        }
    }

    override val workManagerConfiguration: Configuration
        get() = Configuration.Builder()
            .setWorkerFactory(workerFactory)
            .build()

    override fun newImageLoader(context: PlatformContext): ImageLoader =
        ImageLoader.Builder(context)
            .components {
                add(ArtworkSizeInterceptor())
                add(OkHttpNetworkFetcherFactory(callFactory = { okHttpClient.get() }))
            }
            .memoryCache {
                MemoryCache.Builder()
                    .maxSizePercent(context, 0.25)
                    .build()
            }
            .diskCache {
                DiskCache.Builder()
                    .directory(context.cacheDir.resolve("artwork"))
                    .maxSizeBytes(512L * 1024 * 1024)
                    .build()
            }
            .crossfade(true)
            .build()
}
