package app.polyphony.core.playback.engine

import android.net.Uri
import android.util.Log
import app.polyphony.core.domain.model.Server
import app.polyphony.core.domain.model.ServerOptions
import app.polyphony.core.domain.model.ServerType
import app.polyphony.core.domain.repository.NetworkMonitor
import app.polyphony.core.domain.repository.ServerRepository
import app.polyphony.core.domain.transcode.NetworkKind
import app.polyphony.core.playback.di.PlaybackScope
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import java.net.InetAddress
import java.util.concurrent.TimeUnit
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Whether the phone is on the home network: on Wi-Fi, the active server answers at its LAN
 * address (the receiver address, or a base URL that is itself a LAN IP). Mobile data is never
 * home. Null when it cannot be told (no LAN address configured, offline, not checked yet), which
 * leaves the transcoding rules as they were.
 */
@Singleton
class HomeNetworkDetector @Inject constructor(
    serverRepository: ServerRepository,
    networkMonitor: NetworkMonitor,
    okHttpClient: OkHttpClient,
    @PlaybackScope scope: CoroutineScope,
) {
    private val _atHome = MutableStateFlow<Boolean?>(null)
    val atHome: StateFlow<Boolean?> = _atHome.asStateFlow()

    private val client = okHttpClient.newBuilder()
        .connectTimeout(PROBE_TIMEOUT_S, TimeUnit.SECONDS)
        .readTimeout(PROBE_TIMEOUT_S, TimeUnit.SECONDS)
        .callTimeout(PROBE_TIMEOUT_S + 1, TimeUnit.SECONDS)
        .followRedirects(false)
        .build()

    init {
        scope.launch {
            combine(
                serverRepository.activeServer,
                networkMonitor.state.map { Triple(it.kind, it.isMetered, it.isVpn) }.distinctUntilChanged(),
            ) { server, network -> server to network.first }
                .collectLatest { (server, kind) ->
                    val home = when (kind) {
                        NetworkKind.NONE -> null
                        NetworkKind.CELLULAR -> false
                        else -> server?.let { probe(it) }
                    }
                    if (home != _atHome.value) Log.i(TAG, "Home network: ${home ?: "unknown"} (${kind.name.lowercase()})")
                    _atHome.value = home
                }
        }
    }

    /** True when [server] answers at its LAN address, false when it does not, null when it has none. */
    private suspend fun probe(server: Server): Boolean? = withContext(Dispatchers.IO) {
        val base = lanBaseUrl(server) ?: return@withContext null
        // The receiver entry may route only audio paths; an unauthenticated stream request still
        // gets the server's own error document back, which a stranger's device would not send.
        val path = if (server.type == ServerType.SUBSONIC) "/rest/stream?v=1.16.1&c=Polyphony&f=xml" else "/"
        runCatching {
            client.newCall(Request.Builder().url(base + path).build()).execute().use { response ->
                if (server.type == ServerType.SUBSONIC) {
                    val source = response.body?.source() ?: return@use false
                    source.request(MAX_PROBE_BODY) // as much as arrived, up to the cap
                    source.buffer.snapshot().utf8().contains("subsonic-response")
                } else {
                    response.code < 500
                }
            }
        }.getOrDefault(false)
    }

    private companion object {
        const val TAG = "PolyphonyHome"
        const val PROBE_TIMEOUT_S = 2L
        const val MAX_PROBE_BODY = 4_096L

        /** The receiver address, or the base URL when its host is a private LAN address. */
        fun lanBaseUrl(server: Server): String? {
            server.options[ServerOptions.REMOTE_BASE_URL]?.trim()?.takeIf { it.isNotEmpty() }?.let { raw ->
                return (if (raw.contains("://")) raw else "http://$raw").trimEnd('/')
            }
            val host = Uri.parse(server.baseUrl).host ?: return null
            val literal = host.matches(Regex("""\d{1,3}(\.\d{1,3}){3}"""))
            if (!literal) return null
            val address = runCatching { InetAddress.getByName(host) }.getOrNull() ?: return null
            return if (address.isSiteLocalAddress) server.baseUrl.trimEnd('/') else null
        }
    }
}
