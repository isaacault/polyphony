package app.polyphony.core.downloads.work

import android.content.Context
import androidx.hilt.work.HiltWorker
import androidx.work.CoroutineWorker
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import app.polyphony.core.domain.provider.ProviderException
import app.polyphony.core.domain.repository.DownloadSettings
import app.polyphony.core.domain.repository.DownloadStatus
import app.polyphony.core.domain.repository.ProviderRegistry
import app.polyphony.core.domain.repository.ServerRepository
import app.polyphony.core.domain.repository.SettingsRepository
import app.polyphony.core.domain.repository.TranscodeProfileRepository
import app.polyphony.core.domain.transcode.TranscodeProfile
import app.polyphony.core.downloads.db.DownloadDao
import app.polyphony.core.downloads.db.DownloadEntity
import app.polyphony.core.downloads.db.track
import app.polyphony.core.downloads.engine.ProgressThrottle
import app.polyphony.core.downloads.engine.ResumeDecision
import app.polyphony.core.downloads.engine.ResumePolicy
import app.polyphony.core.downloads.internal.await
import app.polyphony.core.downloads.storage.DownloadPaths
import app.polyphony.core.downloads.storage.DownloadStorage
import app.polyphony.core.network.ServerHttpClientFactory
import dagger.assisted.Assisted
import dagger.assisted.AssistedInject
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import okhttp3.Request
import java.io.FileOutputStream
import java.io.IOException
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicInteger
import kotlin.coroutines.coroutineContext

/**
 * Drains every QUEUED download. One unique instance runs at a time; new items enqueued while it
 * runs are picked up by the loop, and the scheduler appends a follow-up run for stragglers.
 */
