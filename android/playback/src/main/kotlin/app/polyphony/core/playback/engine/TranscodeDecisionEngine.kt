package app.polyphony.core.playback.engine

import app.polyphony.core.domain.playback.CastController
import app.polyphony.core.domain.playback.DlnaController
import app.polyphony.core.domain.repository.RemoteDevicePreference
import app.polyphony.core.domain.repository.SettingsRepository
import app.polyphony.core.domain.repository.TranscodeProfileRepository
import app.polyphony.core.domain.repository.NetworkMonitor
import app.polyphony.core.domain.transcode.NetworkState
import app.polyphony.core.domain.transcode.OutputTarget
import app.polyphony.core.domain.transcode.TranscodeContext
import app.polyphony.core.domain.transcode.TranscodeDecision
import app.polyphony.core.domain.transcode.TranscodePolicy
import app.polyphony.core.domain.transcode.TranscodeProfile
import app.polyphony.core.domain.transcode.TranscodeResolver
import app.polyphony.core.playback.di.PlaybackScope
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.launch
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Keeps every input of the transcoding decision hot in memory and exposes the decision for the
 * current output target as a [StateFlow]. [decisionFor] answers synchronously from the latest
 * inputs, which Media3 callbacks (data source resolution, Cast item conversion) require.
 */
@Singleton
class TranscodeDecisionEngine @Inject constructor(
    settingsRepository: SettingsRepository,
    transcodeProfileRepository: TranscodeProfileRepository,
    networkMonitor: NetworkMonitor,
    castController: CastController,
    dlnaController: DlnaController,
    private val latencyMonitor: LatencyMonitor,
    private val resolver: TranscodeResolver,
    @PlaybackScope scope: CoroutineScope,
) {
    data class Inputs(
        val policy: TranscodePolicy = TranscodePolicy(),
        val profiles: List<TranscodeProfile> = TranscodeProfile.BUILT_INS,
        val network: NetworkState = NetworkState.UNKNOWN,
        val target: OutputTarget = OutputTarget.Local,
        val remoteDevices: List<RemoteDevicePreference> = emptyList(),
        val latencyDegraded: Boolean = false,
        val revision: Int = 0,
    )

    private val refreshTick = MutableStateFlow(0)

    val inputs: StateFlow<Inputs> = combine(
        settingsRepository.settings.map { it.transcode }.distinctUntilChanged(),
        transcodeProfileRepository.profiles,
        networkMonitor.state,
        currentTargetFlow(castController, dlnaController),
        transcodeProfileRepository.remoteDevices,
        latencyMonitor.degraded,
        refreshTick,
    ) { values ->
        @Suppress("UNCHECKED_CAST")
        Inputs(
            policy = values[0] as TranscodePolicy,
            profiles = values[1] as List<TranscodeProfile>,
            network = values[2] as NetworkState,
            target = values[3] as OutputTarget,
            remoteDevices = values[4] as List<RemoteDevicePreference>,
            latencyDegraded = values[5] as Boolean,
            revision = values[6] as Int,
        )
    }.stateIn(scope, SharingStarted.Eagerly, Inputs())

    /** Decision for the *current* output target. */
    val decision: StateFlow<TranscodeDecision> = inputs
        .map { compute(it, it.target) }
        .distinctUntilChanged()
        .stateIn(scope, SharingStarted.Eagerly, compute(Inputs(), OutputTarget.Local))

    init {
        scope.launch(start = CoroutineStart.UNDISPATCHED) {
            inputs.map { it.policy.adaptiveLatency }.distinctUntilChanged().collect { latencyMonitor.settings = it }
        }
    }

    val policy: TranscodePolicy get() = inputs.value.policy
    val currentTarget: OutputTarget get() = inputs.value.target

    fun decisionFor(target: OutputTarget): TranscodeDecision = compute(inputs.value, target)

    fun localDecision(): TranscodeDecision = decisionFor(OutputTarget.Local)

    /** Synchronous profile lookup from the latest cached inputs (built-ins included). */
    fun profileById(id: String): TranscodeProfile? =
        inputs.value.profiles.firstOrNull { it.id == id } ?: TranscodeProfile.builtIn(id)

    /** Force re-evaluation (e.g. after the user edits rules) even if no input flow changed. */
    fun refresh() = refreshTick.update { it + 1 }

    private fun compute(inputs: Inputs, target: OutputTarget): TranscodeDecision {
        val override = if (target.isRemote) {
            inputs.remoteDevices.firstOrNull { it.targetKey == target.key }?.profileId
        } else null
        return resolver.resolve(
            policy = inputs.policy,
            profiles = inputs.profiles,
            context = TranscodeContext(
                network = inputs.network,
                target = target,
                latencyDegraded = inputs.latencyDegraded,
                deviceProfileId = override,
            ),
        )
    }
}

/** DLNA wins when a renderer session is active, then Cast, else local. */
private fun currentTargetFlow(cast: CastController, dlna: DlnaController): Flow<OutputTarget> =
    combine(cast.state, dlna.state) { c, d ->
        val dlnaTarget = d.target
        val castTarget = c.target
        when {
            dlnaTarget != null && d.isActiveSession -> dlnaTarget
            castTarget != null && c.isActiveSession -> castTarget
            else -> OutputTarget.Local
        }
    }.distinctUntilChanged()
