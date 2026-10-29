package app.polyphony.ui.browse

import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.ArrowDropDown
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.DownloadDone
import androidx.compose.material.icons.rounded.Favorite
import androidx.compose.material.icons.rounded.MusicNote
import androidx.compose.material.icons.rounded.Star
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.RadioButton
import androidx.compose.material3.RangeSlider
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import app.polyphony.R
import app.polyphony.core.domain.model.FilterField
import app.polyphony.core.domain.model.LibraryFilter
import app.polyphony.core.ui.theme.Dimens
import kotlinx.collections.immutable.ImmutableList
import kotlinx.collections.immutable.persistentListOf
import kotlin.math.roundToInt

/**
 * The filter chips under a list's sort row (and under the search field): one chip per field in
 * [fields], in a horizontally scrolling row, plus Clear once anything is set. Toggles flip in
 * place; Rating opens a menu, Genre and Year a picker. [years] is the library's year span; the
 * Year chip hides without one.
 */
@Composable
fun LibraryFilterRow(
    filter: LibraryFilter,
    fields: Set<FilterField>,
    onChange: (LibraryFilter) -> Unit,
    modifier: Modifier = Modifier,
    genres: ImmutableList<String> = persistentListOf(),
    years: IntRange? = null,
    /** Chips before the filters (Search puts its Source chip here). */
    leading: @Composable () -> Unit = {},
) {
    var picker by remember { mutableStateOf<FilterField?>(null) }
    Row(
        modifier = modifier
            .fillMaxWidth()
            .horizontalScroll(rememberScrollState())
            .padding(horizontal = Dimens.screenPadding)
            .padding(bottom = 4.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        leading()
        if (FilterField.FAVOURITES in fields) {
            FilterChip(
                selected = filter.favouritesOnly,
                onClick = { onChange(filter.copy(favouritesOnly = !filter.favouritesOnly)) },
                label = { Text(stringResource(R.string.browse_filter_favourites)) },
                leadingIcon = { ChipIcon(Icons.Rounded.Favorite) },
            )
        }
        if (FilterField.RATING in fields) {
            Box {
                FilterChip(
                    selected = filter.minRating > 0,
                    onClick = { picker = FilterField.RATING },
                    label = { Text(ratingLabel(filter.minRating)) },
                    leadingIcon = { ChipIcon(Icons.Rounded.Star) },
                    trailingIcon = { ChipIcon(Icons.Rounded.ArrowDropDown) },
                )
                DropdownMenu(expanded = picker == FilterField.RATING, onDismissRequest = { picker = null }) {
                    (0..5).forEach { stars ->
                        DropdownMenuItem(
                            text = { Text(ratingLabel(stars)) },
                            onClick = {
                                picker = null
                                onChange(filter.copy(minRating = stars))
                            },
                            trailingIcon = { if (stars == filter.minRating) RadioButton(selected = true, onClick = null) },
                        )
                    }
                }
            }
        }
        if (FilterField.SINGLES in fields) {
            FilterChip(
                selected = filter.singleSongs,
                onClick = { onChange(filter.copy(singleSongs = !filter.singleSongs)) },
                label = { Text(stringResource(R.string.browse_filter_single_songs)) },
                leadingIcon = { ChipIcon(Icons.Rounded.MusicNote) },
            )
        }
        if (FilterField.DOWNLOADED in fields) {
            FilterChip(
                selected = filter.downloadedOnly,
                onClick = { onChange(filter.copy(downloadedOnly = !filter.downloadedOnly)) },
                label = { Text(stringResource(R.string.browse_filter_downloaded)) },
                leadingIcon = { ChipIcon(Icons.Rounded.DownloadDone) },
            )
        }
        if (FilterField.GENRE in fields && (genres.isNotEmpty() || filter.genre != null)) {
            FilterChip(
                selected = filter.genre != null,
                onClick = { picker = FilterField.GENRE },
                label = { Text(filter.genre ?: stringResource(R.string.browse_filter_genre)) },
                trailingIcon = { ChipIcon(Icons.Rounded.ArrowDropDown) },
            )
        }
        val yearSet = filter.yearFrom != null || filter.yearTo != null
        if (FilterField.YEAR in fields && (years != null || yearSet)) {
            FilterChip(
                selected = yearSet,
                onClick = { picker = FilterField.YEAR },
                label = { Text(yearLabel(filter)) },
                trailingIcon = { ChipIcon(Icons.Rounded.ArrowDropDown) },
            )
        }
        if (filter.isActive) {
            TextButton(onClick = { onChange(LibraryFilter.NONE) }) {
                Icon(Icons.Rounded.Close, contentDescription = null, modifier = Modifier.size(FilterChipDefaults.IconSize))
                Text(stringResource(R.string.browse_filter_clear), modifier = Modifier.padding(start = 4.dp))
            }
        }
    }
    when (picker) {
        FilterField.GENRE -> GenrePicker(
            genres = genres,
            selected = filter.genre,
            onPick = { onChange(filter.copy(genre = it)); picker = null },
            onDismiss = { picker = null },
        )
        FilterField.YEAR -> YearPicker(
            bounds = years ?: (filter.yearFrom ?: filter.yearTo ?: 2000)..(filter.yearTo ?: filter.yearFrom ?: 2000),
            filter = filter,
            onPick = { from, to -> onChange(filter.copy(yearFrom = from, yearTo = to)); picker = null },
            onDismiss = { picker = null },
        )
        else -> Unit
    }
}

@Composable
private fun ChipIcon(icon: androidx.compose.ui.graphics.vector.ImageVector) =
    Icon(icon, contentDescription = null, modifier = Modifier.size(FilterChipDefaults.IconSize))

@Composable
private fun ratingLabel(stars: Int): String = when (stars) {
    0 -> stringResource(R.string.browse_filter_rating_any)
    5 -> stringResource(R.string.browse_filter_rating_five)
    else -> stringResource(R.string.browse_filter_rating_at_least, stars)
}

@Composable
private fun yearLabel(filter: LibraryFilter): String {
    val from = filter.yearFrom
    val to = filter.yearTo
    return when {
        from != null && to != null && from == to -> from.toString()
        from != null && to != null -> stringResource(R.string.browse_filter_year_range, from, to)
        from != null -> stringResource(R.string.browse_filter_year_from, from)
        to != null -> stringResource(R.string.browse_filter_year_to, to)
        else -> stringResource(R.string.browse_filter_year)
    }
}

@Composable
private fun GenrePicker(genres: ImmutableList<String>, selected: String?, onPick: (String?) -> Unit, onDismiss: () -> Unit) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.browse_filter_genre)) },
        text = {
            LazyColumn(Modifier.heightIn(max = 420.dp)) {
                item(key = "any") { PickerRow(stringResource(R.string.browse_filter_genre_any), selected == null) { onPick(null) } }
                items(genres, key = { it }) { genre ->
                    PickerRow(genre, genre.equals(selected, ignoreCase = true)) { onPick(genre) }
                }
            }
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.dialog_cancel)) } },
    )
}

