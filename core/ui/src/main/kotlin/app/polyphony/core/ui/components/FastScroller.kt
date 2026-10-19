package app.polyphony.core.ui.components

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.detectVerticalDragGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import kotlinx.collections.immutable.ImmutableList
import kotlinx.collections.immutable.persistentListOf
import kotlinx.collections.immutable.toImmutableList

val DefaultScrollerLetters: ImmutableList<Char> = (listOf('#') + ('A'..'Z')).toImmutableList()

/** Bucket a display name into a scroller letter. */
fun scrollerLetter(name: String?): Char {
    val c = name?.trim()?.firstOrNull { !it.isWhitespace() }?.uppercaseChar() ?: return '#'
    return if (c in 'A'..'Z') c else '#'
}

/**
 * Vertical A–Z rail. Place it at the end of a `Box` that also holds the list; call
 * `listState.scrollToItem(index)` from [onLetterSelected] with an index you precomputed.
 */
@Composable
fun FastScroller(
    onLetterSelected: (Char) -> Unit,
    modifier: Modifier = Modifier,
    letters: ImmutableList<Char> = DefaultScrollerLetters,
    availableLetters: ImmutableList<Char> = persistentListOf(),
) {
    val haptics = LocalHapticFeedback.current
    val density = LocalDensity.current
    var active by remember { mutableStateOf(false) }
    var currentIndex by remember { mutableIntStateOf(-1) }
    var railHeightPx by remember { mutableIntStateOf(1) }

    fun select(y: Float) {
        if (letters.isEmpty()) return
        val perLetter = railHeightPx.toFloat() / letters.size
        val index = (y / perLetter).toInt().coerceIn(0, letters.lastIndex)
        if (index != currentIndex) {
            currentIndex = index
            haptics.performHapticFeedback(HapticFeedbackType.SegmentTick)
            onLetterSelected(letters[index])
        }
    }

    BoxWithConstraints(
        modifier = modifier
            .fillMaxHeight()
            .width(44.dp),
        contentAlignment = Alignment.CenterEnd,
    ) {
        railHeightPx = with(density) { maxHeight.roundToPx() }
        Column(
            modifier = Modifier
                .fillMaxHeight()
                .width(24.dp)
                .padding(end = 4.dp)
                .pointerInput(letters) {
                    detectVerticalDragGestures(
                        onDragStart = { active = true; select(it.y) },
                        onDragEnd = { active = false; currentIndex = -1 },
                        onDragCancel = { active = false; currentIndex = -1 },
                        onVerticalDrag = { change, _ -> change.consume(); select(change.position.y) },
                    )
                }
                .pointerInput(letters) {
                    detectTapGestures(onPress = { offset ->
                        active = true
                        select(offset.y)
                        tryAwaitRelease()
                        active = false
                        currentIndex = -1
                    })
                },
            verticalArrangement = Arrangement.SpaceEvenly,
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            letters.forEach { letter ->
                val enabled = availableLetters.isEmpty() || letter in availableLetters
                Text(
                    text = letter.toString(),
                    style = MaterialTheme.typography.labelSmall,
                    fontWeight = if (active && currentIndex >= 0 && letters[currentIndex] == letter) FontWeight.Bold else FontWeight.Medium,
                    color = if (enabled) MaterialTheme.colorScheme.onSurfaceVariant else MaterialTheme.colorScheme.outlineVariant,
                )
            }
        }
        AnimatedVisibility(
            visible = active && currentIndex >= 0,
            enter = fadeIn() + scaleIn(initialScale = 0.8f),
            exit = fadeOut() + scaleOut(targetScale = 0.8f),
            modifier = Modifier.align(Alignment.TopEnd),
        ) {
            val perLetter = if (letters.isEmpty()) 0.dp else maxHeight / letters.size
            val bubbleOffset = perLetter * currentIndex.coerceAtLeast(0) + perLetter / 2 - 28.dp
            Surface(
                modifier = Modifier
                    .offset(x = (-40).dp, y = bubbleOffset.coerceAtLeast(0.dp))
                    .size(56.dp),
                shape = CircleShape,
                color = MaterialTheme.colorScheme.primaryContainer,
                contentColor = MaterialTheme.colorScheme.onPrimaryContainer,
                tonalElevation = 3.dp,
                shadowElevation = 4.dp,
            ) {
                Box(contentAlignment = Alignment.Center) {
                    Text(
                        text = letters.getOrNull(currentIndex)?.toString() ?: "",
                        style = MaterialTheme.typography.headlineSmall,
                        fontWeight = FontWeight.Bold,
                    )
                }
            }
        }
    }
}
