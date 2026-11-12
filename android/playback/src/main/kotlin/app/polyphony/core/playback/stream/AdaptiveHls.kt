package app.polyphony.core.playback.stream

import android.net.Uri
import androidx.media3.common.MediaItem
import androidx.media3.datasource.DataSource
import androidx.media3.datasource.DataSpec
import androidx.media3.datasource.HttpDataSource
import androidx.media3.datasource.ResolvingDataSource
import androidx.media3.exoplayer.drm.DrmSessionManagerProvider
import androidx.media3.exoplayer.hls.HlsMediaSource
import androidx.media3.exoplayer.source.MediaSource
import androidx.media3.exoplayer.trackselection.DefaultTrackSelector
import androidx.media3.exoplayer.upstream.DefaultLoadErrorHandlingPolicy
import androidx.media3.exoplayer.upstream.LoadErrorHandlingPolicy
import app.polyphony.core.domain.model.Server
import app.polyphony.core.domain.repository.RequestRepository
import app.polyphony.core.domain.transcode.AdaptiveStreaming
import app.polyphony.core.playback.queue.MediaItemFactory
import kotlinx.coroutines.runBlocking
import java.net.URLEncoder
import java.util.concurrent.ConcurrentHashMap

/**
 * continuo's adaptive streams (#185): where a song's HLS lives, and what authorises it.
 *
 * Playlists and segments carry this install's continuo bearer token in a header, never in the URL
 * (so logs and the stream cache's keys hold none). Tokens expire within the hour, inside a long
 * session: [RefreshingAuthDataSource] asks for a fresh one when continuo refuses the last.
 */
class ContinuoStreams(private val fetchHeaders: suspend (server: Server, refresh: Boolean) -> Map<String, String>) {
    constructor(requests: RequestRepository) : this({ server, refresh -> requests.streamHeaders(server, refresh) })

    private val servers = ConcurrentHashMap<String, Server>()   // stream base -> server
    private val headers = ConcurrentHashMap<String, Map<String, String>>()   // server id -> headers

    private fun base(server: Server) = server.baseUrl.trimEnd('/') + "/v1/stream/"

    fun masterUrl(server: Server, trackId: String): String {
        servers[base(server)] = server
        return base(server) + URLEncoder.encode(trackId, "UTF-8") + "/master.m3u8"
    }

    /** The continuo server a playlist or segment URL belongs to, once one of its songs was opened. */
    fun serverFor(uri: Uri): Server? {
        val url = uri.toString()
        return servers.entries.firstOrNull { url.startsWith(it.key) }?.value
    }

    /**
     * The authorising headers for [server]: cached, or fetched (blocking: loader threads only).
     * When fetching just failed (offline, with no token yet since the app started), none are
     * fetched again for [RETRY_AFTER_MS]: the resolver runs for every playlist and segment, even
     * those the stream cache serves, and each would otherwise wait on the network again.
     */
    fun headersFor(server: Server, refresh: Boolean = false): Map<String, String> {
        if (!refresh) {
            headers[server.id]?.let { return it }
            failedAt[server.id]?.let { if (now() - it < RETRY_AFTER_MS) return emptyMap() }
        }
        val fresh = runBlocking { runCatching { fetchHeaders(server, refresh) }.getOrDefault(emptyMap()) }
        if (fresh.isNotEmpty()) {
            headers[server.id] = fresh
            failedAt.remove(server.id)
        } else {
            failedAt[server.id] = now()
        }
        return fresh
    }

    private val failedAt = ConcurrentHashMap<String, Long>()   // server id -> when fetching failed

    private companion object {
        const val RETRY_AFTER_MS = 15_000L
        fun now(): Long = System.nanoTime() / 1_000_000
    }
}

/**
 * Opens an HLS song: its opaque `polyphony://track/...` URI becomes continuo's master playlist,
 * and every request to continuo's stream URLs gets the authorising headers. Reports real URLs, so
 * the playlists' relative URIs (`64/index.m3u8`, `3.m4s`) resolve against continuo's address.
 * Runs on ExoPlayer's loader threads.
 */
class ContinuoHlsResolver(
    /** The server with this id (blocking: loader threads only), null when none is set up. */
    private val serverFor: (serverId: String) -> Server?,
    private val streams: ContinuoStreams,
    private val onOpened: (trackId: String, server: Server) -> Unit = { _, _ -> },
) : ResolvingDataSource.Resolver {
    override fun resolveDataSpec(dataSpec: DataSpec): DataSpec {
        var spec = dataSpec
        MediaItemFactory.parseUri(dataSpec.uri)?.let { (serverId, trackId) ->
            val server = serverFor(serverId) ?: throw UnplayableSongException("Unknown server $serverId")
            spec = spec.buildUpon().setUri(Uri.parse(streams.masterUrl(server, trackId))).build()
            onOpened(trackId, server)
        }
        val server = streams.serverFor(spec.uri) ?: return spec
        return spec.withAdditionalHeaders(streams.headersFor(server))
    }
}

/** Retries a request continuo refused (401: the token expired) once, with a fresh token. */
class RefreshingAuthDataSource(
    private val upstream: DataSource,
    private val serverFor: (serverId: String) -> Server?,
    private val streams: ContinuoStreams,
) : DataSource by upstream {
    override fun open(dataSpec: DataSpec): Long = try {
        upstream.open(dataSpec)
    } catch (e: HttpDataSource.InvalidResponseCodeException) {
        if (e.responseCode != 401) throw e
        val server = MediaItemFactory.parseUri(dataSpec.uri)?.let { serverFor(it.first) }
            ?: streams.serverFor(e.dataSpec.uri)
            ?: throw e
        if (streams.headersFor(server, refresh = true).isEmpty()) throw e
        runCatching { upstream.close() }
        upstream.open(dataSpec)
    }

    class Factory(
        private val upstream: DataSource.Factory,
        private val serverFor: (serverId: String) -> Server?,
        private val streams: ContinuoStreams,
    ) : DataSource.Factory {
        override fun createDataSource(): DataSource = RefreshingAuthDataSource(upstream.createDataSource(), serverFor, streams)
    }
}

