package app.polyphony.ui.player

import androidx.compose.runtime.Composable
import androidx.compose.runtime.Stable
import androidx.compose.runtime.remember
import androidx.compose.ui.hapticfeedback.HapticFeedback
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalHapticFeedback

/** Thin wrapper so every haptic honours the user's "haptic feedback" setting. */
@Stable
class Haptics internal constructor(
    private val feedback: HapticFeedback,
    private val enabled: Boolean,
) {
    fun longPress() = perform(HapticFeedbackType.LongPress)
    fun gestureEnd() = perform(HapticFeedbackType.GestureEnd)
    fun confirm() = perform(HapticFeedbackType.Confirm)
    fun segmentTick() = perform(HapticFeedbackType.SegmentTick)
    fun toggle(on: Boolean) = perform(if (on) HapticFeedbackType.ToggleOn else HapticFeedbackType.ToggleOff)
    fun tap() = perform(HapticFeedbackType.ContextClick)

    private fun perform(type: HapticFeedbackType) {
        if (enabled) feedback.performHapticFeedback(type)
    }
}

@Composable
fun rememberHaptics(enabled: Boolean = true): Haptics {
    val feedback = LocalHapticFeedback.current
    return remember(feedback, enabled) { Haptics(feedback, enabled) }
}
