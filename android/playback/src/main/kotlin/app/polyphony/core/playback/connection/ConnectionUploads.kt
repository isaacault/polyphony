package app.polyphony.core.playback.connection

import android.content.Context
import android.util.Log
import app.polyphony.core.domain.connection.ConnectionSample
import app.polyphony.core.domain.model.Server
import app.polyphony.core.domain.repository.ConnectionSampleRepository
import app.polyphony.core.domain.repository.NetworkMonitor
import app.polyphony.core.domain.repository.RequestRepository
import app.polyphony.core.domain.repository.ServerRepository
import app.polyphony.core.playback.di.PlaybackScope
import app.polyphony.core.playback.util.Clock
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import java.util.concurrent.atomic.AtomicBoolean
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Sends the connection samples to the continuo they were streamed from (#185 phase 2), which keeps
 * them per device: per server, what ended after the last sample it took, oldest first, in batches
 * of [BATCH]. A continuo that doesn't take them (one from before) is left alone for [BACKOFF_MS];
 * offline or with continuo down, they simply wait for the next run. One run at a time.
 */
class ConnectionUploads(
    private val servers: suspend () -> List<Server>,
    private val supports: (Server) -> Boolean,
    private val send: suspend (Server, List<ConnectionSample>) -> Boolean,
    private val samples: ConnectionSampleRepository,
    private val marks: SentMarks,
    private val nowMs: () -> Long,
) {
    /** Per server, the end of the last sample it took. */
    interface SentMarks {
        fun sentThrough(serverId: String): Long
        fun setSentThrough(serverId: String, endedAtMs: Long)
    }

    private val refusedUntil = HashMap<String, Long>()

    suspend fun run() {
        for (server in servers().filter(supports)) {
            if ((refusedUntil[server.id] ?: 0L) > nowMs()) continue
            var through = marks.sentThrough(server.id)
            while (true) {
                val batch = samples.forServerAfter(server.id, through, BATCH)
                if (batch.isEmpty()) break
                val took = try {
                    send(server, batch)
                } catch (e: CancellationException) {
                    throw e
                } catch (_: Exception) {
                    break   // offline or continuo down: next time
                }
                if (!took) {
                    refusedUntil[server.id] = nowMs() + BACKOFF_MS
                    break
                }
                through = batch.last().endedAtMs
                marks.setSentThrough(server.id, through)
                if (batch.size < BATCH) break
            }
        }
    }

    companion object {
        const val BATCH = 200
        const val BACKOFF_MS = 6 * 3_600_000L
    }
}

/**
 * Runs [ConnectionUploads] after a sample is stored ([ConnectionSampler]): at most every
 * [MIN_GAP_MS] while playing, and at once when playback stops. The samples go only to the user's
 * own continuo, and only off metered networks (#60): they're kept for two weeks, so they wait for
 * Wi-Fi rather than spend mobile data or compete with the music on a weak link. Getting onto an
 * unmetered network sends what waited.
 */
@Singleton
class ConnectionUploader @Inject constructor(
    @ApplicationContext context: Context,
    serverRepository: ServerRepository,
    samples: ConnectionSampleRepository,
    requests: RequestRepository,
    private val networkMonitor: NetworkMonitor,
    private val clock: Clock,
    @PlaybackScope private val scope: CoroutineScope,
) {
    private val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    private val uploads = ConnectionUploads(
        servers = { serverRepository.servers.first() },
        supports = { requests.supports(it) },
        send = { server, batch -> requests.sendConnectionSamples(server, batch) },
        samples = samples,
        marks = object : ConnectionUploads.SentMarks {
            override fun sentThrough(serverId: String) = prefs.getLong(serverId, 0L)
            override fun setSentThrough(serverId: String, endedAtMs: Long) = prefs.edit().putLong(serverId, endedAtMs).apply()
        },
        nowMs = clock::nowMs,
    )

    private val running = AtomicBoolean(false)
    @Volatile private var lastRunMs = 0L

    init {
        scope.launch {
            networkMonitor.state.map { it.isOnline && !it.isMetered }.distinctUntilChanged().filter { it }.collect { run(force = true) }
        }
    }

    fun onStored(trigger: ConnectionSample.Trigger) = run(force = trigger == ConnectionSample.Trigger.STOPPED)

    private fun run(force: Boolean) {
        val now = clock.nowMs()
        val network = networkMonitor.state.value
        if (!network.isOnline || network.isMetered) return
        if (!force && now - lastRunMs < MIN_GAP_MS) return
        if (!running.compareAndSet(false, true)) return
        lastRunMs = now
        scope.launch(Dispatchers.IO) {
            try {
                uploads.run()
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Log.w(TAG, "Couldn't send connection samples: ${e.message}")
            } finally {
                running.set(false)
            }
        }
    }

    private companion object {
        const val TAG = "PolyphonyConnection"
        const val PREFS = "polyphony-connection-uploads"
        const val MIN_GAP_MS = 2 * 60_000L
    }
}
