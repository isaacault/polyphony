package app.polyphony.ui.player

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsDraggedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.TransformOrigin
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.polyphony.R
import app.polyphony.core.domain.model.Lyrics
import kotlinx.coroutines.delay

/** Synced lyrics follow the position; tapping a line seeks. Unsynced lyrics just scroll. */
@Composable
fun LyricsView(
    state: LyricsUiState,
    positionMs: Long,
    onSeek: (Long) -> Unit,
    modifier: Modifier = Modifier,
) {
    Box(modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        when (state) {
            LyricsUiState.Hidden, LyricsUiState.Loading -> Column(horizontalAlignment = Alignment.CenterHorizontally) {
                CircularProgressIndicator(strokeWidth = 2.dp)
                Text(
                    text = stringResource(R.string.lyrics_loading),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(top = 16.dp),
                )
            }
            LyricsUiState.None -> Column(horizontalAlignment = Alignment.CenterHorizontally, modifier = Modifier.padding(32.dp)) {
                Text(stringResource(R.string.lyrics_none), style = MaterialTheme.typography.titleMedium)
                Text(
                    text = stringResource(R.string.lyrics_none_hint),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    textAlign = TextAlign.Center,
                    modifier = Modifier.padding(top = 8.dp),
                )
            }
            is LyricsUiState.Loaded -> if (state.lyrics.synced) {
                SyncedLyrics(state.lyrics, positionMs, onSeek)
            } else {
                PlainLyrics(state.lyrics)
            }
        }
    }
}

@Composable
private fun SyncedLyrics(lyrics: Lyrics, positionMs: Long, onSeek: (Long) -> Unit) {
    val lines = lyrics.lines
    val activeIndex = remember(lines, positionMs) {
        var idx = -1
        for (i in lines.indices) {
            val start = lines[i].startMs ?: continue
            if (start <= positionMs + 250) idx = i else break
        }
        idx
    }
    val listState = rememberLazyListState()
    val dragged by listState.interactionSource.collectIsDraggedAsState()
    var userScrolling by remember { mutableStateOf(false) }
    LaunchedEffect(dragged) {
        if (dragged) {
            userScrolling = true
        } else {
            delay(3_000)
            userScrolling = false
        }
    }
    LaunchedEffect(activeIndex, userScrolling) {
        if (userScrolling || activeIndex < 0) return@LaunchedEffect
        val viewport = listState.layoutInfo.viewportSize.height
        listState.animateScrollToItem(activeIndex, scrollOffset = -(viewport / 2 - viewport / 10))
    }
    LazyColumn(
        state = listState,
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(horizontal = 28.dp, vertical = 120.dp),
        verticalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        itemsIndexed(lines, key = { index, _ -> index }) { index, line ->
            val active = index == activeIndex
            val scale by animateFloatAsState(if (active) 1f else 0.94f, label = "lyricScale")
            val alpha by animateFloatAsState(if (active) 1f else if (index < activeIndex) 0.35f else 0.5f, label = "lyricAlpha")
            Text(
                text = line.text.ifBlank { "♪" },
                style = MaterialTheme.typography.headlineSmall.copy(lineHeight = 32.sp),
                fontWeight = if (active) FontWeight.Bold else FontWeight.SemiBold,
                color = MaterialTheme.colorScheme.onSurface,
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable(
                        interactionSource = remember { MutableInteractionSource() },
                        indication = null,
                        enabled = line.startMs != null,
                    ) { line.startMs?.let(onSeek) }
                    .graphicsLayer {
                        this.alpha = alpha
                        scaleX = scale
                        scaleY = scale
                        transformOrigin = TransformOrigin(0f, 0.5f)
                    }
                    .padding(vertical = 8.dp),
            )
        }
    }
}

@Composable
private fun PlainLyrics(lyrics: Lyrics) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 28.dp, vertical = 24.dp),
    ) {
        Text(
            text = lyrics.plainText,
            style = MaterialTheme.typography.bodyLarge.copy(lineHeight = 30.sp),
            color = MaterialTheme.colorScheme.onSurface,
        )
        lyrics.source?.let {
            Text(
                text = it,
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(top = 24.dp),
            )
        }
    }
}
