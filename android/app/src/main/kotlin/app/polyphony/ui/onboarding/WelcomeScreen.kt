package app.polyphony.ui.onboarding

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.systemBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Cast
import androidx.compose.material.icons.rounded.Dns
import androidx.compose.material.icons.rounded.GraphicEq
import androidx.compose.material3.Button
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import app.polyphony.R

@Composable
fun WelcomeScreen(onGetStarted: () -> Unit) {
    val scheme = MaterialTheme.colorScheme
    Box(
        Modifier
            .fillMaxSize()
            .background(
                Brush.verticalGradient(
                    0f to scheme.primaryContainer.copy(alpha = 0.55f),
                    0.55f to scheme.surface,
                    1f to scheme.surface,
                ),
            ),
    ) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .systemBarsPadding()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 24.dp, vertical = 24.dp),
        ) {
            Spacer(Modifier.weight(1f))
            PolyphonyLogo(size = 96.dp)
            Spacer(Modifier.height(24.dp))
            Text(
                text = stringResource(R.string.app_name),
                style = MaterialTheme.typography.displaySmall,
                fontWeight = FontWeight.Bold,
                color = MaterialTheme.colorScheme.onSurface,
            )
            Text(
                text = stringResource(R.string.onboarding_tagline),
                style = MaterialTheme.typography.titleLarge,
                color = MaterialTheme.colorScheme.primary,
                modifier = Modifier.padding(top = 4.dp),
            )
            Text(
                text = stringResource(R.string.onboarding_pitch),
                style = MaterialTheme.typography.bodyLarge,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(top = 16.dp),
            )
            Spacer(Modifier.height(32.dp))
            FeatureRow(Icons.Rounded.Dns, R.string.onboarding_feature_servers, R.string.onboarding_feature_servers_desc)
            FeatureRow(Icons.Rounded.Cast, R.string.onboarding_feature_cast, R.string.onboarding_feature_cast_desc)
            FeatureRow(Icons.Rounded.GraphicEq, R.string.onboarding_feature_quality, R.string.onboarding_feature_quality_desc)
            Spacer(Modifier.weight(1f))
            Spacer(Modifier.height(32.dp))
            Button(
                onClick = onGetStarted,
                modifier = Modifier
                    .fillMaxWidth()
                    .height(56.dp),
                shape = MaterialTheme.shapes.large,
            ) {
                Text(stringResource(R.string.onboarding_get_started), style = MaterialTheme.typography.titleMedium)
            }
        }
    }
}

/** Three rounded bars, like the launcher icon. */
@Composable
fun PolyphonyLogo(size: Dp, modifier: Modifier = Modifier) {
    val scheme = MaterialTheme.colorScheme
    val bar = size / 5
    Row(
        modifier = modifier.height(size),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(bar / 2),
    ) {
        Box(
            Modifier
                .width(bar)
                .height(size * 0.5f)
                .background(scheme.tertiary, RoundedCornerShape(bar / 2)),
        )
        Box(
            Modifier
                .width(bar * 1.2f)
                .height(size)
                .background(scheme.primary, RoundedCornerShape(bar / 2)),
        )
        Box(
            Modifier
                .width(bar)
                .height(size * 0.3f)
                .background(scheme.secondary, RoundedCornerShape(bar / 2)),
        )
    }
}

@Composable
private fun FeatureRow(icon: ImageVector, title: Int, description: Int) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        Surface(
            shape = MaterialTheme.shapes.medium,
            color = MaterialTheme.colorScheme.secondaryContainer,
            modifier = Modifier.size(48.dp),
        ) {
            Box(contentAlignment = Alignment.Center) {
                Icon(icon, contentDescription = null, tint = MaterialTheme.colorScheme.onSecondaryContainer)
            }
        }
        Column {
            Text(
                stringResource(title),
                style = MaterialTheme.typography.titleMedium,
                color = MaterialTheme.colorScheme.onSurface,
            )
            Text(
                stringResource(description),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

