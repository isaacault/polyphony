package app.polyphony.diagnostics

import android.content.Context
import android.content.Intent
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import javax.inject.Inject

@HiltViewModel
class DiagnosticsViewModel @Inject constructor(
    @ApplicationContext private val context: Context,
    private val exporter: DiagnosticsExporter,
) : ViewModel() {
    data class State(val exporting: Boolean = false, val error: String? = null, val crashCount: Int = 0)

    private val _state = MutableStateFlow(State(crashCount = CrashRecorder.recent(context).size))
    val state: StateFlow<State> = _state

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
