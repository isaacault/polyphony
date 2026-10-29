package app.polyphony.core.playback.transcode

import android.util.Log
import app.polyphony.core.domain.model.Server
import app.polyphony.core.domain.model.ServerOptions
import app.polyphony.core.domain.model.Track
import app.polyphony.core.domain.repository.LibraryRepository
import app.polyphony.core.domain.repository.LibrarySyncManager
import app.polyphony.core.domain.repository.ProviderRegistry
import app.polyphony.core.domain.repository.ServerRepository
import app.polyphony.core.domain.repository.SyncState
import app.polyphony.core.domain.transcode.AudioCodec
import app.polyphony.core.domain.transcode.TranscodeProfile
import app.polyphony.core.network.ServerHttpClientFactory
import app.polyphony.core.playback.di.PlaybackScope
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import okhttp3.Request
import java.util.concurrent.TimeUnit
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Finds out which lossy codecs a server can actually transcode to, by requesting the first few
 * kilobytes of a short track in each format and checking that audio of the *requested* type comes
 * back. Servers without ffmpeg (or without a transcoding profile for a codec) either fail, hang, or
 * quietly return the original file; all three count as unsupported.
 *
 * Runs automatically once per server after the first library sync (and again every
 * [REPROBE_AFTER_MS]), and on demand from the server settings screen. Results live in
 * [ServerOptions.TRANSCODE_CODECS] and feed [CodecSupport.adjust].
 */
@Singleton
class TranscodeCapabilityProbe @Inject constructor(
    private val serverRepository: ServerRepository,
    private val providerRegistry: ProviderRegistry,
    private val libraryRepository: LibraryRepository,
    private val httpClients: ServerHttpClientFactory,
    librarySyncManager: LibrarySyncManager,
    @PlaybackScope scope: CoroutineScope,
) {
    data class Result(val supported: Set<AudioCodec>, val probedAt: Long, val details: Map<AudioCodec, String>)

    private val mutex = Mutex()
    private val _running = MutableStateFlow<String?>(null)
    /** Server id currently being probed, or null. */
    val running: StateFlow<String?> = _running

    init {
        // Probe the active server once its library has content (after a sync completes).
        scope.launch {
            combine(
                serverRepository.activeServer,
                librarySyncManager.state.map { it.status }.distinctUntilChanged(),
            ) { server, status -> server to status }
                .filter { (server, status) -> server != null && status == SyncState.Status.IDLE }
                .map { (server, _) -> server!! }
                .collect { server ->
                    val probedAt = server.options[ServerOptions.TRANSCODE_PROBED_AT]?.toLongOrNull() ?: 0L
                    if (System.currentTimeMillis() - probedAt > REPROBE_AFTER_MS) {
                        runCatching { probeAndStore(server) }
                            .onFailure { Log.w(TAG, "Automatic transcoding probe failed: ${it.message}") }
                    }
                }
        }
    }

    /** Probe [server] and persist the outcome in its options. Returns null when no track is available yet. */
    suspend fun probeAndStore(server: Server): Result? = mutex.withLock {
        val track = sampleTrack(server) ?: return null
        _running.value = server.id
        try {
            val result = probe(server, track)
            val fresh = serverRepository.get(server.id) ?: server
            serverRepository.update(
                fresh.copy(
                    options = fresh.options +
                        (ServerOptions.TRANSCODE_CODECS to CodecSupport.encode(result.supported)) +
                        (ServerOptions.TRANSCODE_PROBED_AT to result.probedAt.toString()),
                ),
            )
            Log.i(TAG, "Transcoding support on ${server.displayHost}: ${result.details}")
            result
        } finally {
            _running.value = null
        }
    }

    suspend fun probe(server: Server, track: Track): Result = withContext(Dispatchers.IO) {
        val provider = providerRegistry.forServer(server)
        val client = httpClients.clientFor(server).newBuilder()
            .connectTimeout(8, TimeUnit.SECONDS)
            .readTimeout(PROBE_READ_TIMEOUT_S, TimeUnit.SECONDS)
            .build()
        val details = linkedMapOf<AudioCodec, String>()
        val supported = mutableSetOf<AudioCodec>()
        for (codec in PROBED) {
            val profile = TranscodeProfile("probe.${codec.name}", "probe", codec, maxBitrateKbps = 96)
            val request = provider.streamRequest(track, profile)
            val outcome = withTimeoutOrNull(PROBE_TOTAL_TIMEOUT_MS) {
                runCatching {
                    val builder = Request.Builder().url(request.url).header("Range", "bytes=0-65535")
                    request.headers.forEach { (k, v) -> builder.header(k, v) }
                    client.newCall(builder.build()).execute().use { response ->
                        val type = response.header("Content-Type")?.lowercase().orEmpty()
                        val bytes = response.body?.byteStream()?.let { stream ->
                            val buf = ByteArray(16_384)
                            var total = 0
                            while (total < MIN_BYTES) {
                                val n = stream.read(buf)
                                if (n < 0) break
                                total += n
                            }
                            total
                        } ?: 0
                        when {
                            !response.isSuccessful -> "HTTP ${response.code}"
                            bytes < MIN_BYTES -> "only $bytes bytes"
                            !matches(codec, type) -> "got $type"
                            else -> "ok"
                        }
                    }
                }.getOrElse { "error: ${it.message ?: it.javaClass.simpleName}" }
            } ?: "timeout"
            details[codec] = outcome
            if (outcome == "ok") supported += codec
        }
        Result(supported, System.currentTimeMillis(), details)
    }

    private suspend fun sampleTrack(server: Server): Track? {
        val tracks = runCatching { libraryRepository.tracks().first() }.getOrDefault(emptyList())
            .filter { it.serverId == server.id }
        // Short tracks keep the probe cheap for the server; anything with audio will do.
        return tracks.filter { it.durationMs > 0 }.minByOrNull { it.durationMs } ?: tracks.firstOrNull()
    }

    private companion object {
        const val TAG = "TranscodeProbe"
        val PROBED = listOf(AudioCodec.OPUS, AudioCodec.MP3, AudioCodec.AAC)
        const val MIN_BYTES = 4_096
        const val PROBE_READ_TIMEOUT_S = 12L
        const val PROBE_TOTAL_TIMEOUT_MS = 15_000L
        const val REPROBE_AFTER_MS = 7L * 24 * 60 * 60 * 1000

        /** Does the response MIME type look like the codec we asked for? */
        fun matches(codec: AudioCodec, type: String): Boolean = when (codec) {
            AudioCodec.OPUS -> "opus" in type || "ogg" in type
            AudioCodec.MP3 -> "mpeg" in type || "mp3" in type
            AudioCodec.AAC -> "aac" in type || "mp4" in type || "m4a" in type
            AudioCodec.VORBIS -> "vorbis" in type || "ogg" in type
            AudioCodec.FLAC -> "flac" in type
            AudioCodec.ORIGINAL -> true
        }
    }
}
