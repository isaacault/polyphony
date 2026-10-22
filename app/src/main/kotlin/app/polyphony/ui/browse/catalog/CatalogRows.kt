package app.polyphony.ui.browse.catalog

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Person
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import app.polyphony.R
import app.polyphony.core.domain.model.Availability
import app.polyphony.core.domain.model.CatalogArtist
import app.polyphony.core.ui.components.ArtworkImage
import app.polyphony.core.ui.components.MediaRow
import app.polyphony.core.ui.components.QualityBadge
import app.polyphony.core.ui.theme.Dimens
import app.polyphony.ui.browse.search.EverythingSection

/** On a library album the catalogue only partly matches: how much of the release you have. */
@Composable
internal fun PartialBadge(partial: Availability.Partial) {
    QualityBadge(label = stringResource(R.string.browse_availability_partial, partial.have, partial.expected))
}

/**
 * A catalogue item that isn't in the library: dimmed, with Request when it can be requested or a
 * status chip when a request is under way. [onClick] opens it, where there is something to open.
 */
@Composable
internal fun CatalogRow(
    title: String,
    subtitle: String?,
    artworkUrl: String?,
    availability: Availability,
    releaseGroupId: String?,
    busy: Boolean,
    requestLabel: String,
    onRequest: (String) -> Unit,
    onClick: (() -> Unit)? = null,
) {
    Row(
        Modifier
            .fillMaxWidth()
            .then(if (onClick != null) Modifier.clickable(onClick = onClick) else Modifier)
            .padding(start = Dimens.screenPadding, end = Dimens.screenPadding, top = 4.dp, bottom = 4.dp)
            .heightIn(min = 52.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        // Dimmed: not in your library. The Request button keeps full contrast.
        ArtworkImage(url = artworkUrl, contentDescription = null, modifier = Modifier.size(Dimens.listArtwork).alpha(CATALOG_ART_ALPHA))
        Spacer(Modifier.width(Dimens.itemSpacing))
        Column(Modifier.weight(1f).alpha(CATALOG_TEXT_ALPHA)) {
            Text(title, style = MaterialTheme.typography.bodyMedium, maxLines = 1, overflow = TextOverflow.Ellipsis)
            if (subtitle != null) {
                Text(
                    subtitle,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
        Spacer(Modifier.width(8.dp))
        if (EverythingSection.canRequest(availability, releaseGroupId) && releaseGroupId != null) {
            FilledTonalButton(
                onClick = { onRequest(releaseGroupId) },
                enabled = !busy,
                contentPadding = PaddingValues(horizontal = 12.dp, vertical = 4.dp),
            ) { Text(requestLabel, style = MaterialTheme.typography.labelLarge) }
        } else {
            availabilityLabel(availability)?.let { QualityBadge(label = stringResource(it)) }
        }
    }
}

// Not in the library: the artwork recedes, the text stays easy to read.
private const val CATALOG_ART_ALPHA = 0.7f
internal const val CATALOG_TEXT_ALPHA = 0.85f

/** The tonal status chip for a hit that's already under way. */
internal fun availabilityLabel(availability: Availability): Int? = when (availability) {
    is Availability.Requested -> when (availability.state) {
        "searching" -> if (availability.stillLooking) R.string.browse_availability_still_looking else R.string.browse_availability_searching
        "downloading" -> R.string.browse_availability_downloading
        "importing", "scanning" -> R.string.browse_availability_importing
        "needs_attention" -> R.string.browse_availability_needs_attention
        else -> R.string.browse_availability_requested
    }
    else -> null
}

/** A catalogue artist (nothing of theirs in the library): dimmed; opens their discography. */
@Composable
internal fun CatalogArtistRow(artist: CatalogArtist, onClick: () -> Unit) {
    MediaRow(
        title = artist.name,
        subtitle = stringResource(R.string.browse_catalog_artist_subtitle),
        artworkUrl = artist.artworkUrl,
        onClick = onClick,
        shape = CircleShape,
        placeholder = Icons.Rounded.Person,
        modifier = Modifier.alpha(CATALOG_TEXT_ALPHA),
    )
}

/**
 * Discography groups as list items: [header] for each release type, then a catalogue row per
 * release with Request or its status. [onOpen] gets the release group and title of a tapped row.
 */
internal fun LazyListScope.discographyItems(
    groups: List<DiscographyGroup>,
    requesting: Set<String>,
    onRequest: (String) -> Unit,
    onOpen: (releaseGroupId: String, title: String) -> Unit,
    header: @Composable (ReleaseType) -> Unit,
) {
    groups.forEach { group ->
        item(key = "dh-" + group.type) { header(group.type) }
        items(group.albums, key = { "d-" + it.key }, contentType = { "catalog-album" }) { album ->
            CatalogRow(
                title = album.title,
                subtitle = album.year?.toString(),
                artworkUrl = album.artworkUrl,
                availability = album.availability,
                releaseGroupId = album.releaseGroupId,
                busy = album.releaseGroupId in requesting,
                requestLabel = stringResource(R.string.browse_action_request),
                onRequest = onRequest,
                onClick = album.releaseGroupId?.let { rg -> { onOpen(rg, album.title) } },
            )
        }
    }
}

/** The section title for a release type. */
internal fun releaseTypeLabel(type: ReleaseType): Int = when (type) {
    ReleaseType.ALBUM -> R.string.browse_catalog_albums
    ReleaseType.EP -> R.string.browse_catalog_eps
    ReleaseType.SINGLE -> R.string.browse_catalog_singles
    ReleaseType.OTHER -> R.string.browse_catalog_other
}

/** A small label over one release type's rows, under a page's "Not in your library" header. */
@Composable
internal fun ReleaseTypeLabel(type: ReleaseType) {
    Text(
        stringResource(releaseTypeLabel(type)),
        style = MaterialTheme.typography.titleSmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.padding(start = Dimens.screenPadding, end = Dimens.screenPadding, top = 12.dp, bottom = 4.dp),
    )
}

/** One quiet line about the catalogue part of a page: loading, failed or empty. */
@Composable
internal fun CatalogNote(text: Int, isError: Boolean = false) {
    Text(
        stringResource(text),
        style = MaterialTheme.typography.bodyMedium,
        color = if (isError) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.padding(horizontal = Dimens.screenPadding, vertical = 8.dp),
    )
}
