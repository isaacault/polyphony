package app.polyphony.core.ui.components

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.IconButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import app.polyphony.core.ui.color.rememberArtworkColors
import app.polyphony.core.ui.theme.ArtworkShapes
import app.polyphony.core.ui.theme.Dimens
import app.polyphony.core.ui.theme.LocalIsDarkTheme

/**
 * Detail-screen frame: the content (a `LazyColumn` whose first item is a [HeroHeader]) scrolls
 * under a transparent top bar; once the hero scrolls away the bar becomes opaque and shows [title].
 */
@Composable
fun HeroScaffold(
    title: String,
    listState: LazyListState,
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
    actions: @Composable RowScope.() -> Unit = {},
    floatingActionButton: @Composable () -> Unit = {},
    content: @Composable (PaddingValues) -> Unit,
) {
    val collapsed by remember(listState) {
        derivedStateOf { listState.firstVisibleItemIndex > 0 || listState.firstVisibleItemScrollOffset > 600 }
    }
    val barAlpha by animateFloatAsState(if (collapsed) 1f else 0f, tween(200), label = "barAlpha")
    val surface = MaterialTheme.colorScheme.surface
    Box(modifier.fillMaxSize()) {
        content(PaddingValues(bottom = LocalContentBottomPadding.current))
        TopAppBar(
            title = {
                Text(
                    title,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.alpha(barAlpha),
                )
            },
            navigationIcon = {
                OverlayIconButton(onClick = onBack, scrimAlpha = 1f - barAlpha) {
                    Icon(Icons.AutoMirrored.Rounded.ArrowBack, contentDescription = "Back")
                }
            },
            actions = actions,
            colors = TopAppBarDefaults.topAppBarColors(
                containerColor = surface.copy(alpha = barAlpha),
                scrolledContainerColor = surface.copy(alpha = barAlpha),
            ),
            modifier = Modifier.align(Alignment.TopCenter),
        )
        Box(Modifier.align(Alignment.BottomEnd).padding(16.dp).padding(bottom = LocalContentBottomPadding.current)) {
            floatingActionButton()
        }
    }
}

/** Icon button with a translucent tonal disc so it stays legible over artwork. */
@Composable
fun OverlayIconButton(
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    scrimAlpha: Float = 1f,
    content: @Composable () -> Unit,
) {
    IconButton(
        onClick = onClick,
        modifier = modifier,
        colors = IconButtonDefaults.iconButtonColors(
            containerColor = MaterialTheme.colorScheme.surface.copy(alpha = 0.55f * scrimAlpha),
            contentColor = MaterialTheme.colorScheme.onSurface,
        ),
        content = content,
    )
}

/**
 * Large centred artwork over a gradient from the artwork's colours into the surface, followed by
 * the title, an optional subtitle slot (e.g. tappable artist) and a meta line.
 */
@Composable
fun HeroHeader(
    artworkUrl: String?,
    title: String,
    modifier: Modifier = Modifier,
    meta: String? = null,
    artworkShape: Shape = ArtworkShapes.hero,
    artwork: (@Composable (Modifier) -> Unit)? = null,
    subtitle: (@Composable ColumnScope.() -> Unit)? = null,
    belowTitle: (@Composable ColumnScope.() -> Unit)? = null,
) {
    val colors by rememberArtworkColors(artworkUrl)
    val dark = LocalIsDarkTheme.current
    val surface = MaterialTheme.colorScheme.surface
    val fallback = MaterialTheme.colorScheme.surfaceContainerHigh
    val target = colors?.let { if (dark) it.darkMuted else it.muted } ?: fallback
    val top by animateColorAsState(target, tween(500), label = "heroTop")

    BoxWithConstraints(modifier.fillMaxWidth()) {
        val artSize = minOf(maxWidth - Dimens.screenPadding * 4, Dimens.heroArtworkMax)
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .background(
                    Brush.verticalGradient(
                        0f to top.copy(alpha = 0.95f),
                        0.55f to top.copy(alpha = 0.45f),
                        1f to surface,
                    ),
                ),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Spacer(Modifier.statusBarsPadding().height(64.dp))
            val artModifier = Modifier
                .size(artSize)
                .shadow(elevation = 18.dp, shape = artworkShape, ambientColor = Color.Black.copy(alpha = 0.35f), spotColor = Color.Black.copy(alpha = 0.35f))
            if (artwork != null) {
                artwork(artModifier)
            } else {
                ArtworkImage(url = artworkUrl, contentDescription = title, modifier = artModifier, shape = artworkShape)
            }
            Spacer(Modifier.height(20.dp))
            Text(
                text = title,
                style = MaterialTheme.typography.headlineMedium,
                textAlign = TextAlign.Center,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.padding(horizontal = 24.dp),
            )
            if (subtitle != null) {
                Spacer(Modifier.height(4.dp))
                subtitle()
            }
            if (meta != null) {
                Spacer(Modifier.height(4.dp))
                Text(
                    text = meta,
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    textAlign = TextAlign.Center,
                    modifier = Modifier.padding(horizontal = 24.dp),
                )
            }
            if (belowTitle != null) {
                Spacer(Modifier.height(12.dp))
                belowTitle()
            }
            Spacer(Modifier.height(8.dp))
        }
    }
}
