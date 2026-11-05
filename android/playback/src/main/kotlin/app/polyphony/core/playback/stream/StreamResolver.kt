package app.polyphony.core.playback.stream

import android.net.Uri
import androidx.media3.datasource.DataSpec
import androidx.media3.datasource.ResolvingDataSource
import app.polyphony.core.domain.model.PlayNowIds
import app.polyphony.core.domain.model.ServerType
import app.polyphony.core.domain.model.StreamRequest
import app.polyphony.core.domain.repository.DownloadRepository
import app.polyphony.core.domain.repository.LibraryRepository
import app.polyphony.core.domain.repository.NetworkMonitor
import app.polyphony.core.domain.repository.RequestRepository
import app.polyphony.core.domain.repository.SettingsRepository
import app.polyphony.core.domain.transcode.LossySourceRule
import app.polyphony.core.domain.transcode.TranscodeProfile
import app.polyphony.core.domain.transcode.TranscodeDecision
import app.polyphony.core.playback.engine.ServerLookup
import app.polyphony.core.playback.engine.TranscodeDecisionEngine
import app.polyphony.core.playback.queue.MediaItemFactory
import app.polyphony.core.playback.queue.QueueStore
import app.polyphony.core.playback.transcode.CodecSupport
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.runBlocking
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.File
import java.io.IOException
import java.util.concurrent.TimeUnit
import javax.inject.Inject
import javax.inject.Singleton

/** The profile applied to a track when its stream was last opened. */
data class AppliedDecision(val trackId: String, val decision: TranscodeDecision)

/**
 * Turns opaque `polyphony://track/...` URIs into real stream URLs at load time, applying the
 * transcoding decision in force at that moment. Runs on ExoPlayer's loader thread.
 */
