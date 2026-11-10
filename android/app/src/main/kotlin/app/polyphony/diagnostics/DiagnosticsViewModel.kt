package app.polyphony.diagnostics

import android.content.Context
import android.content.Intent
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import app.polyphony.core.domain.connection.ConnectionSummary
import app.polyphony.core.domain.repository.ConnectionSampleRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.collections.immutable.ImmutableList
import kotlinx.collections.immutable.persistentListOf
import kotlinx.collections.immutable.toImmutableList
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import javax.inject.Inject

@HiltViewModel
class DiagnosticsViewModel @Inject constructor(
    @ApplicationContext private val context: Context,
    private val exporter: DiagnosticsExporter,
    private val connectionSamples: ConnectionSampleRepository,
) : ViewModel() {
    data class State(
        val exporting: Boolean = false,
        val error: String? = null,
        val crashCount: Int = 0,
        /** How each kind of network has done while streaming (#185); empty until something was measured. */
        val connection: ImmutableList<ConnectionSummary.Row> = persistentListOf(),
    )

    private val _state = MutableStateFlow(State(crashCount = CrashRecorder.recent(context).size))
    val state: StateFlow<State> = _state

    init {
        viewModelScope.launch {
            val rows = runCatching { ConnectionSummary.of(connectionSamples.all()) }.getOrDefault(emptyList())
            _state.update { it.copy(connection = rows.toImmutableList()) }
        }
    }

    /** Builds the report and launches the system share sheet. */
    fun export(launch: (Intent) -> Unit) {
        if (_state.value.exporting) return
        _state.update { it.copy(exporting = true, error = null) }
        viewModelScope.launch {
            runCatching { exporter.export() }
                .onSuccess { uri -> launch(exporter.shareIntent(uri)) }
                .onFailure { e -> _state.update { it.copy(error = e.message ?: e.javaClass.simpleName) } }
            _state.update { it.copy(exporting = false) }
        }
    }

    fun clearCrashes() {
        CrashRecorder.recent(context).forEach { it.delete() }
        _state.update { it.copy(crashCount = 0) }
    }
}
