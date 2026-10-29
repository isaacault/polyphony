package app.polyphony.ui.common

import androidx.compose.runtime.Composable
import androidx.compose.runtime.State
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import app.polyphony.core.domain.repository.RequestRepository
import app.polyphony.core.domain.repository.ServerRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import javax.inject.Inject

/** Whether the active server is continuo-linked, so acquisition entry points can show (#50). */
@HiltViewModel
class ContinuoLinkViewModel @Inject constructor(
    servers: ServerRepository,
    requests: RequestRepository,
) : ViewModel() {
    val linked: StateFlow<Boolean> = servers.activeServer
        .map { requests.supports(it) }
        .distinctUntilChanged()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), false)
}

@Composable
fun rememberContinuoLinked(): State<Boolean> {
    val vm: ContinuoLinkViewModel = hiltViewModel()
    return vm.linked.collectAsStateWithLifecycle()
}
