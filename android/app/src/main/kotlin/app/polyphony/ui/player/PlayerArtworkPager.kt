package app.polyphony.ui.player

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.unit.dp
import androidx.compose.ui.util.lerp
import app.polyphony.core.domain.model.Track
import app.polyphony.core.ui.components.ArtworkImage
import kotlinx.collections.immutable.ImmutableList
import kotlinx.coroutines.flow.collectLatest
import kotlin.math.absoluteValue

/**
 * Swipeable artwork bound to the queue. Swiping settles on a page → the controller skips there;
 * controller index changes → the pager animates to match.
 */
@Composable
fun PlayerArtworkPager(
    queue: ImmutableList<Track>,
    currentIndex: Int,
    artworkUrlFor: (Track) -> String?,
    shape: Shape,
    onPageSettled: (Int) -> Unit,
    modifier: Modifier = Modifier,
) {
    val pagerState = rememberPagerState(
        initialPage = currentIndex.coerceIn(0, (queue.size - 1).coerceAtLeast(0)),
    ) { queue.size }
    val latestIndex by rememberUpdatedState(currentIndex)
    val latestQueue by rememberUpdatedState(queue)

    LaunchedEffect(currentIndex, queue.size) {
        if (currentIndex !in queue.indices) return@LaunchedEffect
        if (pagerState.currentPage == currentIndex || pagerState.isScrollInProgress) return@LaunchedEffect
        val sameTrackAtPage = queue.getOrNull(pagerState.currentPage)?.id == queue[currentIndex].id
        if (sameTrackAtPage) pagerState.scrollToPage(currentIndex) else pagerState.animateScrollToPage(currentIndex)
    }
    LaunchedEffect(pagerState) {
        snapshotFlow { pagerState.settledPage }.collectLatest { page ->
            if (page != latestIndex && page in latestQueue.indices) onPageSettled(page)
        }
    }

    HorizontalPager(
        state = pagerState,
        modifier = modifier,
        contentPadding = PaddingValues(horizontal = 28.dp),
        pageSpacing = 12.dp,
        beyondViewportPageCount = 1,
        key = { index -> "${queue.getOrNull(index)?.id}#$index" },
    ) { page ->
        val track = queue.getOrNull(page) ?: return@HorizontalPager
        Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            ArtworkImage(
                url = artworkUrlFor(track),
                contentDescription = track.albumName,
                shape = shape,
                crossfade = false,
                modifier = Modifier
                    .aspectRatio(1f)
                    .graphicsLayer {
                        val pageOffset =
                            ((pagerState.currentPage - page) + pagerState.currentPageOffsetFraction).absoluteValue
                        val progress = 1f - pageOffset.coerceIn(0f, 1f)
                        val scale = lerp(0.86f, 1f, progress)
                        scaleX = scale
                        scaleY = scale
                        alpha = lerp(0.55f, 1f, progress)
                    }
                    .shadow(elevation = 14.dp, shape = shape, clip = false),
            )
        }
    }
}
