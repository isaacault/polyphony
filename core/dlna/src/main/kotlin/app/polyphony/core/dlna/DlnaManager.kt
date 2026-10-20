package app.polyphony.core.dlna

import android.content.Context
import android.os.Handler
import android.os.Looper
import android.util.Log
import androidx.media3.common.MediaItem
import app.polyphony.core.dlna.engine.DlnaMediaResolver
import app.polyphony.core.dlna.engine.DlnaPlaybackEngine
import app.polyphony.core.dlna.engine.RendererTransport
import app.polyphony.core.dlna.player.DlnaPlayer
import app.polyphony.core.dlna.service.AvTransportService
import app.polyphony.core.dlna.service.ConnectionManagerService
import app.polyphony.core.dlna.service.RenderingControlService
import app.polyphony.core.dlna.soap.SoapClient
import app.polyphony.core.dlna.ssdp.SsdpDiscovery
import app.polyphony.core.dlna.ssdp.SsdpEvent
import app.polyphony.core.dlna.upnp.DeviceDescriptionClient
import app.polyphony.core.dlna.upnp.UpnpDevice
import app.polyphony.core.domain.playback.CastState
import app.polyphony.core.domain.playback.DlnaController
import app.polyphony.core.domain.playback.DlnaRenderer
import app.polyphony.core.domain.repository.TranscodeProfileRepository
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.job
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import java.util.Collections
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.math.roundToInt

/**
 * Discovery + session owner for UPnP renderers. The playback service registers a
 * [DlnaSessionListener] and a [DlnaMediaResolver]; the UI drives it through [DlnaController].
 */
