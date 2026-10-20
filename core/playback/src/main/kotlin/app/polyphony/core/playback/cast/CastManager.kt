package app.polyphony.core.playback.cast

import android.util.Log

import android.content.Context
import android.os.Handler
import android.os.Looper
import androidx.mediarouter.media.MediaRouteSelector
import androidx.mediarouter.media.MediaRouter
import app.polyphony.core.domain.playback.CastController
import app.polyphony.core.domain.playback.CastState
import app.polyphony.core.domain.repository.TranscodeProfileRepository
import app.polyphony.core.domain.transcode.OutputTarget
import app.polyphony.core.playback.di.PlaybackScope
import com.google.android.gms.cast.CastDevice
import com.google.android.gms.cast.CastMediaControlIntent
import com.google.android.gms.cast.framework.CastContext
import com.google.android.gms.cast.framework.CastSession
import com.google.android.gms.cast.framework.CastState as GmsCastState
import com.google.android.gms.cast.framework.SessionManagerListener
import com.google.android.gms.common.ConnectionResult
import com.google.android.gms.common.GoogleApiAvailability
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import java.util.concurrent.Executors
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Owns the Google Cast session state. Devices are remembered in [TranscodeProfileRepository] as
 * soon as they are discovered so Settings can list them even when not connected.
 */
