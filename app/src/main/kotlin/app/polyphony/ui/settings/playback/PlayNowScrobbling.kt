package app.polyphony.ui.settings.playback

import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import app.polyphony.R
import app.polyphony.core.domain.model.ScrobbleLinks
import app.polyphony.core.domain.repository.RequestRepository
import app.polyphony.core.domain.repository.ServerRepository
import app.polyphony.ui.settings.components.ActionPreference
import app.polyphony.ui.settings.components.ConfirmDialog
import app.polyphony.ui.settings.components.PreferenceNote
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import javax.inject.Inject

/**
 * Where Play now songs are scrobbled (continuo): Navidrome scrobbles library songs to the
 * accounts linked in its own settings, but doesn't know Play now songs, so continuo does, to the
 * accounts linked here. ListenBrainz links with its user token; Last.fm through its web approval.
 */
@HiltViewModel
class PlayNowScrobblingViewModel @Inject constructor(
    private val servers: ServerRepository,
    private val requests: RequestRepository,
) : ViewModel() {
    private val _links = MutableStateFlow<ScrobbleLinks?>(null)
    /** Null off continuo, or from a continuo that can't scrobble Play now songs. */
    val links: StateFlow<ScrobbleLinks?> = _links.asStateFlow()
    private val _lastFmToken = MutableStateFlow<String?>(null)
    /** A Last.fm link waiting for the user's approval in the browser. */
    val lastFmPending: StateFlow<String?> = _lastFmToken.asStateFlow()
    private val _problem = MutableStateFlow<String?>(null)
    val problem: StateFlow<String?> = _problem.asStateFlow()

    init {
        run { requests.scrobbling(it) }
    }

    private fun run(block: suspend (app.polyphony.core.domain.model.Server) -> ScrobbleLinks?) {
        viewModelScope.launch {
            val server = servers.getActive()?.takeIf { requests.supports(it) } ?: return@launch
            try {
                _links.value = block(server) ?: _links.value
                _problem.value = null
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                _problem.value = e.message
            }
        }
    }

    fun linkListenBrainz(token: String) = run { requests.linkListenBrainz(it, token) }

    fun unlink(service: String) = run { requests.unlinkScrobbler(it, service) }

    /** Open Last.fm's approval page ([open]); the next tap on the row finishes. */
    fun startLastFm(open: (String) -> Unit) = run { server ->
        val (token, url) = requests.startLastFm(server)
        _lastFmToken.value = token
        open(url)
        null
    }

    fun finishLastFm() {
        val token = _lastFmToken.value ?: return
        run { server -> requests.finishLastFm(server, token).also { _lastFmToken.value = null } }
    }
}

/** The Play now rows of Settings, Scrobbling; nothing off continuo. */
@Composable
internal fun PlayNowScrobbling(viewModel: PlayNowScrobblingViewModel = hiltViewModel()) {
    val links by viewModel.links.collectAsStateWithLifecycle()
    val pending by viewModel.lastFmPending.collectAsStateWithLifecycle()
    val problem by viewModel.problem.collectAsStateWithLifecycle()
    val uri = LocalUriHandler.current
    var askToken by remember { mutableStateOf(false) }
    var unlinking by remember { mutableStateOf<String?>(null) }
    val l = links ?: return

    PreferenceNote(stringResource(R.string.scrobble_play_now_note))
    ActionPreference(
        title = stringResource(R.string.scrobble_listenbrainz),
        summary = l.listenBrainz.account?.takeIf { l.listenBrainz.connected }?.let { stringResource(R.string.scrobble_linked_as, it) }
            ?: stringResource(R.string.scrobble_listenbrainz_link),
        onClick = { if (l.listenBrainz.connected) unlinking = "listenbrainz" else askToken = true },
    )
    ActionPreference(
        title = stringResource(R.string.scrobble_lastfm),
        summary = when {
            !l.lastFm.available -> stringResource(R.string.scrobble_lastfm_unavailable)
            l.lastFm.connected -> stringResource(R.string.scrobble_linked_as, l.lastFm.account.orEmpty())
            pending != null -> stringResource(R.string.scrobble_lastfm_finish)
            else -> stringResource(R.string.scrobble_lastfm_link)
        },
        enabled = l.lastFm.available,
        onClick = {
            when {
                l.lastFm.connected -> unlinking = "lastfm"
                pending != null -> viewModel.finishLastFm()
                else -> viewModel.startLastFm { uri.openUri(it) }
            }
        },
    )
    problem?.let { PreferenceNote(it) }

    if (askToken) {
        var token by remember { mutableStateOf("") }
        AlertDialog(
            onDismissRequest = { askToken = false },
            title = { Text(stringResource(R.string.scrobble_listenbrainz)) },
            text = {
                OutlinedTextField(
                    value = token,
                    onValueChange = { token = it },
                    label = { Text(stringResource(R.string.scrobble_listenbrainz_token)) },
                    supportingText = { Text(stringResource(R.string.scrobble_listenbrainz_token_hint)) },
                    singleLine = true,
                    visualTransformation = PasswordVisualTransformation(),
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
                )
            },
            confirmButton = {
                TextButton(enabled = token.isNotBlank(), onClick = { askToken = false; viewModel.linkListenBrainz(token) }) {
                    Text(stringResource(R.string.scrobble_link))
                }
            },
            dismissButton = { TextButton(onClick = { askToken = false }) { Text(stringResource(R.string.settings_cancel)) } },
        )
    }
    unlinking?.let { service ->
        ConfirmDialog(
            title = stringResource(if (service == "lastfm") R.string.scrobble_lastfm else R.string.scrobble_listenbrainz),
            text = stringResource(R.string.scrobble_unlink_text),
            confirmLabel = stringResource(R.string.scrobble_unlink),
            onConfirm = { unlinking = null; viewModel.unlink(service) },
            onDismiss = { unlinking = null },
        )
    }
}
