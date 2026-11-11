package app.polyphony.core.playback.stream

import android.net.Uri
import androidx.media3.common.MediaItem
import androidx.media3.datasource.DataSource
import androidx.media3.datasource.DataSpec
import androidx.media3.datasource.HttpDataSource
import androidx.media3.datasource.ResolvingDataSource
import androidx.media3.exoplayer.drm.DrmSessionManagerProvider
import androidx.media3.exoplayer.source.MediaSource
import androidx.media3.exoplayer.upstream.LoadErrorHandlingPolicy
import app.polyphony.core.domain.model.Server
import app.polyphony.core.domain.repository.RequestRepository
import app.polyphony.core.domain.transcode.AdaptiveStreaming
import app.polyphony.core.playback.engine.ServerLookup
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
class ContinuoStreams(private val requests: RequestRepository) {
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

    /** The authorising headers for [server]: cached, or fetched (blocking: loader threads only). */
    fun headersFor(server: Server, refresh: Boolean = false): Map<String, String> {
        if (!refresh) headers[server.id]?.let { return it }
        val fresh = runBlocking { runCatching { requests.streamHeaders(server, refresh) }.getOrDefault(emptyMap()) }
        if (fresh.isNotEmpty()) headers[server.id] = fresh
        return fresh
    }
}

/**
 * Opens an HLS song: its opaque `polyphony://track/...` URI becomes continuo's master playlist,
 * and every request to continuo's stream URLs gets the authorising headers. Reports real URLs, so
 * the playlists' relative URIs (`64/index.m3u8`, `3.m4s`) resolve against continuo's address.
 * Runs on ExoPlayer's loader threads.
 */
class ContinuoHlsResolver(
    private val serverLookup: ServerLookup,
    private val streams: ContinuoStreams,
    private val onOpened: (trackId: String, server: Server) -> Unit = { _, _ -> },
) : ResolvingDataSource.Resolver {
    override fun resolveDataSpec(dataSpec: DataSpec): DataSpec {
        var spec = dataSpec
        MediaItemFactory.parseUri(dataSpec.uri)?.let { (serverId, trackId) ->
            val server = serverLookup.providerFor(serverId)?.server
                ?: runBlocking { serverLookup.awaitProvider(serverId) }?.server
                ?: throw UnplayableSongException("Unknown server $serverId")
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
    private val serverLookup: ServerLookup,
    private val streams: ContinuoStreams,
) : DataSource by upstream {
    override fun open(dataSpec: DataSpec): Long = try {
        upstream.open(dataSpec)
    } catch (e: HttpDataSource.InvalidResponseCodeException) {
        if (e.responseCode != 401) throw e
        val server = MediaItemFactory.parseUri(dataSpec.uri)?.let { serverLookup.providerFor(it.first)?.server }
            ?: streams.serverFor(e.dataSpec.uri)
            ?: throw e
        if (streams.headersFor(server, refresh = true).isEmpty()) throw e
        runCatching { upstream.close() }
        upstream.open(dataSpec)
    }

    class Factory(
        private val upstream: DataSource.Factory,
        private val serverLookup: ServerLookup,
        private val streams: ContinuoStreams,
    ) : DataSource.Factory {
        override fun createDataSource(): DataSource = RefreshingAuthDataSource(upstream.createDataSource(), serverLookup, streams)
    }
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
