package app.polyphony.ui.settings.streaming

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Cable
import androidx.compose.material.icons.rounded.Cast
import androidx.compose.material.icons.rounded.CloudOff
import androidx.compose.material.icons.rounded.Public
import androidx.compose.material.icons.rounded.SignalCellularAlt
import androidx.compose.material.icons.rounded.Smartphone
import androidx.compose.material.icons.rounded.Speaker
import androidx.compose.material.icons.rounded.SurroundSound
import androidx.compose.material.icons.rounded.Tv
import androidx.compose.material.icons.rounded.Wifi
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.stringResource
import app.polyphony.R
import app.polyphony.core.domain.repository.SettingsRepository
import app.polyphony.core.domain.repository.TranscodeProfileRepository
import app.polyphony.core.domain.transcode.AdaptiveLatencySettings
import app.polyphony.core.domain.transcode.NetworkKind
import app.polyphony.core.domain.transcode.NetworkState
import app.polyphony.core.domain.transcode.OutputTarget
import app.polyphony.core.domain.transcode.TranscodeProfile
import kotlinx.coroutines.flow.first

/** Presets for the adaptive-latency sensitivity control. */
enum class LatencySensitivity(val stallThreshold: Int, val windowSeconds: Int, val longStallMs: Long) {
    LOW(5, 180, 6_000),
    MEDIUM(3, 120, 4_000),
    HIGH(2, 90, 2_500);

    companion object {
        fun matching(settings: AdaptiveLatencySettings): LatencySensitivity? = entries.firstOrNull {
            it.stallThreshold == settings.stallThreshold &&
                it.windowSeconds == settings.windowSeconds &&
                it.longStallMs == settings.longStallMs
        }
    }
}

/** Remove every reference to [profileId] from the policy and device pins before deleting it. */
suspend fun clearProfileReferences(
    profileId: String,
    settingsRepository: SettingsRepository,
    profileRepository: TranscodeProfileRepository,
) {
    settingsRepository.update { settings ->
        val t = settings.transcode
        settings.copy(
            transcode = t.copy(
                defaultProfileId = if (t.defaultProfileId == profileId) TranscodeProfile.ORIGINAL_ID else t.defaultProfileId,
                wifiProfileId = t.wifiProfileId.takeUnless { it == profileId },
                cellularProfileId = t.cellularProfileId.takeUnless { it == profileId },
                castDefaultProfileId = t.castDefaultProfileId.takeUnless { it == profileId },
                adaptiveLatency = t.adaptiveLatency.copy(
                    highLatencyProfileId = t.adaptiveLatency.highLatencyProfileId.takeUnless { it == profileId },
                ),
            ),
        )
    }
    profileRepository.remoteDevices.first()
        .filter { it.profileId == profileId }
        .forEach { profileRepository.setRemoteDeviceProfile(it.targetKey, null) }
}

fun networkIcon(network: NetworkState): ImageVector = when (network.kind) {
    NetworkKind.WIFI -> Icons.Rounded.Wifi
    NetworkKind.ETHERNET -> Icons.Rounded.Cable
    NetworkKind.CELLULAR -> Icons.Rounded.SignalCellularAlt
    NetworkKind.OTHER -> Icons.Rounded.Public
    NetworkKind.NONE -> Icons.Rounded.CloudOff
}

@Composable
fun networkLabel(network: NetworkState): String {
    val base = when (network.kind) {
        NetworkKind.WIFI -> stringResource(R.string.settings_network_wifi)
        NetworkKind.ETHERNET -> stringResource(R.string.settings_network_ethernet)
        NetworkKind.CELLULAR -> stringResource(R.string.settings_network_cellular)
        NetworkKind.OTHER -> stringResource(R.string.settings_network_other)
        NetworkKind.NONE -> stringResource(R.string.settings_network_offline)
    }
    val extras = buildList {
        if (network.isMetered && network.kind != NetworkKind.CELLULAR && network.kind != NetworkKind.NONE) {
            add(stringResource(R.string.settings_network_metered))
        }
        if (network.isVpn) add(stringResource(R.string.settings_network_vpn))
    }
    return if (extras.isEmpty()) base else "$base · ${extras.joinToString(" · ")}"
}

fun outputIcon(target: OutputTarget): ImageVector = when (target) {
    OutputTarget.Local -> Icons.Rounded.Smartphone
    is OutputTarget.Cast -> deviceIcon(target.modelName, target.friendlyName)
    is OutputTarget.Dlna -> Icons.Rounded.SurroundSound
}

/** True for remembered devices that are UPnP/DLNA renderers (`dlna:<udn>` keys). */
fun isDlnaKey(targetKey: String): Boolean = targetKey.startsWith("dlna:")

/** Icon for a remembered remote device, distinguishing DLNA renderers from Cast receivers. */
fun remoteDeviceIcon(targetKey: String, modelName: String?, friendlyName: String?): ImageVector =
    if (isDlnaKey(targetKey)) Icons.Rounded.SurroundSound else deviceIcon(modelName, friendlyName)

/** Best-effort glyph for a remote device from its model / friendly name. */
fun deviceIcon(modelName: String?, friendlyName: String?): ImageVector {
    val haystack = listOfNotNull(modelName, friendlyName).joinToString(" ").lowercase()
    return when {
        listOf("tv", "chromecast", "shield", "display", "hub").any { it in haystack } -> Icons.Rounded.Tv
        listOf("nest", "home", "speaker", "audio", "mini", "max", "soundbar", "sonos").any { it in haystack } -> Icons.Rounded.Speaker
        else -> Icons.Rounded.Cast
    }
}

/** Short label like "Opus · ≤128 kbps" for list trailing text. */
fun TranscodeProfile.shortLabel(): String = name