/**
 * The adaptive streams' data sources, as the local player and fetching ahead use them:
 * [player] reads through the stream cache (`cached`); [network] goes straight to continuo, for
 * fetching ahead, which writes the cache itself under the same keys (continuo's URLs: the
 * resolver runs above the cache). Both carry the token and renew it on a 401.
 */
class HlsDataSources(
    cached: DataSource.Factory,
    network: DataSource.Factory,
    serverFor: (serverId: String) -> Server?,
    val streams: ContinuoStreams,
    onOpened: (trackId: String, server: Server) -> Unit = { _, _ -> },
) {
    private val resolver = ContinuoHlsResolver(serverFor, streams, onOpened)
    val player: DataSource.Factory =
        RefreshingAuthDataSource.Factory(ResolvingDataSource.Factory(cached, resolver), serverFor, streams)
    val network: DataSource.Factory =
        RefreshingAuthDataSource.Factory(ResolvingDataSource.Factory(network, resolver), serverFor, streams)

    /**
     * Adaptive songs' media sources: the master playlist's CODECS let them prepare without a
     * segment, and offline ([online] false) a rendition that can't load gives way to another
     * ([OfflineRenditionFallback]).
     */
    fun mediaSourceFactory(online: () -> Boolean = { true }): HlsMediaSource.Factory =
        HlsMediaSource.Factory(player)
            .setAllowChunklessPreparation(true)
            .setLoadErrorHandlingPolicy(OfflineRenditionFallback(online))
}

/**
 * Offline, only what's in the stream cache plays, and a song fetched ahead may be there at
 * another level than the one the player picks (fetched before the connection turned flaky, at
 * the level playing then). A rendition that can't connect is set aside for a minute, so the
 * player moves on to another, until it finds the one in the cache. Online, Media3's own handling
 * (retries, then the error PlaybackService decides on).
 */
class OfflineRenditionFallback(private val online: () -> Boolean) : DefaultLoadErrorHandlingPolicy() {
    override fun getFallbackSelectionFor(
        fallbackOptions: LoadErrorHandlingPolicy.FallbackOptions,
        loadErrorInfo: LoadErrorHandlingPolicy.LoadErrorInfo,
    ): LoadErrorHandlingPolicy.FallbackSelection? {
        val e = loadErrorInfo.exception
        val couldNotConnect = e is HttpDataSource.HttpDataSourceException &&
            e !is HttpDataSource.InvalidResponseCodeException && e !is HttpDataSource.InvalidContentTypeException
        if (!online() && couldNotConnect && fallbackOptions.isFallbackAvailable(LoadErrorHandlingPolicy.FALLBACK_TYPE_TRACK)) {
            return LoadErrorHandlingPolicy.FallbackSelection(LoadErrorHandlingPolicy.FALLBACK_TYPE_TRACK, SET_ASIDE_MS)
        }
        return super.getFallbackSelectionFor(fallbackOptions, loadErrorInfo)
    }

    private companion object {
        const val SET_ASIDE_MS = 60_000L
    }
}

/**
 * The local players' track selection for adaptive streams: the cap as `maxAudioBitrate`
 * ([AdaptiveStreaming.selectorMaxBitrate]), and adaptation between renditions allowed although
 * the master playlist leaves their channel count and sample rate unstated (continuo's are all
 * 48 kHz Opus with the source's channels). Without that, Media3 counts them incompatible and
 * picks one rendition per song, kept whatever the throughput does (HlsPlaybackTest).
 */
fun DefaultTrackSelector.Parameters.Builder.forAdaptiveStreams(capKbps: Int): DefaultTrackSelector.Parameters.Builder = apply {
    setMaxAudioBitrate(AdaptiveStreaming.selectorMaxBitrate(capKbps))
    setAllowAudioMixedChannelCountAdaptiveness(true)
    setAllowAudioMixedSampleRateAdaptiveness(true)
}

/**
 * Each song's media source: HLS from continuo when [route] says so (AdaptiveStreaming.route),
 * otherwise the progressive stream every other song plays as. Items keep their opaque URIs either
 * way, so the queue, crossfade, reporting and restore see the same songs.
 */
class AdaptiveMediaSourceFactory(
    private val progressive: MediaSource.Factory,
    private val hls: MediaSource.Factory,
    private val route: (MediaItem) -> AdaptiveStreaming.Route,
) : MediaSource.Factory {
    override fun setDrmSessionManagerProvider(drmSessionManagerProvider: DrmSessionManagerProvider): MediaSource.Factory = apply {
        progressive.setDrmSessionManagerProvider(drmSessionManagerProvider)
        hls.setDrmSessionManagerProvider(drmSessionManagerProvider)
    }

    override fun setLoadErrorHandlingPolicy(loadErrorHandlingPolicy: LoadErrorHandlingPolicy): MediaSource.Factory = apply {
        progressive.setLoadErrorHandlingPolicy(loadErrorHandlingPolicy)
        hls.setLoadErrorHandlingPolicy(loadErrorHandlingPolicy)
    }

    override fun getSupportedTypes(): IntArray = (progressive.supportedTypes + hls.supportedTypes).distinct().toIntArray()

    override fun createMediaSource(mediaItem: MediaItem): MediaSource =
        if (route(mediaItem) == AdaptiveStreaming.Route.HLS) hls.createMediaSource(mediaItem)
        else progressive.createMediaSource(mediaItem)
}