@Composable
private fun PickerRow(label: String, selected: Boolean, onClick: () -> Unit) {
    Row(
        Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        RadioButton(selected = selected, onClick = null)
        Text(label, style = MaterialTheme.typography.bodyLarge, modifier = Modifier.padding(start = 12.dp))
    }
}

/** A range slider over the library's years. Dragging a thumb to the edge leaves that side open. */
@Composable
private fun YearPicker(bounds: IntRange, filter: LibraryFilter, onPick: (Int?, Int?) -> Unit, onDismiss: () -> Unit) {
    val lo = bounds.first.toFloat()
    val hi = bounds.last.toFloat().coerceAtLeast(lo + 1f)
    var range by remember {
        mutableStateOf((filter.yearFrom?.toFloat() ?: lo).coerceIn(lo, hi)..(filter.yearTo?.toFloat() ?: hi).coerceIn(lo, hi))
    }
    val from = range.start.roundToInt()
    val to = range.endInclusive.roundToInt()
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.browse_filter_year)) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(
                    stringResource(R.string.browse_filter_year_range, from, to),
                    style = MaterialTheme.typography.headlineSmall,
                    modifier = Modifier.align(Alignment.CenterHorizontally),
                )
                RangeSlider(
                    value = range,
                    onValueChange = { range = it },
                    valueRange = lo..hi,
                    steps = (hi - lo).roundToInt() - 1,
                )
            }
        },
        confirmButton = {
            TextButton(onClick = { onPick(from.takeIf { it > lo.roundToInt() }, to.takeIf { it < hi.roundToInt() }) }) {
                Text(stringResource(R.string.browse_filter_apply))
            }
        },
        dismissButton = { TextButton(onClick = { onPick(null, null) }) { Text(stringResource(R.string.browse_filter_year_any)) } },
    )
}
