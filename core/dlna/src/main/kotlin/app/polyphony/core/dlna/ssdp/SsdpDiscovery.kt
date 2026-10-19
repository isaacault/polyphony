package app.polyphony.core.dlna.ssdp

import android.content.Context
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
    private var multicastLock: WifiManager.MulticastLock? = null

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
        scope.launch(io) { sendSearches(searchSocket ?: return@launch) }
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
                }
            } catch (t: Throwable) {
                Log.w(TAG, "Search socket failed: ${t.message}")
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
                        handleDatagram(String(packet.data, 0, packet.length, Charsets.ISO_8859_1))
                    } catch (_: SocketTimeoutException) {
                        // loop around to re-search
                    }
                }
            } catch (t: Throwable) {
                if (running) Log.w(TAG, "Search loop error: ${t.message}")
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
        for (st in targets) {
            val bytes = SsdpMessage.buildSearch(st).toByteArray(Charsets.US_ASCII)
            repeat(2) {
                runCatching { socket.send(DatagramPacket(bytes, bytes.size, group, SSDP_PORT)) }
                    .onFailure { Log.w(TAG, "M-SEARCH send failed: ${it.message}") }
            }
        }
    }

    private suspend fun notifyLoop() {
        val socket = try {
            MulticastSocket(null).apply {
                reuseAddress = true
                bind(InetSocketAddress(SSDP_PORT))
                soTimeout = RECEIVE_TIMEOUT_MS
            }
        } catch (t: Throwable) {
            Log.i(TAG, "NOTIFY listener unavailable (search-only mode): ${t.message}")
            return
        }
        listenSocket = socket
        val group = InetAddress.getByName(SSDP_ADDRESS)
        var joined = false
        for (nif in candidateInterfaces()) {
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
                    handleDatagram(String(packet.data, 0, packet.length, Charsets.ISO_8859_1))
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

    internal fun handleDatagram(raw: String) {
        val message = SsdpMessage.parse(raw) ?: return
        val udn = message.udn ?: return
        when (message.kind) {
            SsdpMessage.Kind.RESPONSE, SsdpMessage.Kind.NOTIFY_ALIVE, SsdpMessage.Kind.NOTIFY_UPDATE -> {
                if (!message.isMediaRenderer) return
                val location = message.location ?: return
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
