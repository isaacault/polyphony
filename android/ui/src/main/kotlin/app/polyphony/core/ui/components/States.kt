package app.polyphony.core.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.ErrorOutline
import androidx.compose.material.icons.rounded.LibraryMusic
import androidx.compose.material3.Button
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import app.polyphony.core.ui.theme.ArtworkShapes
import app.polyphony.core.ui.theme.Dimens

@Composable
fun EmptyState(
    title: String,
    modifier: Modifier = Modifier,
    icon: ImageVector = Icons.Rounded.LibraryMusic,
    body: String? = null,
    actionLabel: String? = null,
    onAction: (() -> Unit)? = null,
    secondaryActionLabel: String? = null,
    onSecondaryAction: (() -> Unit)? = null,
) {
    Column(
        modifier = modifier
            .fillMaxWidth()
            .padding(horizontal = 32.dp, vertical = 48.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Box(
            Modifier
                .size(88.dp)
                .clip(MaterialTheme.shapes.extraLarge)
                .background(MaterialTheme.colorScheme.surfaceContainerHigh),
            contentAlignment = Alignment.Center,
        ) {
            Icon(icon, contentDescription = null, modifier = Modifier.size(40.dp), tint = MaterialTheme.colorScheme.primary)
        }
        Text(title, style = MaterialTheme.typography.titleLarge, textAlign = TextAlign.Center)
        if (body != null) {
            Text(
                body,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                textAlign = TextAlign.Center,
            )
        }
        if (actionLabel != null && onAction != null) {
            Spacer(Modifier.height(4.dp))
            Button(onClick = onAction) { Text(actionLabel) }
        }
        if (secondaryActionLabel != null && onSecondaryAction != null) {
            FilledTonalButton(onClick = onSecondaryAction) { Text(secondaryActionLabel) }
        }
    }
}

@Composable
fun ErrorState(
    message: String,
    modifier: Modifier = Modifier,
    title: String = "Something went wrong",
    retryLabel: String = "Retry",
    onRetry: (() -> Unit)? = null,
) {
    EmptyState(
        title = title,
        body = message,
        icon = Icons.Rounded.ErrorOutline,
        actionLabel = if (onRetry != null) retryLabel else null,
        onAction = onRetry,
        modifier = modifier,
    )
}

enum class PlaceholderStyle { LIST, GRID, CAROUSEL }

/** Shimmering tonal shapes that stand in for content while the first emission arrives. */
@Composable
fun LoadingPlaceholder(
    style: PlaceholderStyle = PlaceholderStyle.LIST,
    modifier: Modifier = Modifier,
    count: Int = 8,
) {
    val tone = MaterialTheme.colorScheme.surfaceContainerHigh
    val toneSoft = MaterialTheme.colorScheme.surfaceContainer
    when (style) {
        PlaceholderStyle.LIST -> Column(modifier.fillMaxWidth()) {
            repeat(count) {
                Row(
                    Modifier
                        .fillMaxWidth()
                        .padding(horizontal = Dimens.screenPadding, vertical = 8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Box(
                        Modifier
                            .size(Dimens.listArtwork)
                            .clip(ArtworkShapes.listThumb)
                            .shimmer(tone),
                    )
                    Spacer(Modifier.width(Dimens.itemSpacing))
                    Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        Box(Modifier.fillMaxWidth(0.6f).height(14.dp).clip(RoundedCornerShape(7.dp)).shimmer(tone))
                        Box(Modifier.fillMaxWidth(0.4f).height(12.dp).clip(RoundedCornerShape(6.dp)).shimmer(toneSoft))
                    }
                }
            }
        }
        PlaceholderStyle.GRID -> Column(modifier.fillMaxWidth().padding(horizontal = 12.dp)) {
            repeat((count + 1) / 2) {
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    repeat(2) {
                        Column(Modifier.weight(1f).padding(4.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                            Box(Modifier.fillMaxWidth().aspectRatio(1f).clip(ArtworkShapes.gridTile).shimmer(tone))
                            Box(Modifier.fillMaxWidth(0.7f).height(12.dp).clip(RoundedCornerShape(6.dp)).shimmer(tone))
                            Box(Modifier.fillMaxWidth(0.4f).height(10.dp).clip(RoundedCornerShape(5.dp)).shimmer(toneSoft))
                        }
                    }
                }
            }
        }
        PlaceholderStyle.CAROUSEL -> Row(
            modifier
                .fillMaxWidth()
                .padding(horizontal = Dimens.screenPadding),
            horizontalArrangement = Arrangement.spacedBy(Dimens.itemSpacing),
        ) {
            repeat(3) {
                Column(Modifier.width(Dimens.carouselArtwork), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Box(Modifier.size(Dimens.carouselArtwork).clip(ArtworkShapes.gridTile).shimmer(tone))
                    Box(Modifier.fillMaxWidth(0.7f).height(12.dp).clip(RoundedCornerShape(6.dp)).shimmer(tone))
                }
            }
        }
    }
}

/** Fills the whole viewport with a centred placeholder; useful inside `Box(fillMaxSize)`. */
@Composable
fun FullScreenPlaceholder(style: PlaceholderStyle = PlaceholderStyle.LIST) {
    Box(Modifier.fillMaxSize()) { LoadingPlaceholder(style = style, modifier = Modifier.padding(top = 8.dp)) }
}

/**
 * Stand-in for a [TrackRow] whose data has not been paged in yet. Same height and layout, so a
 * paged list keeps its length and scroll position while rows fill in.
 */
@Composable
fun TrackRowPlaceholder(modifier: Modifier = Modifier) {
    val tone = MaterialTheme.colorScheme.surfaceContainerHigh
    val toneSoft = MaterialTheme.colorScheme.surfaceContainer
    Row(
        modifier
            .fillMaxWidth()
            .padding(start = Dimens.screenPadding, end = 4.dp, top = 6.dp, bottom = 6.dp)
            .heightIn(min = 56.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(
            Modifier
                .size(Dimens.listArtwork)
                .clip(ArtworkShapes.listThumb)
                .shimmer(tone),
        )
        Spacer(Modifier.width(Dimens.itemSpacing))
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Box(Modifier.fillMaxWidth(0.55f).height(14.dp).clip(RoundedCornerShape(7.dp)).shimmer(tone))
            Box(Modifier.fillMaxWidth(0.35f).height(12.dp).clip(RoundedCornerShape(6.dp)).shimmer(toneSoft))
        }
    }
}
