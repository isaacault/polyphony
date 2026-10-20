package app.polyphony.core.dlna.ssdp

import java.net.Inet4Address
import java.util.Collections
import java.util.concurrent.atomic.AtomicInteger
import android.content.Context
import android.net.NetworkCapabilities
import android.net.Network
import android.net.ConnectivityManager
import android.net.wifi.WifiManager
import android.util.Log
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancelChildren
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.plus
import java.net.DatagramPacket
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.MulticastSocket
import java.net.NetworkInterface
import java.net.SocketTimeoutException

sealed interface SsdpEvent {
    data class Alive(val udn: String, val location: String, val maxAgeSeconds: Int) : SsdpEvent
    data class ByeBye(val udn: String) : SsdpEvent
}

/** A renderer announcement we currently consider alive. */
data class SsdpEntry(val udn: String, val location: String, val expiresAtMs: Long)

/**
 * SSDP discovery for UPnP MediaRenderers: periodic M-SEARCH plus a passive NOTIFY listener.
 * Everything runs on [io]; sockets are closed cleanly on [stop]. Renderer bugs (bad datagrams,
 * odd headers) are ignored rather than propagated.
 */
class SsdpDiscovery(
    context: Context,
    parentScope: CoroutineScope,
    private val io: CoroutineDispatcher = Dispatchers.IO,
    private val clock: () -> Long = System::currentTimeMillis,
    private val searchIntervalMs: Long = 10_000,
    private val defaultMaxAgeSeconds: Int = 60,
) {
    private val appContext = context.applicationContext
    private val scope = parentScope + SupervisorJob(parentScope.coroutineContext[Job])
    private val wifi = appContext.getSystemService(Context.WIFI_SERVICE) as? WifiManager
    private val connectivity = appContext.getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager

    /** The Wi-Fi network itself (not a VPN on top of it), so discovery bypasses any tunnel. */
    private fun wifiNetwork(): Network? = connectivity?.let { cm ->
        @Suppress("DEPRECATION")
        cm.allNetworks.firstOrNull { n ->
            cm.getNetworkCapabilities(n)?.let { it.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) && !it.hasTransport(NetworkCapabilities.TRANSPORT_VPN) } == true
        }
    }

    private fun wifiInterface(network: Network?): NetworkInterface? = network
        ?.let { connectivity?.getLinkProperties(it)?.interfaceName }
        ?.let { name -> runCatching { NetworkInterface.getByName(name) }.getOrNull() }

    /** Pin [socket] to Wi-Fi: traffic and multicast go out that interface even with a VPN active. */
    private fun pinToWifi(socket: MulticastSocket, what: String) {
        val network = wifiNetwork()
        val nif = wifiInterface(network)
        ifaceName = nif?.name
        wifiAddress = network?.let { n -> connectivity?.getLinkProperties(n)?.linkAddresses?.firstOrNull { it.address is Inet4Address }?.toString() }
        runCatching { network?.bindSocket(socket) }.onFailure { Log.w(TAG, "$what: bindSocket failed: ${it.message}") }
        runCatching { nif?.let { socket.networkInterface = it } }.onFailure { Log.w(TAG, "$what: setNetworkInterface failed: ${it.message}") }
        Log.i(TAG, "$what on ${nif?.name ?: "default route"}${if (network == null) " (no Wi-Fi network found)" else ""}")
    }
    private var multicastLock: WifiManager.MulticastLock? = null

    // ---- diagnostics: enough to tell "no packets at all" from "packets we filtered out" ----
    private val searchesSent = AtomicInteger()
    private val datagramsReceived = AtomicInteger()
    private val responsesSeen = AtomicInteger()
    private val notifiesSeen = AtomicInteger()
    private val otherTypes: MutableSet<String> = Collections.synchronizedSet(LinkedHashSet())
    private val renderersLogged: MutableSet<String> = Collections.synchronizedSet(HashSet())
    @Volatile private var ifaceName: String? = null
    @Volatile private var wifiAddress: String? = null
    @Volatile private var lastDatagramAt: Long? = null
    @Volatile private var lastError: String? = null

    /** Snapshot for the diagnostics export. */
    fun stats(): SsdpStats = SsdpStats(
        running = running,
        interfaceName = ifaceName,
        wifiAddress = wifiAddress,
        multicastLockHeld = multicastLock?.isHeld == true,
        searchesSent = searchesSent.get(),
        datagramsReceived = datagramsReceived.get(),
        responses = responsesSeen.get(),
        notifies = notifiesSeen.get(),
        renderers = _entries.value.size,
        otherDeviceTypes = synchronized(otherTypes) { otherTypes.toList() },
        lastDatagramAgoMs = lastDatagramAt?.let { clock() - it },
        lastError = lastError,
    )

    private val _entries = MutableStateFlow<Map<String, SsdpEntry>>(emptyMap())
    val entries: StateFlow<Map<String, SsdpEntry>> = _entries.asStateFlow()

    private val _events = MutableSharedFlow<SsdpEvent>(extraBufferCapacity = 64)
    val events: SharedFlow<SsdpEvent> = _events.asSharedFlow()

    @Volatile private var running = false
    private var searchSocket: MulticastSocket? = null
    private var listenSocket: MulticastSocket? = null

    @Synchronized
    fun start() {
        if (running) return
        running = true
        acquireLock()
        scope.launch(io) { searchLoop() }
        scope.launch(io) { notifyLoop() }
        scope.launch(io) { expiryLoop() }
    }

    @Synchronized
    fun stop() {
        if (!running) return
        running = false
        runCatching { searchSocket?.close() }
        runCatching { listenSocket?.close() }
        searchSocket = null
        listenSocket = null
        scope.coroutineContext.cancelChildren()
        releaseLock()
        _entries.value = emptyMap()
    }

    /** Forget everything and search again (after a network change). */
    fun restart() {
        stop()
        start()
    }

    /** Fire an extra M-SEARCH now (e.g. when the user opens the device picker). */
    fun searchNow() {
        if (!running) return
        // The user is looking at the picker: ask broadly, some renderers only answer ssdp:all.
        scope.launch(io) { sendSearches(searchSocket ?: return@launch, includeAll = true) }
    }

    private fun acquireLock() {
        runCatching {
            multicastLock = wifi?.createMulticastLock("polyphony-dlna")?.apply {
                setReferenceCounted(false)
                acquire()
            }
        }.onFailure { Log.w(TAG, "Multicast lock unavailable: ${it.message}") }
    }

    private fun releaseLock() {
        runCatching { multicastLock?.takeIf { it.isHeld }?.release() }
        multicastLock = null
    }

    private suspend fun searchLoop() {
        while (running && currentCoroutineIsActive()) {
            val socket = try {
                MulticastSocket(null).apply {
                    reuseAddress = true
                    bind(InetSocketAddress(0))
                    soTimeout = RECEIVE_TIMEOUT_MS
                    runCatching { timeToLive = 4 }
                    pinToWifi(this, "SSDP search socket")
                }
            } catch (t: Throwable) {
                Log.w(TAG, "Search socket failed: ${t.message}")
                lastError = "search socket: ${t.message}"
                delay(searchIntervalMs)
                continue
            }
            searchSocket = socket
            try {
                var lastSearch = 0L
                val buffer = ByteArray(8192)
                while (running && currentCoroutineIsActive()) {
                    val now = clock()
                    if (now - lastSearch >= searchIntervalMs) {
                        sendSearches(socket, includeAll = lastSearch == 0L)
                        lastSearch = now
                    }
                    val packet = DatagramPacket(buffer, buffer.size)
                    try {
                        socket.receive(packet)
                        onDatagram(packet)
                    } catch (_: SocketTimeoutException) {
                        // loop around to re-search
                    }
                }
            } catch (t: Throwable) {
                if (running) { Log.w(TAG, "Search loop error: ${t.message}"); lastError = "search loop: ${t.message}" }
            } finally {
                runCatching { socket.close() }
                if (searchSocket === socket) searchSocket = null
            }
            if (running) delay(1_000)
        }
    }

    private fun sendSearches(socket: MulticastSocket, includeAll: Boolean = false) {
        val group = InetAddress.getByName(SSDP_ADDRESS)
        val targets = buildList {
            add(SsdpMessage.MEDIA_RENDERER_ST)
            if (includeAll) add("ssdp:all")
        }
        var failed: String? = null
        for (st in targets) {
            val bytes = SsdpMessage.buildSearch(st).toByteArray(Charsets.US_ASCII)
            repeat(2) {
                runCatching { socket.send(DatagramPacket(bytes, bytes.size, group, SSDP_PORT)) }
                    .onFailure { failed = it.message; Log.w(TAG, "M-SEARCH send failed: ${it.message}") }
            }
        }
        failed?.let { lastError = "send: $it" }
        val n = searchesSent.incrementAndGet()
        Log.i(TAG, "M-SEARCH #$n targets=$targets via ${ifaceName ?: "default route"} from ${wifiAddress ?: "?"} (received so far: ${datagramsReceived.get()})")
    }

    private fun onDatagram(packet: DatagramPacket) {
        val count = datagramsReceived.incrementAndGet()
        lastDatagramAt = clock()
        if (count == 1) Log.i(TAG, "First SSDP packet received, from ${packet.address?.hostAddress}")
        handleDatagram(String(packet.data, 0, packet.length, Charsets.ISO_8859_1), packet.address?.hostAddress)
    }

    private suspend fun notifyLoop() {
        val socket = try {
            MulticastSocket(null).apply {
                reuseAddress = true
                bind(InetSocketAddress(SSDP_PORT))
                soTimeout = RECEIVE_TIMEOUT_MS
                pinToWifi(this, "SSDP NOTIFY listener")
            }
        } catch (t: Throwable) {
            Log.i(TAG, "NOTIFY listener unavailable (search-only mode): ${t.message}")
            return
        }
        listenSocket = socket
        val group = InetAddress.getByName(SSDP_ADDRESS)
        var joined = false
        val preferred = wifiInterface(wifiNetwork())
        for (nif in listOfNotNull(preferred) + candidateInterfaces().filter { it != preferred }) {
            runCatching { socket.joinGroup(InetSocketAddress(group, SSDP_PORT), nif); joined = true }
        }
        if (!joined) {
            @Suppress("DEPRECATION")
            runCatching { socket.joinGroup(group); joined = true }
        }
        if (!joined) {
            Log.i(TAG, "Could not join SSDP multicast group; search-only mode")
            runCatching { socket.close() }
            return
        }
        val buffer = ByteArray(8192)
        try {
            while (running && currentCoroutineIsActive()) {
                val packet = DatagramPacket(buffer, buffer.size)
                try {
                    socket.receive(packet)
                    onDatagram(packet)
                } catch (_: SocketTimeoutException) {
                }
            }
        } catch (t: Throwable) {
            if (running) Log.w(TAG, "NOTIFY loop error: ${t.message}")
        } finally {
            runCatching { socket.close() }
            if (listenSocket === socket) listenSocket = null
        }
    }

    private suspend fun expiryLoop() {
        while (running && currentCoroutineIsActive()) {
            delay(5_000)
            val now = clock()
            val expired = _entries.value.values.filter { it.expiresAtMs <= now }
            if (expired.isNotEmpty()) {
                _entries.update { map -> map.filterValues { it.expiresAtMs > now } }
                expired.forEach { _events.tryEmit(SsdpEvent.ByeBye(it.udn)) }
            }
        }
    }

    internal fun handleDatagram(raw: String, from: String? = null) {
        val message = SsdpMessage.parse(raw) ?: return
        if (message.kind == SsdpMessage.Kind.RESPONSE) responsesSeen.incrementAndGet() else notifiesSeen.incrementAndGet()
        val udn = message.udn ?: return
        when (message.kind) {
            SsdpMessage.Kind.RESPONSE, SsdpMessage.Kind.NOTIFY_ALIVE, SsdpMessage.Kind.NOTIFY_UPDATE -> {
                if (!message.isMediaRenderer) {
                    val type = message.type ?: "?"
                    if (otherTypes.size < 16 && otherTypes.add(type)) Log.d(TAG, "Non-renderer device on the LAN: $type from $from")
                    return
                }
                val location = message.location ?: return
                if (renderersLogged.add(udn)) Log.i(TAG, "SSDP renderer udn=$udn location=$location from=$from")
                val maxAge = (message.maxAgeSeconds ?: defaultMaxAgeSeconds).coerceIn(15, 7_200)
                val entry = SsdpEntry(udn, location, clock() + maxAge * 1_000L)
                val previous = _entries.value[udn]
                _entries.update { it + (udn to entry) }
                if (previous == null || previous.location != location) {
                    _events.tryEmit(SsdpEvent.Alive(udn, location, maxAge))
                }
            }
            SsdpMessage.Kind.NOTIFY_BYEBYE -> {
                if (_entries.value.containsKey(udn)) {
                    _entries.update { it - udn }
                    _events.tryEmit(SsdpEvent.ByeBye(udn))
                }
            }
            SsdpMessage.Kind.OTHER -> Unit
        }
    }

    private fun candidateInterfaces(): List<NetworkInterface> = runCatching {
        NetworkInterface.getNetworkInterfaces().toList()
            .filter { it.isUp && !it.isLoopback && it.supportsMulticast() }
            .sortedBy { if (it.name.startsWith("wlan")) 0 else 1 }
    }.getOrDefault(emptyList())

    private suspend fun currentCoroutineIsActive(): Boolean =
        kotlinx.coroutines.currentCoroutineContext().isActive

    private companion object {
        const val TAG = "SsdpDiscovery"
        const val SSDP_ADDRESS = "239.255.255.250"
        const val SSDP_PORT = 1900
        const val RECEIVE_TIMEOUT_MS = 1_500
    }
}

/** What SSDP discovery has done since it started; see [SsdpDiscovery.stats]. */
data class SsdpStats(
    val running: Boolean,
    val interfaceName: String?,
    val wifiAddress: String?,
    val multicastLockHeld: Boolean,
    val searchesSent: Int,
    val datagramsReceived: Int,
    val responses: Int,
    val notifies: Int,
    val renderers: Int,
    /** Search targets / notification types of non-renderer devices that did answer (routers, TVs, servers). */
    val otherDeviceTypes: List<String>,
    val lastDatagramAgoMs: Long?,
    val lastError: String?,
) {
    fun summary(): String =
        "running=$running iface=${interfaceName ?: "?"} wifi=${wifiAddress ?: "?"} multicastLock=$multicastLockHeld " +
            "searches=$searchesSent received=$datagramsReceived responses=$responses notifies=$notifies renderers=$renderers " +
            "lastPacket=${lastDatagramAgoMs?.let { "${it / 1000}s ago" } ?: "never"} otherDevices=$otherDeviceTypes" +
            (lastError?.let { " lastError=\"$it\"" } ?: "")
}
