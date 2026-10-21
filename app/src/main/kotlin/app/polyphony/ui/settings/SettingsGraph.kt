package app.polyphony.ui.settings

import androidx.navigation.NavGraphBuilder
import androidx.navigation.compose.composable
import app.polyphony.navigation.AboutRoute
import app.polyphony.navigation.AppearanceSettingsRoute
import app.polyphony.navigation.BackupSettingsRoute
import app.polyphony.navigation.DownloadRuleEditRoute
import app.polyphony.navigation.DownloadsSettingsRoute
import app.polyphony.navigation.LibrarySettingsRoute
import app.polyphony.navigation.PlaybackSettingsRoute
import app.polyphony.navigation.PolyphonyNavigator
import app.polyphony.navigation.RemoteDevicesSettingsRoute
import app.polyphony.navigation.ServerEditRoute
import app.polyphony.navigation.ServersSettingsRoute
import app.polyphony.navigation.SettingsRoute
import app.polyphony.navigation.StreamingSettingsRoute
import app.polyphony.navigation.TranscodeProfileEditRoute
import app.polyphony.ui.settings.about.AboutScreen
import app.polyphony.ui.settings.appearance.AppearanceSettingsScreen
import app.polyphony.ui.settings.backup.BackupSettingsScreen
import app.polyphony.ui.settings.downloads.DownloadsSettingsScreen
import app.polyphony.ui.downloads.DownloadRuleEditScreen
import app.polyphony.ui.settings.library.LibrarySettingsScreen
import app.polyphony.ui.settings.playback.PlaybackSettingsScreen
import app.polyphony.ui.settings.servers.ServerEditScreen
import app.polyphony.ui.settings.servers.ServersSettingsScreen
import app.polyphony.ui.settings.streaming.RemoteDevicesSettingsScreen
import app.polyphony.ui.settings.streaming.StreamingSettingsScreen
import app.polyphony.ui.settings.streaming.TranscodeProfileEditScreen

/** Settings destinations. Screens obtain navigation through [app.polyphony.navigation.LocalNavigator]. */
@Suppress("UNUSED_PARAMETER")
fun NavGraphBuilder.settingsGraph(navigator: PolyphonyNavigator) {
    composable<SettingsRoute> { SettingsScreen() }
    composable<ServersSettingsRoute> { ServersSettingsScreen() }
    composable<ServerEditRoute> { ServerEditScreen() }
    composable<AppearanceSettingsRoute> { AppearanceSettingsScreen() }
    composable<PlaybackSettingsRoute> { PlaybackSettingsScreen() }
    composable<StreamingSettingsRoute> { StreamingSettingsScreen() }
    composable<TranscodeProfileEditRoute> { TranscodeProfileEditScreen() }
    composable<RemoteDevicesSettingsRoute> { RemoteDevicesSettingsScreen() }
    composable<LibrarySettingsRoute> { LibrarySettingsScreen() }
    composable<DownloadsSettingsRoute> { DownloadsSettingsScreen() }
    composable<DownloadRuleEditRoute> { DownloadRuleEditScreen() }
    composable<BackupSettingsRoute> { BackupSettingsScreen() }
    composable<AboutRoute> { AboutScreen() }
}
