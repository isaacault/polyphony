package app.polyphony.ui.settings.about

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.OpenInNew
import androidx.compose.material.icons.rounded.BugReport
import androidx.compose.material.icons.rounded.Code
import androidx.compose.material.icons.rounded.Gavel
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import app.polyphony.BuildConfig
import app.polyphony.R
import app.polyphony.navigation.LocalNavigator
import app.polyphony.ui.settings.components.PreferenceCategory
import app.polyphony.ui.settings.components.PreferenceRow
import app.polyphony.ui.settings.components.SettingsScaffold
import app.polyphony.ui.settings.components.preferenceCategory

const val SOURCE_URL = "https://github.com/isaacault/polyphony"
const val ISSUES_URL = "$SOURCE_URL/issues"
const val LICENSE_URL = "https://www.gnu.org/licenses/gpl-3.0.html"

private data class Acknowledgement(val name: String, val description: String, val url: String)

private val Acknowledgements = listOf(
    Acknowledgement("Media3 / ExoPlayer", "Playback engine, media session and Cast integration", "https://github.com/androidx/media"),
    Acknowledgement("Jellyfin Kotlin SDK", "Jellyfin API client", "https://github.com/jellyfin/jellyfin-sdk-kotlin"),
    Acknowledgement("OpenSubsonic", "The open Subsonic API specification", "https://opensubsonic.netlify.app"),
    Acknowledgement("Coil", "Image loading and caching", "https://coil-kt.github.io/coil/"),
    Acknowledgement("material-kolor", "Dynamic colour schemes from a seed colour", "https://github.com/jordond/MaterialKolor"),
    Acknowledgement("Reorderable", "Drag-to-reorder for Compose lists", "https://github.com/Calvin-LL/Reorderable"),
)

@Composable
fun AboutScreen() {
    val navigator = LocalNavigator.current
    val uriHandler = LocalUriHandler.current

    SettingsScaffold(title = stringResource(R.string.settings_about), onBack = { navigator.back() }) {
        item(key = "header") {
            Column(
                horizontalAlignment = Alignment.CenterHorizontally,
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(vertical = 16.dp),
            ) {
                Box(
                    modifier = Modifier
                        .size(96.dp)
                        .clip(CircleShape)
                        .background(MaterialTheme.colorScheme.primaryContainer),
                    contentAlignment = Alignment.Center,
                ) {
                    Icon(
                        painter = painterResource(R.drawable.ic_launcher_monochrome),
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.onPrimaryContainer,
                        modifier = Modifier.size(96.dp),
                    )
                }
                Spacer(Modifier.height(16.dp))
                Text(stringResource(R.string.app_name), style = MaterialTheme.typography.headlineMedium)
                Text(
                    text = stringResource(R.string.settings_about_version, BuildConfig.VERSION_NAME),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Spacer(Modifier.height(8.dp))
                Text(
                    text = stringResource(R.string.settings_about_tagline),
                    style = MaterialTheme.typography.bodyLarge,
                    textAlign = TextAlign.Center,
                )
                Spacer(Modifier.height(4.dp))
                Text(
                    text = stringResource(R.string.settings_about_license),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    textAlign = TextAlign.Center,
                )
            }
        }

        preferenceCategory(key = "links", titleRes = R.string.settings_about_links) {
            PreferenceRow(
                title = stringResource(R.string.settings_about_source),
                summary = SOURCE_URL.removePrefix("https://"),
                icon = Icons.Rounded.Code,
                onClick = { uriHandler.openUri(SOURCE_URL) },
                trailing = { Icon(Icons.AutoMirrored.Rounded.OpenInNew, contentDescription = null) },
            )
            PreferenceRow(
                title = stringResource(R.string.settings_about_issues),
                summary = stringResource(R.string.settings_about_issues_summary),
                icon = Icons.Rounded.BugReport,
                onClick = { uriHandler.openUri(ISSUES_URL) },
                trailing = { Icon(Icons.AutoMirrored.Rounded.OpenInNew, contentDescription = null) },
            )
            PreferenceRow(
                title = stringResource(R.string.settings_about_license_title),
                summary = "GNU General Public License v3.0",
                icon = Icons.Rounded.Gavel,
                onClick = { uriHandler.openUri(LICENSE_URL) },
                trailing = { Icon(Icons.AutoMirrored.Rounded.OpenInNew, contentDescription = null) },
            )
        }

        item(key = "acks") {
            PreferenceCategory(title = stringResource(R.string.settings_about_acknowledgements)) {
                Acknowledgements.forEachIndexed { index, ack ->
                    PreferenceRow(
                        title = ack.name,
                        summary = ack.description,
                        onClick = { uriHandler.openUri(ack.url) },
                    )
                    if (index < Acknowledgements.lastIndex) HorizontalDivider(Modifier.padding(horizontal = 16.dp))
                }
            }
        }
    }
}
