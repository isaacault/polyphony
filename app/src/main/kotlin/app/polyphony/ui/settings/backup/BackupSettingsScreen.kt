package app.polyphony.ui.settings.backup

import android.content.Context
import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.FileDownload
import androidx.compose.material.icons.rounded.FileUpload
import androidx.compose.material3.SnackbarHostState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import app.polyphony.R
import app.polyphony.core.data.backup.BackupManager
import app.polyphony.core.domain.repository.ServerRepository
import app.polyphony.navigation.LocalNavigator
import app.polyphony.ui.settings.components.ActionPreference
import app.polyphony.ui.settings.components.InfoBanner
import app.polyphony.ui.settings.components.SettingsScaffold
import app.polyphony.ui.settings.components.preferenceCategory
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import javax.inject.Inject

/** Default filename offered when creating the backup document. */
private const val DEFAULT_BACKUP_NAME = "polyphony-backup.json"
private const val BACKUP_MIME = "application/json"

sealed interface BackupMessage {
    data object Exported : BackupMessage
    data class ExportFailed(val reason: String?) : BackupMessage
    data class Restored(val serverCount: Int) : BackupMessage
    data class RestoreFailed(val reason: String?) : BackupMessage
}

@HiltViewModel
class BackupSettingsViewModel @Inject constructor(
    @ApplicationContext private val context: Context,
    private val backupManager: BackupManager,
    serverRepository: ServerRepository,
) : ViewModel() {

    private val _messages = Channel<BackupMessage>(Channel.BUFFERED)
    val messages: Flow<BackupMessage> = _messages.receiveAsFlow()

    /** Number of configured servers, shown so the user knows what the backup will contain. */
    val serverCount: StateFlow<Int> = serverRepository.servers
        .map { it.size }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), 0)

    /** Writes the backup JSON to the document the user picked with [ActivityResultContracts.CreateDocument]. */
    fun export(uri: Uri) {
        viewModelScope.launch {
            runCatching {
                val content = backupManager.export()
                withContext(Dispatchers.IO) {
                    context.contentResolver.openOutputStream(uri)?.use { it.write(content.encodeToByteArray()) }
                        ?: error("Could not open the chosen file for writing")
                }
            }
                .onSuccess { _messages.send(BackupMessage.Exported) }
                .onFailure { _messages.send(BackupMessage.ExportFailed(it.message)) }
        }
    }

    /** Reads and applies a backup from the document the user picked with [ActivityResultContracts.OpenDocument]. */
    fun import(uri: Uri) {
        viewModelScope.launch {
            runCatching {
                val text = withContext(Dispatchers.IO) {
                    context.contentResolver.openInputStream(uri)?.use { it.readBytes().decodeToString() }
                        ?: error("Could not open the chosen file for reading")
                }
                backupManager.restore(text)
            }
                .onSuccess { _messages.send(BackupMessage.Restored(it.serverCount)) }
                .onFailure { _messages.send(BackupMessage.RestoreFailed(it.message)) }
        }
    }
}

@Composable
fun BackupSettingsScreen(viewModel: BackupSettingsViewModel = hiltViewModel()) {
    val navigator = LocalNavigator.current
    val context = LocalContext.current
    val snackbar = remember { SnackbarHostState() }
    val serverCount by viewModel.serverCount.collectAsStateWithLifecycle()

    val exported = stringResource(R.string.settings_backup_exported)
    val exportFailed = stringResource(R.string.settings_backup_export_failed)
    val restoreFailed = stringResource(R.string.settings_backup_restore_failed)

    LaunchedEffect(Unit) {
        viewModel.messages.collect { message ->
            when (message) {
                BackupMessage.Exported -> snackbar.showSnackbar(exported)
                is BackupMessage.ExportFailed -> snackbar.showSnackbar(message.reason ?: exportFailed)
                is BackupMessage.Restored -> snackbar.showSnackbar(restoredMessage(context, message.serverCount))
                is BackupMessage.RestoreFailed -> snackbar.showSnackbar(message.reason ?: restoreFailed)
            }
        }
    }

    val exportLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.CreateDocument(BACKUP_MIME),
    ) { uri -> uri?.let(viewModel::export) }

    val importLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenDocument(),
    ) { uri -> uri?.let(viewModel::import) }

    SettingsScaffold(
        title = stringResource(R.string.settings_backup),
        onBack = { navigator.back() },
        snackbarHostState = snackbar,
    ) {
        item(key = "note") {
            InfoBanner(text = stringResource(R.string.settings_backup_note))
        }

        preferenceCategory(key = "actions") {
            ActionPreference(
                title = stringResource(R.string.settings_backup_export),
                summary = stringResource(R.string.settings_backup_export_summary, serverCount),
                icon = Icons.Rounded.FileDownload,
                showChevron = false,
                onClick = { exportLauncher.launch(DEFAULT_BACKUP_NAME) },
            )
            ActionPreference(
                title = stringResource(R.string.settings_backup_restore),
                summary = stringResource(R.string.settings_backup_restore_summary),
                icon = Icons.Rounded.FileUpload,
                showChevron = false,
                onClick = { importLauncher.launch(arrayOf(BACKUP_MIME, "application/octet-stream")) },
            )
        }
    }
}

private fun restoredMessage(context: Context, serverCount: Int): String =
    context.resources.getQuantityString(R.plurals.settings_backup_restored, serverCount, serverCount)