@Singleton
class CastManager @Inject constructor(
    @ApplicationContext private val context: Context,
    private val transcodeProfileRepository: TranscodeProfileRepository,
    @PlaybackScope private val scope: CoroutineScope,
) : CastController {

    private val _state = MutableStateFlow(CastState())
    override val state: StateFlow<CastState> = _state.asStateFlow()

    private val mainHandler = Handler(Looper.getMainLooper())
    private val initExecutor = Executors.newSingleThreadExecutor()
    private val ready = CompletableDeferred<CastContext?>()

    @Volatile
    var castContext: CastContext? = null
        private set

    @Volatile
    private var initialised = false

    /** Every MediaRouter route seen since start, for the diagnostics export (id -> description). */
    private val routesSeen = java.util.concurrent.ConcurrentHashMap<String, String>()
    @Volatile private var lastCastState: Int? = null

    fun diagnostics(): List<String> = buildList {
        add("supported=$isSupported initialised=$initialised context=${castContext != null} castState=${lastCastState ?: castContext?.castState}")
        if (routesSeen.isEmpty()) add("routes: none seen") else routesSeen.values.sorted().forEach { add("route: $it") }
    }

    /** True when Google Play services (and thus Cast) exist on this device. */
    val isSupported: Boolean by lazy {
        GoogleApiAvailability.getInstance().isGooglePlayServicesAvailable(context) == ConnectionResult.SUCCESS
    }

    /** Idempotent; safe from any thread. */
    fun initialize() {
        if (initialised) return
        initialised = true
        if (!isSupported) {
            ready.complete(null)
            return
        }
        mainHandler.post {
            runCatching {
                CastContext.getSharedInstance(context, initExecutor)
                    .addOnSuccessListener { ctx -> attach(ctx); ready.complete(ctx) }
                    .addOnFailureListener { ready.complete(null) }
            }.onFailure { ready.complete(null) }
        }
    }

    suspend fun awaitCastContext(): CastContext? {
        initialize()
        return ready.await()
    }

    private fun attach(ctx: CastContext) {
        castContext = ctx
        _state.update { it.copy(isAvailable = ctx.castState != GmsCastState.NO_DEVICES_AVAILABLE) }
        lastCastState = ctx.castState
        ctx.addCastStateListener { castState ->
            if (castState != lastCastState) Log.i(TAG, "Cast state: $castState (1=no devices, 2=not connected, 3=connecting, 4=connected)")
            lastCastState = castState
            _state.update { it.copy(isAvailable = castState != GmsCastState.NO_DEVICES_AVAILABLE) }
        }
        ctx.sessionManager.addSessionManagerListener(sessionListener, CastSession::class.java)
        ctx.sessionManager.currentCastSession?.let { session ->
            if (session.isConnected) onConnected(session)
        }
        runCatching {
            val selector = MediaRouteSelector.Builder()
                .addControlCategory(CastMediaControlIntent.categoryForCast(CastMediaControlIntent.DEFAULT_MEDIA_RECEIVER_APPLICATION_ID))
                .build()
            val router = MediaRouter.getInstance(context)
            router.addCallback(selector, routerCallback, MediaRouter.CALLBACK_FLAG_REQUEST_DISCOVERY)
            router.routes.forEach(::rememberRoute)
        }
    }

    /** Current target, falling back to the SDK's session when our state hasn't caught up yet. */
    fun currentTargetSync(): OutputTarget? =
        _state.value.target
            ?: castContext?.sessionManager?.currentCastSession?.castDevice?.toTarget()

    override fun disconnect() {
        mainHandler.post { castContext?.sessionManager?.endCurrentSession(true) }
    }

    override fun setVolume(volume: Float) {
        mainHandler.post {
            runCatching { castContext?.sessionManager?.currentCastSession?.volume = volume.toDouble().coerceIn(0.0, 1.0) }
            _state.update { it.copy(volume = volume.coerceIn(0f, 1f)) }
        }
    }

    private val sessionListener = object : SessionManagerListener<CastSession> {
        override fun onSessionStarting(session: CastSession) = onConnecting(session)
        override fun onSessionResuming(session: CastSession, sessionId: String) = onConnecting(session)
        override fun onSessionStarted(session: CastSession, sessionId: String) = onConnected(session)
        override fun onSessionResumed(session: CastSession, wasSuspended: Boolean) = onConnected(session)
        override fun onSessionStartFailed(session: CastSession, error: Int) {
            Log.w(TAG, "Cast session start failed: error=$error device=${session.castDevice?.friendlyName}")
            onDisconnected()
        }
        override fun onSessionResumeFailed(session: CastSession, error: Int) = onDisconnected()
        override fun onSessionEnding(session: CastSession) = Unit
        override fun onSessionEnded(session: CastSession, error: Int) = onDisconnected()
        override fun onSessionSuspended(session: CastSession, reason: Int) = onDisconnected()
    }

    private fun onConnecting(session: CastSession) {
        val target = session.castDevice?.toTarget()
        _state.update { it.copy(isConnecting = true, isConnected = false, target = target ?: it.target) }
    }

    private fun onConnected(session: CastSession) {
        val target = session.castDevice?.toTarget() ?: return
        Log.i(TAG, "Cast connected: ${target.friendlyName} model=${target.modelName} id=${target.deviceId}")
        val volume = runCatching { session.volume.toFloat() }.getOrDefault(1f)
        _state.update { it.copy(isConnecting = false, isConnected = true, target = target, volume = volume) }
        scope.launch { runCatching { transcodeProfileRepository.rememberRemoteDevice(target, connected = true) } }
    }

    private fun onDisconnected() {
        if (_state.value.isConnected) Log.i(TAG, "Cast disconnected from ${_state.value.target?.displayName}")
        _state.update { it.copy(isConnecting = false, isConnected = false, target = null) }
    }

    private val routerCallback = object : MediaRouter.Callback() {
        override fun onRouteAdded(router: MediaRouter, route: MediaRouter.RouteInfo) = rememberRoute(route)
        override fun onRouteChanged(router: MediaRouter, route: MediaRouter.RouteInfo) = rememberRoute(route)
        override fun onRouteRemoved(router: MediaRouter, route: MediaRouter.RouteInfo) {
            if (routesSeen.remove(route.id) != null) Log.i(TAG, "Route removed: ${route.name}")
        }
    }

    private fun rememberRoute(route: MediaRouter.RouteInfo) {
        val device = route.extras?.let { runCatching { CastDevice.getFromBundle(it) }.getOrNull() }
        val description = "${route.name} type=${route.deviceType} default=${route.isDefault} speaker=${route.isDeviceSpeaker} cast=${device != null}"
        if (routesSeen.put(route.id, description) == null) Log.i(TAG, "Route seen: $description")
        if (route.isDefault || route.isDeviceSpeaker) return
        if (device == null) return
        val target = device.toTarget()
        scope.launch { runCatching { transcodeProfileRepository.rememberRemoteDevice(target, connected = false) } }
    }
}

internal fun CastDevice.toTarget(): OutputTarget.Cast =
    OutputTarget.Cast(deviceId = deviceId, friendlyName = friendlyName, modelName = modelName)

private const val TAG = "PolyphonyCast"
