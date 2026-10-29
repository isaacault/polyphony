package app.polyphony.core.ui.components

import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.runtime.Composable
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

/**
 * Extra bottom space screens must leave for overlays docked at the bottom (mini player, bottom
 * bar) when the shell lets content scroll underneath them. The shell provides it; screens read it.
 */
val LocalContentBottomPadding = compositionLocalOf { 0.dp }

/** Standard padding for scrolling content: optional top/horizontal plus the shell's bottom space. */
@Composable
fun listContentPadding(
    top: Dp = 0.dp,
    horizontal: Dp = 0.dp,
    extraBottom: Dp = 24.dp,
): PaddingValues = PaddingValues(
    start = horizontal,
    end = horizontal,
    top = top,
    bottom = extraBottom + LocalContentBottomPadding.current,
)
