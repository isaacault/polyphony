package app.polyphony.ui.browse.catalog

import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.ErrorOutline
import androidx.compose.material.icons.rounded.HourglassTop
import androidx.compose.material.icons.rounded.LibraryAdd
import androidx.compose.material.icons.rounded.Person
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import app.polyphony.R
import app.polyphony.core.domain.model.Album
import app.polyphony.core.domain.model.Availability
import app.polyphony.core.domain.model.CatalogAlbum
import app.polyphony.core.domain.model.CatalogArtist
import app.polyphony.core.domain.model.CatalogTrack
import app.polyphony.core.ui.components.AlbumCard
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
 * A catalogue item that isn't in the library, at full brightness like a library row: what marks it
 * is [RequestAction] at the end. [onClick] opens it, where there is something to open.
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
    /** Opens the row's options (a song's: Play now, Play next, Add to queue). */
    onLongClick: (() -> Unit)? = null,
) {
    Row(
        Modifier
            .fillMaxWidth()
            .then(
                if (onClick != null || onLongClick != null) {
                    Modifier.combinedClickable(onClick = onClick ?: {}, onLongClick = onLongClick)
                } else {
                    Modifier
                },
            )
            .padding(start = Dimens.screenPadding, end = Dimens.screenPadding, top = 4.dp, bottom = 4.dp)
            .heightIn(min = 52.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        ArtworkImage(url = artworkUrl, contentDescription = null, modifier = Modifier.size(Dimens.listArtwork))
        Spacer(Modifier.width(Dimens.itemSpacing))
        Column(Modifier.weight(1f)) {
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
        RequestAction(availability, releaseGroupId, busy, requestLabel, onRequest)
    }
}

/** What [RequestAction] shows for an item. */
internal enum class RequestState { NONE, REQUESTABLE, BUSY, UNDER_WAY, NEEDS_LOOK }

internal fun requestState(availability: Availability, releaseGroupId: String?, busy: Boolean): RequestState = when {
    busy -> RequestState.BUSY
    EverythingSection.canRequest(availability, releaseGroupId) -> RequestState.REQUESTABLE
    availability is Availability.Requested && availability.state == "needs_attention" -> RequestState.NEEDS_LOOK
    availability is Availability.Requested -> RequestState.UNDER_WAY
    else -> RequestState.NONE
}

/**
 * The mark of an item that isn't in the library. "Library add" (a stack with a plus) requests it:
 * it reads as "add to the server's library" and never as the phone's download arrow. An hourglass
 * means a request is under way, an alert that one needs a look. [label] is what the button says
 * to TalkBack; the status icons say their status.
 */
@Composable
internal fun RequestAction(
    availability: Availability,
    releaseGroupId: String?,
    busy: Boolean,
    label: String,
    onRequest: (String) -> Unit,
    modifier: Modifier = Modifier,
) {
    when (requestState(availability, releaseGroupId, busy)) {
        RequestState.NONE -> Unit
        RequestState.BUSY -> Box(modifier.size(48.dp), contentAlignment = Alignment.Center) {
            CircularProgressIndicator(Modifier.size(20.dp), strokeWidth = 2.dp)
        }
        RequestState.REQUESTABLE -> IconButton(onClick = { releaseGroupId?.let(onRequest) }, modifier = modifier) {
            Icon(Icons.Rounded.LibraryAdd, contentDescription = label, tint = MaterialTheme.colorScheme.primary)
        }
        RequestState.UNDER_WAY, RequestState.NEEDS_LOOK -> Box(modifier.size(48.dp), contentAlignment = Alignment.Center) {
            val look = availability is Availability.Requested && availability.state == "needs_attention"
            Icon(
                if (look) Icons.Rounded.ErrorOutline else Icons.Rounded.HourglassTop,
                contentDescription = availabilityLabel(availability)?.let { stringResource(it) },
                tint = if (look) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

/**
 * A catalogue album as a library-style card: the same artwork, title and year, with
 * [RequestAction] on a small disc over the artwork's corner. Opens the album page.
 */
@Composable
internal fun CatalogAlbumCard(
    album: CatalogAlbum,
    busy: Boolean,
    onRequest: (String) -> Unit,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Box(modifier) {
        AlbumCard(album = album.asAlbum(), onClick = onClick, subtitle = catalogAlbumSubtitle(album))
        if (requestState(album.availability, album.releaseGroupId, busy) != RequestState.NONE) {
            Surface(
                shape = CircleShape,
                color = MaterialTheme.colorScheme.surfaceContainerHigh.copy(alpha = 0.92f),
                modifier = Modifier.align(Alignment.TopEnd).padding(10.dp),
            ) {
                RequestAction(album.availability, album.releaseGroupId, busy, stringResource(R.string.browse_action_request), onRequest)
            }
        }
    }
}

/** The year, and where a request stands once there is one. */
@Composable
internal fun catalogAlbumSubtitle(album: CatalogAlbum): String = listOfNotNull(
    album.year?.toString(),
    availabilityLabel(album.availability)?.let { stringResource(it) },
).joinToString(" · ")

// The library's card, fed a catalogue item: its id is a catalogue key, never a library id.
internal fun CatalogAlbum.asAlbum() = Album(id = key, serverId = "", name = title, artistName = artist, year = year, artworkUrl = artworkUrl)

/** What a song row's request button says to TalkBack: the song, when it knows its recording. */
internal fun requestTrackLabel(track: CatalogTrack): Int =
    if (track.recordingId != null) R.string.browse_action_request_song else R.string.browse_action_request_album

/** The tonal status chip for a hit that's already under way. */
internal fun availabilityLabel(availability: Availability): Int? = when (availability) {
    is Availability.OnServer -> R.string.browse_availability_on_server
    is Availability.Requested -> when (availability.state) {
        "searching" -> if (availability.stillLooking) R.string.browse_availability_still_looking else R.string.browse_availability_searching
        "downloading" -> R.string.browse_availability_downloading
        "importing", "scanning" -> R.string.browse_availability_importing
        "needs_attention" -> R.string.browse_availability_needs_attention
        else -> R.string.browse_availability_requested
    }
    else -> null
}

/** A catalogue artist (nothing of theirs in the library); opens their page. */
@Composable
internal fun CatalogArtistRow(artist: CatalogArtist, onClick: () -> Unit, subtitle: String? = null) {
    MediaRow(
        title = artist.name,
        subtitle = subtitle ?: stringResource(R.string.browse_catalog_artist_subtitle),
        artworkUrl = artist.artworkUrl,
        onClick = onClick,
        shape = CircleShape,
        placeholder = Icons.Rounded.Person,
    )
}

/** The section title for a release type. */
internal fun releaseTypeLabel(type: ReleaseType): Int = when (type) {
    ReleaseType.ALBUM -> R.string.browse_catalog_albums
    ReleaseType.EP -> R.string.browse_catalog_eps
    ReleaseType.SINGLE -> R.string.browse_catalog_singles
    ReleaseType.OTHER -> R.string.browse_catalog_other
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