@Singleton
class StreamResolver @Inject constructor(
    private val queueStore: QueueStore,
    private val libraryRepository: LibraryRepository,
    private val serverLookup: ServerLookup,
    private val engine: TranscodeDecisionEngine,
    private val downloadRepository: DownloadRepository,
    private val settingsRepository: SettingsRepository,
    private val requestRepository: RequestRepository,
    private val streamCache: StreamCache,
    private val networkMonitor: NetworkMonitor,
    private val okHttpClient: OkHttpClient,
) : ResolvingDataSource.Resolver {

    /** Session memory of transcodes this server could not deliver. */
    val fallbacks = TranscodeFallbackRegistry()

    private val _lastApplied = MutableStateFlow<AppliedDecision?>(null)
    /** Most recent resolution. Note: ExoPlayer preloads the next item, so this may be the *next* track. */
    val lastApplied: StateFlow<AppliedDecision?> = _lastApplied

    private val _applied = MutableStateFlow<Map<String, TranscodeDecision>>(emptyMap())
    /** Decisions applied per track id (bounded), so the UI can show what the current item really uses. */
    val applied: StateFlow<Map<String, TranscodeDecision>> = _applied

    private fun record(trackId: String, decision: TranscodeDecision) {
        _lastApplied.value = AppliedDecision(trackId, decision)
        _applied.update { current ->
            val next = LinkedHashMap(current)
            next.remove(trackId)
            next[trackId] = decision
            while (next.size > 32) next.remove(next.keys.first())
            next
        }
    }

    /**
     * What each song's stream was first opened as (URL, headers, cache key), per track id. ExoPlayer
     * re-opens a song's stream inside the same player period after a network error, possibly from
     * byte 0, and keeps the extractor it picked for the first bytes. Re-resolving there could hand
     * it another format (the decision moved from MP3 on Wi-Fi to Opus on mobile data, or a complete
     * copy of another quality turned up in the cache): the MP3 extractor finds no frames in Opus,
     * reaches the end of input cleanly, and the song skips to the next one partway through. So a
     * song keeps its first resolution until the playback service starts it afresh ([unpin]).
     */
    private val pins = LinkedHashMap<String, DataSpec>()

    @Throws(IOException::class)
    override fun resolveDataSpec(dataSpec: DataSpec): DataSpec {
        val trackId = MediaItemFactory.parseUri(dataSpec.uri)?.second
            ?: return resolve(dataSpec, ahead = false)!!
        synchronized(pins) { pins[trackId] }?.let { pinned ->
            return dataSpec.buildUpon()
                .setUri(pinned.uri)
                .setHttpRequestHeaders(dataSpec.httpRequestHeaders + pinned.httpRequestHeaders)
                .setKey(pinned.key)
                .build()
        }
        val resolved = resolve(dataSpec, ahead = false)!!
        synchronized(pins) {
            pins[trackId] = resolved
            while (pins.size > MAX_PINS) pins.remove(pins.keys.first())
        }
        return resolved
    }

    private val probeClient: OkHttpClient by lazy {
        okHttpClient.newBuilder()
            .connectTimeout(PROBE_TIMEOUT_S, TimeUnit.SECONDS)
            .readTimeout(PROBE_TIMEOUT_S, TimeUnit.SECONDS)
            .callTimeout(PROBE_TIMEOUT_S + 1, TimeUnit.SECONDS)
            .build()
    }

    /** The exact length of a transcode the server has finished, or null while it is still live. */
    private fun probeTranscodeLength(request: StreamRequest): Long? = runCatching {
        val builder = Request.Builder().url(request.url)
            .header("Range", "bytes=0-0")
            .header("Accept-Encoding", "identity")
        request.headers.forEach { (k, v) -> builder.header(k, v) }
        probeClient.newCall(builder.build()).execute().use { response ->
            if (response.code == 206) TranscodeLength.totalFromContentRange(response.header("Content-Range")) else null
        }
    }.getOrNull()

    /**
     * Waits (blocking, off the main thread) up to [timeoutMs] for the stream [uri] would open now
     * to have a known length, so that reopening it makes it seekable. True at once for downloads
     * and completely cached songs.
     */
    fun awaitKnownLength(uri: Uri, timeoutMs: Long): Boolean {
        val deadline = System.currentTimeMillis() + timeoutMs
        while (true) {
            val spec = runCatching { resolve(DataSpec(uri), ahead = true) }.getOrElse { return false } ?: return true
            if (spec.uri.scheme == "file" || TranscodeLength.hasKnownLength(spec.key) ||
                spec.key?.let(streamCache::isComplete) == true
            ) return true
            if (System.currentTimeMillis() >= deadline) return false
            Thread.sleep(LENGTH_POLL_MS)
        }
    }

    /** The next open of [trackId] resolves afresh (the service is starting it in a new player period). */
    fun unpin(trackId: String) {
        synchronized(pins) { pins.remove(trackId) }
    }

    /** Forget every pin but [keep]'s (the current and next songs, whose periods may still be loading). */
    fun retainPins(keep: Set<String>) {
        synchronized(pins) { pins.keys.retainAll(keep) }
    }

    /**
     * What [resolveDataSpec] would play, for fetching a queued song ahead into the stream cache
     * (QueuePrefetcher); null when there's nothing to fetch (a downloaded or Play now song).
     * Records nothing: the song isn't playing.
     */
    @Throws(IOException::class)
    fun resolveAhead(dataSpec: DataSpec): DataSpec? = resolve(dataSpec, ahead = true)

    private fun resolve(dataSpec: DataSpec, ahead: Boolean): DataSpec? {
        val (serverId, trackId) = MediaItemFactory.parseUri(dataSpec.uri) ?: return dataSpec
        PlayNowIds.streamId(trackId)?.let { return if (ahead) null else resolvePlayNow(serverId, trackId, it, dataSpec) }
        val track = queueStore.get(trackId)
            ?: runBlocking { runCatching { libraryRepository.getTrack(trackId) }.getOrNull() }
            ?: throw IOException("Unknown track $trackId")
        if (ahead && isDownloaded(trackId)) return null
        resolveDownloaded(trackId, dataSpec)?.let { return it }
        val provider = serverLookup.providerFor(serverId)
            ?: runBlocking { serverLookup.awaitProvider(serverId) }
            ?: throw IOException("Unknown server $serverId")
        var decision = engine.localDecision()
        val codec = decision.profile.codec
        if (!decision.profile.isPassthrough &&
            (fallbacks.forcesPassthrough(trackId) || fallbacks.isBroken(serverId, codec))
        ) {
            decision = TranscodeDecision(TranscodeProfile.ORIGINAL, TranscodeDecision.Reason.FALLBACK)
        }
        CodecSupport.adjust(decision.profile, CodecSupport.supported(provider.server)).let { adjusted ->
            if (adjusted !== decision.profile) decision = decision.copy(profile = adjusted)
        }
        // An MP3 (or other lossy file) streams as it is unless the connection is struggling.
        decision = LossySourceRule.apply(decision, track)
        val request = provider.streamRequest(track, decision.profile)
        var key = "$trackId|${decision.profile.fingerprint}"
        if (request.isTranscoded && provider.server.type == ServerType.SUBSONIC && !streamCache.isComplete(key)) {
            // A transcode Navidrome has finished has an exact length: under its own key, the player
            // can seek in it and resume it. A live one keeps the plain key (and plays as before).
            key = streamCache.completeKeyWithPrefix(TranscodeLength.lengthKeyPrefix(key))
                ?: probeTranscodeLength(request)?.let { total ->
                    TranscodeLength.keyWithLength(key, total).also { streamCache.storeContentLength(it, total) }
                }
                ?: key
        }
        // Offline or on mobile data, a complete copy already on the phone beats none or another
        // download, whatever quality it was cached at (a song fetched ahead on Wi-Fi, say).
        val network = networkMonitor.state.value
        if (!ahead && (!network.isOnline || network.isMetered) && !streamCache.isComplete(key)) {
            streamCache.completeKey(trackId)?.let { key = it }
        }
        if (!ahead) record(trackId, decision)
        return dataSpec.buildUpon()
            .setUri(Uri.parse(request.url))
            .setHttpRequestHeaders(dataSpec.httpRequestHeaders + request.headers)
            .setKey(key)
            .build()
    }

    /** True when the song plays without a connection: downloaded, or complete in the stream cache. */
    fun isOnPhone(trackId: String): Boolean = isDownloaded(trackId) || streamCache.completeKey(trackId) != null

    private fun isDownloaded(trackId: String): Boolean =
        runBlocking { runCatching { downloadRepository.localFile(trackId) }.getOrNull() }?.filePath?.let { File(it).exists() } == true

    /**
     * A Play now song (continuo#12): continuo serves it while it downloads, untranscoded; the
     * request carries this install's continuo authorisation.
     */
    private fun resolvePlayNow(serverId: String, trackId: String, streamId: String, dataSpec: DataSpec): DataSpec {
        val provider = serverLookup.providerFor(serverId)
            ?: runBlocking { serverLookup.awaitProvider(serverId) }
            ?: throw IOException("Unknown server $serverId")
        val request = runBlocking { runCatching { requestRepository.playNowStream(provider.server, streamId) }.getOrNull() }
            ?: throw IOException("continuo can't serve $trackId")
        record(trackId, TranscodeDecision(TranscodeProfile.ORIGINAL, TranscodeDecision.Reason.DEFAULT))
        return dataSpec.buildUpon()
            .setUri(Uri.parse(request.url))
            .setHttpRequestHeaders(dataSpec.httpRequestHeaders + request.headers)
            .setKey("$trackId|playnow")
            .build()
    }

    /**
     * Prefer a downloaded copy when the user asked for it (or is in offline mode). In offline mode a
     * track without a local file is an error rather than a silent network fetch.
     */
    private fun resolveDownloaded(trackId: String, dataSpec: DataSpec): DataSpec? {
        val downloads = runBlocking { runCatching { settingsRepository.current().downloads }.getOrNull() } ?: return null
        if (!downloads.preferDownloadedForPlayback && !downloads.offlineMode) return null
        val local = runBlocking { runCatching { downloadRepository.localFile(trackId) }.getOrNull() }
        val path = local?.filePath
        if (path == null) {
            if (downloads.offlineMode) throw IOException("Not downloaded; offline mode is on")
            return null
        }
        val file = File(path)
        if (!file.exists()) return if (downloads.offlineMode) throw IOException("Downloaded file is missing") else null
        val profile = engine.profileById(local.profileId) ?: TranscodeProfile.ORIGINAL
        record(trackId, TranscodeDecision(profile, TranscodeDecision.Reason.DOWNLOADED))
        return dataSpec.buildUpon()
            .setUri(Uri.fromFile(file))
            .setHttpRequestHeaders(emptyMap())
            .setKey("$trackId|local")
            .build()
    }

    /**
     * Called by the service when a load fails. Returns true when the failing item was a transcoded
     * stream, in which case it (and later tracks on that server/codec) will resolve to the original
     * file and the caller should re-prepare.
     */
    fun markTranscodeFailure(trackId: String): Boolean {
        val applied = _applied.value[trackId] ?: return false
        if (applied.profile.isPassthrough || applied.reason == TranscodeDecision.Reason.DOWNLOADED) return false
        val serverId = queueStore.get(trackId)?.serverId ?: return false
        val first = fallbacks.markBroken(serverId, applied.profile.codec, trackId)
        if (first) android.util.Log.w("PolyphonyPlayback", "Transcoding to ${applied.profile.codec} failed on server $serverId; falling back to original quality for this session")
        return true
    }

    /** Keep the opaque URI in reported events so logs never leak auth tokens. */
    override fun resolveReportedUri(uri: Uri): Uri = uri

    private companion object {
        /** A bound for pins the service never released; normally only the current and next songs hold one. */
        const val MAX_PINS = 8
        const val PROBE_TIMEOUT_S = 2L
        const val LENGTH_POLL_MS = 500L
    }
}
