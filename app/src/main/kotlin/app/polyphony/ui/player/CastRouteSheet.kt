package app.polyphony.ui.player

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Cast
import androidx.compose.material.icons.rounded.CastConnected
import androidx.compose.material.icons.rounded.Check
import androidx.compose.material.icons.rounded.Speaker
import androidx.compose.material.icons.rounded.SpeakerGroup
import androidx.compose.material.icons.rounded.SurroundSound
import androidx.compose.material.icons.rounded.Tv
import androidx.compose.material.icons.rounded.VolumeDown
import androidx.compose.material.icons.rounded.VolumeUp
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.ListItem
import androidx.compose.material3.ListItemDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Slider
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.mediarouter.media.MediaRouteSelector
import androidx.mediarouter.media.MediaRouter
import app.polyphony.R
import app.polyphony.core.domain.playback.CastState
import app.polyphony.core.domain.playback.DlnaRenderer
import com.google.android.gms.cast.CastMediaControlIntent
import com.google.android.gms.cast.framework.CastContext

/** Cast icon that reflects the session state (Cast or DLNA). */
@Composable
fun CastIconButton(castState: CastState, onClick: () -> Unit, modifier: Modifier = Modifier) {
    IconButton(onClick = onClick, modifier = modifier) {
        Icon(
            imageVector = if (castState.isConnected) Icons.Rounded.CastConnected else Icons.Rounded.Cast,
            contentDescription = stringResource(R.string.player_cast),
            tint = if (castState.isActiveSession) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

/**
 * Output picker: Google Cast routes (via MediaRouter, themed and FragmentActivity-free) plus
 * UPnP/DLNA renderers discovered by the built-in control point, and a volume slider for whichever
 * remote output is connected.
 */
@Composable
fun CastRouteSheet(
    castState: CastState,
    onDisconnect: () -> Unit,
    onDismiss: () -> Unit,
) {
    val context = LocalContext.current
    val vm: RemoteOutputViewModel = hiltViewModel()
    val dlnaState by vm.dlna.collectAsStateWithLifecycle()
    val renderers by vm.renderers.collectAsStateWithLifecycle()
    val routes = remember { mutableStateListOf<MediaRouter.RouteInfo>() }
    val available = remember { mutableStateListOf(true) }

    DisposableEffect(Unit) {
        vm.startDiscovery()
        onDispose { vm.stopDiscovery() }
    }

    DisposableEffect(Unit) {
        val router = MediaRouter.getInstance(context)
        val selector = runCatching { CastContext.getSharedInstance(context).mergedSelector }
            .onFailure { available[0] = false }
            .getOrNull()
            ?: MediaRouteSelector.Builder()
                .addControlCategory(CastMediaControlIntent.categoryForCast(CastMediaControlIntent.DEFAULT_MEDIA_RECEIVER_APPLICATION_ID))
                .build()

        fun refresh() {
            val fresh = router.routes.filter { !it.isDefault && it.matchesSelector(selector) }
            routes.clear()
            routes.addAll(fresh)
        }

        val callback = object : MediaRouter.Callback() {
            override fun onRouteAdded(router: MediaRouter, route: MediaRouter.RouteInfo) = refresh()
            override fun onRouteRemoved(router: MediaRouter, route: MediaRouter.RouteInfo) = refresh()
            override fun onRouteChanged(router: MediaRouter, route: MediaRouter.RouteInfo) = refresh()
            override fun onRouteSelected(router: MediaRouter, route: MediaRouter.RouteInfo, reason: Int) = refresh()
            override fun onRouteUnselected(router: MediaRouter, route: MediaRouter.RouteInfo, reason: Int) = refresh()
        }
        router.addCallback(
            selector,
            callback,
            MediaRouter.CALLBACK_FLAG_REQUEST_DISCOVERY or MediaRouter.CALLBACK_FLAG_PERFORM_ACTIVE_SCAN,
        )
        refresh()
        onDispose { router.removeCallback(callback) }
    }

    val connectedRemote: CastState? = when {
        dlnaState.isActiveSession -> dlnaState
        castState.isActiveSession -> castState
        else -> null
    }

    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = rememberModalBottomSheetState()) {
        val hint by vm.networkHint.collectAsStateWithLifecycle()
        LazyColumn(Modifier.navigationBarsPadding().padding(bottom = 16.dp)) {
            hint?.let { h ->
                item(key = "network-hint") {
                    NetworkHintRow(
                        when (h) {
                            RemoteOutputViewModel.NetworkHint.NOT_ON_WIFI -> stringResource(R.string.output_hint_not_on_wifi)
                            RemoteOutputViewModel.NetworkHint.VPN_ACTIVE -> stringResource(R.string.output_hint_vpn)
                        },
                    )
                }
            }
            item(key = "title") {
                Text(
                    text = stringResource(R.string.cast_title),
                    style = MaterialTheme.typography.titleLarge,
                    modifier = Modifier.padding(horizontal = 24.dp, vertical = 8.dp),
                )
            }
            val target = connectedRemote?.target
            if (connectedRemote != null && target != null) {
                item(key = "connected") {
                    val isDlna = connectedRemote === dlnaState
                    ListItem(
                        headlineContent = { Text(target.displayName) },
                        supportingContent = {
                            Text(
                                if (connectedRemote.isConnected) stringResource(R.string.cast_connected_to, target.displayName)
                                else stringResource(R.string.cast_connecting),
                            )
                        },
                        leadingContent = {
                            Icon(
                                if (isDlna) Icons.Rounded.SurroundSound else Icons.Rounded.CastConnected,
                                null,
                                tint = MaterialTheme.colorScheme.primary,
                            )
                        },
                        trailingContent = {
                            TextButton(onClick = {
                                if (isDlna) vm.disconnectDlna() else onDisconnect()
                                onDismiss()
                            }) { Text(stringResource(R.string.player_disconnect)) }
                        },
                        colors = ListItemDefaults.colors(containerColor = Color.Transparent),
                    )
                }
                if (connectedRemote.isConnected) {
                    item(key = "volume") {
                        RemoteVolumeRow(initial = connectedRemote.volume, onChange = vm::setRemoteVolume)
                    }
                }
            }

            // ---- Google Cast ----
            item(key = "cast-header") { SectionLabel(stringResource(R.string.cast_section_title)) }
            when {
                !available[0] -> item(key = "cast-unavailable") {
                    Text(
                        text = stringResource(R.string.cast_unavailable),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(horizontal = 24.dp, vertical = 12.dp),
                    )
                }
                routes.isEmpty() -> item(key = "cast-searching") {
                    SearchingRow(stringResource(R.string.cast_searching))
                }
                else -> items(routes, key = { "route-" + it.id }) { route ->
                    val selected = route.isSelected
                    val connecting = route.connectionState == MediaRouter.RouteInfo.CONNECTION_STATE_CONNECTING
                    ListItem(
                        headlineContent = { Text(route.name) },
                        supportingContent = route.description?.takeIf { it.isNotBlank() }?.let { { Text(it) } },
                        leadingContent = {
                            Icon(
                                imageVector = when (route.deviceType) {
                                    MediaRouter.RouteInfo.DEVICE_TYPE_TV -> Icons.Rounded.Tv
                                    MediaRouter.RouteInfo.DEVICE_TYPE_SPEAKER -> Icons.Rounded.Speaker
                                    MediaRouter.RouteInfo.DEVICE_TYPE_GROUP -> Icons.Rounded.SpeakerGroup
                                    else -> Icons.Rounded.Cast
                                },
                                contentDescription = null,
                                tint = if (selected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        },
                        trailingContent = {
                            when {
                                connecting -> CircularProgressIndicator(Modifier.size(20.dp), strokeWidth = 2.dp)
                                selected -> Icon(Icons.Rounded.Check, null, tint = MaterialTheme.colorScheme.primary)
                            }
                        },
                        colors = ListItemDefaults.colors(containerColor = Color.Transparent),
                        modifier = Modifier.clickable(enabled = !selected) {
                            if (dlnaState.isActiveSession) vm.disconnectDlna()
                            route.select()
                            onDismiss()
                        },
                    )
                }
            }

            // ---- UPnP / DLNA ----
            item(key = "dlna-header") { SectionLabel(stringResource(R.string.dlna_section_title)) }
            if (renderers.isEmpty()) {
                item(key = "dlna-searching") { SearchingRow(stringResource(R.string.dlna_searching)) }
            } else {
                items(renderers, key = { "dlna-" + it.udn }) { renderer ->
                    DlnaRendererRow(
                        renderer = renderer,
                        state = dlnaState,
                        onClick = {
                            val isThis = dlnaState.target?.key == renderer.target.key
                            if (isThis && dlnaState.isActiveSession) {
                                vm.disconnectDlna()
                            } else {
                                vm.connectDlna(renderer.udn)
                            }
                            onDismiss()
                        },
                    )
                }
            }
            item(key = "hint") {
                Text(
                    text = stringResource(R.string.dlna_hint),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(horizontal = 24.dp, vertical = 8.dp),
                )
            }
        }
    }
}

@Composable
private fun SectionLabel(text: String) {
    Text(
        text = text,
        style = MaterialTheme.typography.labelLarge,
        color = MaterialTheme.colorScheme.primary,
        modifier = Modifier.padding(start = 24.dp, end = 24.dp, top = 12.dp, bottom = 4.dp),
    )
}

@Composable
private fun SearchingRow(text: String) {
    Column(Modifier.padding(horizontal = 24.dp, vertical = 12.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        LinearProgressIndicator(Modifier.fillMaxWidth().height(2.dp))
        Text(text, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

@Composable
private fun DlnaRendererRow(renderer: DlnaRenderer, state: CastState, onClick: () -> Unit) {
    val isThis = state.target?.key == renderer.target.key
    val connected = isThis && state.isConnected
    val connecting = isThis && state.isConnecting && !state.isConnected
    ListItem(
        headlineContent = { Text(renderer.friendlyName) },
        supportingContent = {
            val parts = listOfNotNull(
                renderer.modelName?.takeIf { it.isNotBlank() },
                when {
                    connected -> stringResource(R.string.dlna_connected)
                    connecting -> stringResource(R.string.dlna_connecting)
                    else -> null
                },
            )
            if (parts.isNotEmpty()) Text(parts.joinToString(" · "))
        },
        leadingContent = {
            Icon(
                imageVector = rendererIcon(renderer),
                contentDescription = null,
                tint = if (connected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
            )
        },
        trailingContent = {
            when {
                connecting -> CircularProgressIndicator(Modifier.size(20.dp), strokeWidth = 2.dp)
                connected -> Icon(Icons.Rounded.Check, null, tint = MaterialTheme.colorScheme.primary)
            }
        },
        colors = ListItemDefaults.colors(containerColor = Color.Transparent),
        modifier = Modifier.clickable(onClick = onClick),
    )
}

private fun rendererIcon(renderer: DlnaRenderer) = run {
    val haystack = listOfNotNull(renderer.modelName, renderer.manufacturer, renderer.friendlyName).joinToString(" ").lowercase()
    when {
        listOf("tv", "bravia", "shield", "display").any { it in haystack } -> Icons.Rounded.Tv
        listOf("speaker", "sonos", "heos", "soundbar", "audio").any { it in haystack } -> Icons.Rounded.Speaker
        else -> Icons.Rounded.SurroundSound
    }
}

@Composable
private fun RemoteVolumeRow(initial: Float, onChange: (Float) -> Unit) {
    var local by remember(initial) { mutableFloatStateOf(initial.coerceIn(0f, 1f)) }
    Row(
        Modifier
            .fillMaxWidth()
            .padding(horizontal = 24.dp, vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(Icons.Rounded.VolumeDown, contentDescription = stringResource(R.string.dlna_volume), tint = MaterialTheme.colorScheme.onSurfaceVariant)
        Spacer(Modifier.width(8.dp))
        Slider(
            value = local,
            onValueChange = { local = it },
            onValueChangeFinished = { onChange(local) },
            modifier = Modifier.weight(1f),
        )
        Spacer(Modifier.width(8.dp))
        Icon(Icons.Rounded.VolumeUp, contentDescription = null, tint = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

@Composable
private fun NetworkHintRow(text: String) {
    androidx.compose.material3.Surface(
        color = MaterialTheme.colorScheme.tertiaryContainer,
        contentColor = MaterialTheme.colorScheme.onTertiaryContainer,
        shape = MaterialTheme.shapes.medium,
        modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp),
    ) {
        Text(text, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.padding(12.dp))
    }
}
