package app.polyphony.core.playback.remote

import android.net.Uri
import android.util.Log
import app.polyphony.core.domain.model.Server
import app.polyphony.core.domain.model.ServerOptions
import app.polyphony.core.domain.repository.NetworkMonitor
import app.polyphony.core.playback.di.PlaybackScope
import app.polyphony.core.playback.engine.ServerLookup
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.net.Inet4Address
import java.net.InetAddress
import java.util.concurrent.ConcurrentHashMap
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Cast and DLNA receivers fetch audio and artwork themselves, so the URL we hand them must be
 * reachable *from the receiver*, not just from the phone. A public hostname often is not: the
 * receiver may use a DNS server that cannot resolve it, or the router may not hairpin traffic to
 * the public address from inside the LAN.
 *
 * Two remedies, applied to every URL sent to a receiver:
 * 1. An explicit per-server receiver address ([ServerOptions.REMOTE_BASE_URL]) replaces the
 *    server's base URL outright. This is the reliable fix and should be a LAN address.
 * 2. Otherwise, for plain-http URLs whose host the phone resolves to a private LAN IP, the IP is
 *    substituted for the hostname (split-horizon DNS case). HTTPS URLs are left alone, because
 *    swapping the host would break certificate validation on the receiver.
 *
 * DNS lookups happen off the main thread whenever the server list or the network changes; the
 * synchronous [forReceiver] only reads the cached results.
 */
@Singleton
class RemoteUrlResolver @Inject constructor(
    private val serverLookup: ServerLookup,
    networkMonitor: NetworkMonitor,
    @PlaybackScope scope: CoroutineScope,
) {
    private val lanIps = ConcurrentHashMap<String, String>()

    init {
        scope.launch {
            combine(serverLookup.servers, networkMonitor.state.map { it.kind }.distinctUntilChanged()) { servers, _ -> servers }
                .collectLatest { servers -> refresh(servers.values) }
        }
    }

    /**
     * What a receiver would be given for [url] if [overrideBaseUrl] were the server's receiver
     * address (ignoring the stored option). Used by the settings screen to test an address before
     * saving it.
     */
    fun previewForReceiver(url: String, server: Server, overrideBaseUrl: String?): String {
        val host = Uri.parse(server.baseUrl).host
        return rewriteForReceiver(url, server.baseUrl, overrideBaseUrl, host?.let { lanIps[it] })
    }

    /** Rewrite [url] (built from [server]) into one the receiver can fetch. */
    fun forReceiver(url: String, server: Server): String {
        val override = server.options[ServerOptions.REMOTE_BASE_URL]?.trim()?.takeIf { it.isNotEmpty() }
        val autoLan = server.options[ServerOptions.REMOTE_AUTO_LAN] != "false"
        val host = Uri.parse(server.baseUrl).host
        val rewritten = rewriteForReceiver(url, server.baseUrl, override, if (autoLan && host != null) lanIps[host] else null)
        if (rewritten != url) Log.d(TAG, "Receiver URL host rewritten: ${Uri.parse(url).host} -> ${Uri.parse(rewritten).host}")
        return rewritten
    }

    private suspend fun refresh(servers: Collection<Server>) = withContext(Dispatchers.IO) {
        val hosts = servers.mapNotNull { Uri.parse(it.baseUrl).host }.toSet()
        lanIps.keys.retainAll(hosts)
        for (host in hosts) {
            if (isIpLiteral(host)) continue
            val lan = runCatching { InetAddress.getAllByName(host) }.getOrDefault(emptyArray())
                .firstOrNull { it is Inet4Address && it.isSiteLocalAddress }
                ?.hostAddress
            if (lan != null) lanIps[host] = lan else lanIps.remove(host)
        }
        if (lanIps.isNotEmpty()) Log.i(TAG, "LAN addresses for receivers: $lanIps")
    }

    private companion object {
        const val TAG = "PolyphonyRemoteUrl"
    }
}

internal fun isIpLiteral(host: String): Boolean =
    host.matches(Regex("""\d{1,3}(\.\d{1,3}){3}""")) || host.contains(':')

/**
 * Pure rewrite used by [RemoteUrlResolver.forReceiver].
 * @param override explicit receiver base URL, wins when set and [url] starts with [baseUrl]
 * @param lanIp phone-resolved private IPv4 for the host of [baseUrl], used only for http URLs
 */
internal fun rewriteForReceiver(url: String, baseUrl: String, override: String?, lanIp: String?): String {
    val base = baseUrl.trimEnd('/')
    if (!override.isNullOrBlank()) {
        val target = override.trim().trimEnd('/').let { if (it.contains("://")) it else "http://$it" }
        return if (url.startsWith(base)) target + url.removePrefix(base) else url
    }
    if (lanIp == null) return url
    val uri = runCatching { java.net.URI(url) }.getOrNull() ?: return url
    val host = uri.host ?: return url
    if (uri.scheme != "http" || isIpLiteral(host)) return url
    return url.replaceFirst("://$host", "://$lanIp")
}