@Singleton
class DlnaManager @Inject constructor(
    @ApplicationContext private val context: Context,
    okHttpClient: OkHttpClient,
    private val transcodeProfileRepository: TranscodeProfileRepository,
) : DlnaController {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val engineDispatcher = Dispatchers.Default.limitedParallelism(1)
    private val mainHandler = Handler(Looper.getMainLooper())

    private val discovery = SsdpDiscovery(context, scope)
    private val descriptions = DeviceDescriptionClient(okHttpClient)
    private val soap = SoapClient(okHttpClient)

    private data class Known(val device: UpnpDevice, val renderer: DlnaRenderer)

    private val known = MutableStateFlow<Map<String, Known>>(emptyMap())
    private val inFlight: MutableSet<String> = Collections.synchronizedSet(HashSet())
    private val rejected: MutableList<String> = Collections.synchronizedList(ArrayList())
    private val failed = java.util.concurrent.ConcurrentHashMap<String, Long>()

    /** Discovery state for the diagnostics export. */
    fun diagnostics(): DlnaDiagnostics = DlnaDiagnostics(
        ssdp = discovery.stats(),
        renderers = renderers.value,
        rejected = synchronized(rejected) { rejected.toList() },
    )

    override val renderers: StateFlow<List<DlnaRenderer>> = known
        .map { map -> map.values.map { it.renderer }.sortedBy { it.friendlyName.lowercase() } }
        .stateIn(scope, SharingStarted.Eagerly, emptyList())

    private val _state = MutableStateFlow(CastState(isAvailable = true))
    override val state: StateFlow<CastState> = _state.asStateFlow()

    private val _activePlayer = MutableStateFlow<DlnaPlayer?>(null)
    /** The player for the connected renderer, if any. Main-thread object. */
    val activePlayer: StateFlow<DlnaPlayer?> = _activePlayer.asStateFlow()

    @Volatile private var sessionListener: DlnaSessionListener? = null
    @Volatile private var mediaResolver: DlnaMediaResolver? = null
    private var discoveryRefs = 0
    private var eventJob: Job? = null
    private var volumeJob: Job? = null
    private var engineScope: CoroutineScope? = null

    fun setSessionListener(listener: DlnaSessionListener?) { sessionListener = listener }

    fun setMediaResolver(resolver: DlnaMediaResolver?) { mediaResolver = resolver }

    fun renderer(udn: String): DlnaRenderer? = known.value[udn]?.renderer

    fun activeRenderer(): DlnaRenderer? = _activePlayer.value?.renderer

    // ---- discovery -------------------------------------------------------------------------------

    @Synchronized
    override fun startDiscovery() {
        discoveryRefs++
        if (discoveryRefs == 1) {
            // Subscribe before searching: a renderer can answer within milliseconds of the first
            // M-SEARCH and is only announced once per session. The entries StateFlow replays what
            // is already known, so nothing depends on catching the one Alive event.
            eventJob = scope.launch {
                launch { discovery.events.collect { handle(it) } }
                discovery.entries.collect { entries -> entries.values.forEach { ensureDescribed(it.udn, it.location) } }
            }
            discovery.start()
        } else {
            discovery.searchNow()
            discovery.entries.value.values.forEach { ensureDescribed(it.udn, it.location) }
        }
    }

    /** Fetch the description of an announced renderer we do not know (or know at another address); failures retry after a pause. */
    private fun ensureDescribed(udn: String, location: String) {
        val existing = known.value[udn]
        if (existing != null && existing.device.location == location) return
        val failedAt = failed[udn]
        if (failedAt != null && System.currentTimeMillis() - failedAt < RETRY_FAILED_AFTER_MS) return
        scope.launch { fetch(udn, location) }
    }

    @Synchronized
    override fun stopDiscovery() {
        if (discoveryRefs == 0) return
        discoveryRefs--
        if (discoveryRefs == 0) {
            eventJob?.cancel()
            eventJob = null
            discovery.stop()
            failed.clear()
            val connected = activeRenderer()?.udn
            known.update { map -> map.filterKeys { it == connected } }
        }
    }

    /** Call when the Wi-Fi network changes so stale renderers are dropped and re-found. */
    fun onNetworkChanged() {
        if (discoveryRefs > 0) {
            val connected = activeRenderer()?.udn
            known.update { map -> map.filterKeys { it == connected } }
            discovery.restart()
        }
    }

    private suspend fun handle(event: SsdpEvent) {
        when (event) {
            is SsdpEvent.Alive -> {
                val existing = known.value[event.udn]
                if (existing != null && existing.device.location == event.location) {
                    known.update { map -> map + (event.udn to existing.copy(renderer = existing.renderer.copy(lastSeenAt = System.currentTimeMillis()))) }
                } else {
                    fetch(event.udn, event.location)
                }
            }
            is SsdpEvent.ByeBye -> {
                if (activeRenderer()?.udn != event.udn) known.update { it - event.udn }
            }
        }
    }

    private suspend fun fetch(udn: String, location: String) {
        if (!inFlight.add(udn)) return
        Log.i(TAG, "Fetching description for $udn at $location")
        try {
            val device = descriptions.fetch(location)
            val mimeTypes = device.connectionManager?.let { cm ->
                runCatching { ConnectionManagerService(soap, cm).getProtocolInfo().sink }.getOrNull()
            }.orEmpty()
            val renderer = DlnaRenderer(
                udn = device.udn,
                friendlyName = device.friendlyName,
                modelName = device.modelName,
                manufacturer = device.manufacturer,
                iconUrl = device.iconUrl,
                supportedMimeTypes = mimeTypes,
                lastSeenAt = System.currentTimeMillis(),
            )
            known.update { it + (udn to Known(device, renderer)) }
            failed.remove(udn)
            Log.i(TAG, "Renderer: ${device.friendlyName} (${device.manufacturer} ${device.modelName}) at $location, ${mimeTypes.size} sink formats")
            runCatching { transcodeProfileRepository.rememberRemoteDevice(renderer.target, connected = false) }
        } catch (t: Throwable) {
            failed[udn] = System.currentTimeMillis()
            Log.w(TAG, "Ignoring renderer at $location: ${t.message}")
            synchronized(rejected) { if (rejected.size >= 8) rejected.removeAt(0); rejected += "$location: ${t.message}" }
        } finally {
            inFlight.remove(udn)
        }
    }

    // ---- session ---------------------------------------------------------------------------------

    override fun connect(udn: String) {
        val entry = known.value[udn] ?: run {
            Log.w(TAG, "connect($udn): unknown renderer")
            return
        }
        scope.launch {
            disconnectInternal()
            _state.value = CastState(isAvailable = true, isConnecting = true, target = entry.renderer.target)
            try {
                val av = entry.device.avTransport ?: error("Renderer has no AVTransport service")
                val rc = entry.device.renderingControl?.let { RenderingControlService(soap, it) }
                val transport = RendererTransport(AvTransportService(soap, av), rc)
                val resolver = mediaResolver
                val newEngineScope = CoroutineScope(SupervisorJob(scope.coroutineContext.job) + engineDispatcher)
                engineScope = newEngineScope
                val engine = DlnaPlaybackEngine<MediaItem>(
                    transport = transport,
                    resolver = { item -> resolver?.resolve(item, entry.renderer) },
                    scope = newEngineScope,
                )
                val volume = runCatching { rc?.getVolume() }.getOrNull()
                engine.setInitialVolume(volume)
                val player = withContext(Dispatchers.Main) { DlnaPlayer(engine, entry.renderer) }
                _activePlayer.value = player
                _state.value = CastState(
                    isAvailable = true,
                    isConnected = true,
                    target = entry.renderer.target,
                    volume = (volume ?: 50) / 100f,
                )
                volumeJob?.cancel()
                volumeJob = scope.launch {
                    engine.state.map { it.volumePercent }.collect { v ->
                        _state.update { if (it.isConnected) it.copy(volume = v / 100f) else it }
                    }
                }
                runCatching { transcodeProfileRepository.rememberRemoteDevice(entry.renderer.target, connected = true) }
                withContext(Dispatchers.Main) { sessionListener?.onDlnaSessionAvailable(player, entry.renderer) }
            } catch (t: Throwable) {
                Log.w(TAG, "connect failed: ${t.message}")
                _state.value = CastState(isAvailable = true)
            }
        }
    }

    override fun disconnect() {
        scope.launch { disconnectInternal() }
    }

    private suspend fun disconnectInternal() {
        val player = _activePlayer.value ?: return
        _activePlayer.value = null
        volumeJob?.cancel()
        volumeJob = null
        withContext(Dispatchers.Main) {
            runCatching { sessionListener?.onDlnaSessionUnavailable() }
            runCatching { player.release() }
        }
        engineScope?.coroutineContext?.job?.cancel()
        engineScope = null
        _state.value = CastState(isAvailable = true)
    }

    override fun setVolume(volume: Float) {
        val player = _activePlayer.value ?: return
        val percent = (volume.coerceIn(0f, 1f) * 100).roundToInt()
        mainHandler.post { runCatching { player.setDeviceVolume(percent, 0) } }
    }

    private companion object {
        const val TAG = "DlnaManager"
        const val RETRY_FAILED_AFTER_MS = 30_000L
    }
}

/** Snapshot of UPnP discovery for the diagnostics export. */
data class DlnaDiagnostics(
    val ssdp: app.polyphony.core.dlna.ssdp.SsdpStats,
    val renderers: List<DlnaRenderer>,
    /** Devices that announced themselves but whose description could not be used (most recent last). */
    val rejected: List<String>,
)