@HiltWorker
class DownloadWorker @AssistedInject constructor(
    @Assisted context: Context,
    @Assisted params: WorkerParameters,
    private val dao: DownloadDao,
    private val storage: DownloadStorage,
    private val serverRepository: ServerRepository,
    private val providerRegistry: ProviderRegistry,
    private val profileRepository: TranscodeProfileRepository,
    private val settingsRepository: SettingsRepository,
    private val httpFactory: ServerHttpClientFactory,
    private val notifications: DownloadNotifications,
    private val json: Json,
) : CoroutineWorker(context, params) {

    private val active = ConcurrentHashMap<String, Float>()
    private val done = AtomicInteger(0)
    private val total = AtomicInteger(0)
    private val notifyThrottle = ProgressThrottle(minIntervalMs = 500)
    private val cancelIntent by lazy { WorkManager.getInstance(applicationContext).createCancelPendingIntent(id) }

    override suspend fun getForegroundInfo() =
        notifications.foregroundInfo(notifications.build(done.get(), total.get(), 0f, indeterminate = true, cancelIntent = cancelIntent))

    override suspend fun doWork(): Result {
        // Rows left DOWNLOADING by a killed process belong back in the queue.
        dao.bulkSetStatus(listOf(DownloadStatus.DOWNLOADING), DownloadStatus.QUEUED)
        val settings = settingsRepository.current().downloads
        total.set(dao.pendingCount())
        if (total.get() == 0) return Result.success()

        runCatching { setForeground(getForegroundInfo()) } // may be refused when backgrounded on API 31+; keep going.

        var networkFailure = false
        val semaphore = Semaphore(settings.concurrentDownloads.coerceIn(1, 4))
        try {
            while (!isStopped) {
                val queued = dao.getQueued()
                if (queued.isEmpty()) break
                val seen = total.get()
                val pending = queued.size + active.size + done.get()
                if (pending > seen) total.set(pending)
                coroutineScope {
                    queued.map { item ->
                        async(Dispatchers.IO) {
                            semaphore.withPermit {
                                if (isStopped) return@withPermit
                                val ok = downloadOne(item, settings)
                                if (!ok) networkFailure = true
                            }
                        }
                    }.awaitAll()
                }
            }
        } finally {
            notifications.cancel()
        }
        if (isStopped) return Result.retry()
        return if (networkFailure && dao.getQueued().isNotEmpty()) Result.retry() else Result.success()
    }

    /** @return false when a network failure requeued the item (worker should retry later). */
    private suspend fun downloadOne(item: DownloadEntity, settings: DownloadSettings, allowRestart: Boolean = true): Boolean {
        val track = item.track(json)
        if (track == null) {
            dao.setStatus(item.trackId, DownloadStatus.FAILED, "Corrupt download metadata")
            finished(item.trackId)
            return true
        }
        try {
            val server = serverRepository.get(item.serverId)
                ?: return fail(item.trackId, "Server was removed")
            val provider = providerRegistry.forServer(server)
            val profile = if (item.profileId == TranscodeProfile.ORIGINAL_ID) TranscodeProfile.ORIGINAL
            else profileRepository.getProfile(item.profileId) ?: TranscodeProfile.builtIn(item.profileId) ?: TranscodeProfile.ORIGINAL
            val isOriginal = profile.isPassthrough
            val request = if (isOriginal) provider.downloadRequest(track) else provider.streamRequest(track, profile)

            val extension = DownloadPaths.extensionFor(track, profile, request.mimeType)
            val target = storage.fileFor(settings.location, item.serverId, item.trackId, extension)
            val part = storage.partFile(target)
            val decision = ResumePolicy.decide(isOriginal, part.exists(), part.length())

            dao.setProgress(item.trackId, 0f)
            active[item.trackId] = 0f

            val httpRequest = Request.Builder().url(request.url).apply {
                request.headers.forEach { (k, v) -> header(k, v) }
                if (decision is ResumeDecision.RangeFrom) header("Range", "bytes=${decision.offset}-")
            }.build()
            val client = httpFactory.clientFor(server)
            val response = client.newCall(httpRequest).await()
            response.use { resp ->
                when {
                    resp.code == 416 && allowRestart -> {
                        part.delete()
                        return downloadOne(item, settings, allowRestart = false)
                    }
                    resp.code == 401 || resp.code == 403 -> return fail(item.trackId, "Not authorised by the server")
                    resp.code == 404 -> return fail(item.trackId, "Track not found on the server")
                    !resp.isSuccessful -> return fail(item.trackId, "Server error ${resp.code}")
                }
                val offset = if (resp.code == 206 && decision is ResumeDecision.RangeFrom) decision.offset else 0L
                val resuming = offset > 0L
                val body = resp.body
                val contentLength = body.contentLength()
                val expectedTotal = when {
                    resuming -> parseContentRangeTotal(resp.header("Content-Range")) ?: (if (contentLength > 0) offset + contentLength else -1L)
                    contentLength > 0 -> contentLength
                    else -> -1L
                }
                val mime = (resp.header("Content-Type") ?: request.mimeType)?.substringBefore(';')?.trim()

                val written = withContext(Dispatchers.IO) {
                    val throttle = ProgressThrottle(minIntervalMs = 500)
                    var bytes = offset
                    FileOutputStream(part, resuming).use { out ->
                        body.byteStream().use { input ->
                            val buffer = ByteArray(64 * 1024)
                            while (true) {
                                coroutineContext.ensureActive()
                                if (isStopped) throw CancellationException("Worker stopped")
                                val read = input.read(buffer)
                                if (read < 0) break
                                out.write(buffer, 0, read)
                                bytes += read
                                if (expectedTotal > 0) {
                                    val p = (bytes.toFloat() / expectedTotal).coerceIn(0f, 0.999f)
                                    if (throttle.shouldEmit(p)) {
                                        dao.setProgress(item.trackId, p)
                                        active[item.trackId] = p
                                        publishProgress()
                                    }
                                }
                            }
                        }
                        out.fd.sync()
                    }
                    bytes
                }
                if (expectedTotal > 0 && written != expectedTotal) {
                    return fail(item.trackId, "Incomplete download (${written}/${expectedTotal} bytes)")
                }
                if (!storage.commit(part, target)) {
                    return fail(item.trackId, "Could not write file")
                }
                dao.markCompleted(item.trackId, target.absolutePath, target.length(), mime, System.currentTimeMillis())
                finished(item.trackId)
                return true
            }
        } catch (e: CancellationException) {
            dao.requeueIfDownloading(item.trackId)
            active.remove(item.trackId)
            throw e
        } catch (e: ProviderException.Network) {
            return requeue(item.trackId)
        } catch (e: IOException) {
            return requeue(item.trackId)
        } catch (e: ProviderException) {
            return fail(item.trackId, e.message ?: "Server error")
        } catch (e: Exception) {
            return fail(item.trackId, e.message ?: e.javaClass.simpleName)
        }
    }

    private suspend fun fail(trackId: String, message: String): Boolean {
        dao.setStatus(trackId, DownloadStatus.FAILED, message)
        finished(trackId)
        return true
    }

    private suspend fun requeue(trackId: String): Boolean {
        dao.setStatus(trackId, DownloadStatus.QUEUED, null)
        active.remove(trackId)
        publishProgress(force = true)
        return false
    }

    private fun finished(trackId: String) {
        active.remove(trackId)
        done.incrementAndGet()
        publishProgress(force = true)
    }

    private fun publishProgress(force: Boolean = false) {
        val t = total.get().coerceAtLeast(1)
        val overall = ((done.get() + active.values.sum()) / t).coerceIn(0f, 1f)
        val emit = synchronized(notifyThrottle) { notifyThrottle.shouldEmit(overall, force) }
        if (emit) {
            notifications.update(notifications.build(done.get(), total.get(), overall, indeterminate = false, cancelIntent = cancelIntent))
        }
    }

    private fun parseContentRangeTotal(header: String?): Long? =
        header?.substringAfter('/', "")?.trim()?.toLongOrNull()?.takeIf { it > 0 }
}
