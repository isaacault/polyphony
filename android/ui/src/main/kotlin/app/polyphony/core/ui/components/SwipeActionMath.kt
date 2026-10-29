package app.polyphony.core.ui.components

import kotlin.math.abs
import kotlin.math.sign

/**
 * Pure decisions behind [SwipeActionRow]: how far the row follows the finger and when a release
 * counts as "do it". Kept free of Compose so it can be unit-tested.
 */
object SwipeActionMath {

    /** Fraction of the row width the finger must travel for a slow swipe to commit. */
    const val THRESHOLD_FRACTION = 0.3f

    /** Visual travel stops rubber-banding at this multiple of the threshold. */
    const val CAP_MULTIPLIER = 1.5f

    /** Beyond the cap the row moves this fraction of the extra finger travel. */
    const val OVERSHOOT_RESISTANCE = 0.15f

    /** A fling at or above this speed (px/s) commits with less travel. */
    const val FLING_VELOCITY_PX_PER_S = 1_800f

    /** Even a fast fling must have travelled this fraction of the threshold. */
    const val MIN_FLING_FRACTION = 0.35f

    /** Threshold in pixels for a row of [widthPx], capped so tablets do not need a long reach. */
    fun thresholdPx(widthPx: Float, maxThresholdPx: Float): Float =
        (widthPx * THRESHOLD_FRACTION).coerceAtMost(maxThresholdPx).coerceAtLeast(1f)

    /**
     * Offset the row is drawn at for a raw finger travel of [rawPx]. Linear up to the cap, then
     * heavily damped so the row never leaves the screen.
     */
    fun dampedOffset(rawPx: Float, thresholdPx: Float): Float {
        val cap = thresholdPx * CAP_MULTIPLIER
        val magnitude = abs(rawPx)
        if (magnitude <= cap) return rawPx
        return sign(rawPx) * (cap + (magnitude - cap) * OVERSHOOT_RESISTANCE)
    }

    /**
     * Whether a release at [rawPx] finger travel (same sign as the active direction) with
     * [velocityPxPerS] should run the action.
     */
    fun shouldCommit(rawPx: Float, thresholdPx: Float, velocityPxPerS: Float): Boolean {
        val travel = abs(rawPx)
        if (travel >= thresholdPx) return true
        val sameDirection = rawPx != 0f && sign(velocityPxPerS) == sign(rawPx)
        return sameDirection &&
            abs(velocityPxPerS) >= FLING_VELOCITY_PX_PER_S &&
            travel >= thresholdPx * MIN_FLING_FRACTION
    }
}
