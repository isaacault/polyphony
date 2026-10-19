package app.polyphony.core.dlna.engine

enum class EnginePlaybackState { IDLE, BUFFERING, READY, ENDED }

enum class EngineRepeatMode { OFF, ONE, ALL }

data class EngineItem<T>(val uid: Long, val item: T)

data class EngineError(val message: String, val code: Int = 0, val cause: Throwable? = null)

/** Immutable snapshot of the renderer session as the engine understands it. */
data class EngineState<T>(
    val items: List<EngineItem<T>> = emptyList(),
    val currentIndex: Int = -1,
    val playWhenReady: Boolean = false,
    val playbackState: EnginePlaybackState = EnginePlaybackState.IDLE,
    /** Position sampled at [positionSampledAtMs]; extrapolate with [positionAt] while playing. */
    val positionMs: Long = 0L,
    val positionSampledAtMs: Long = 0L,
    /** -1 when unknown. */
    val durationMs: Long = -1L,
    val isLoading: Boolean = false,
    val volumePercent: Int = 50,
    val muted: Boolean = false,
    val repeatMode: EngineRepeatMode = EngineRepeatMode.OFF,
    val shuffleEnabled: Boolean = false,
    /** Indices into [items] in playback order when shuffling. */
    val shuffleOrder: List<Int> = emptyList(),
    val error: EngineError? = null,
    val released: Boolean = false,
    /** Bumps on every seek and track change so adapters can announce discontinuities. */
    val discontinuityCounter: Long = 0L,
) {
    val currentItem: EngineItem<T>? get() = items.getOrNull(currentIndex)
    val isPlayingNow: Boolean get() = playWhenReady && playbackState == EnginePlaybackState.READY && error == null && !isLoading

    fun positionAt(nowMs: Long): Long {
        if (!isPlayingNow) return positionMs
        val extrapolated = positionMs + (nowMs - positionSampledAtMs).coerceAtLeast(0)
        return if (durationMs > 0) extrapolated.coerceAtMost(durationMs) else extrapolated
    }

    /** Index played after the current one finishes on its own. */
    fun autoAdvanceIndex(): Int? = when (repeatMode) {
        EngineRepeatMode.ONE -> currentIndex.takeIf { it >= 0 }
        EngineRepeatMode.ALL -> stepIndex(+1, wrap = true)
        EngineRepeatMode.OFF -> stepIndex(+1, wrap = false)
    }

    /** Index for an explicit "next" (never repeats the same track). */
    fun nextIndex(): Int? = stepIndex(+1, wrap = repeatMode == EngineRepeatMode.ALL)

    fun previousIndex(): Int? = stepIndex(-1, wrap = repeatMode == EngineRepeatMode.ALL)

    private fun stepIndex(delta: Int, wrap: Boolean): Int? {
        if (items.isEmpty() || currentIndex !in items.indices) return null
        val order = if (shuffleEnabled && shuffleOrder.size == items.size) shuffleOrder else items.indices.toList()
        val pos = order.indexOf(currentIndex).takeIf { it >= 0 } ?: return null
        var next = pos + delta
        if (next !in order.indices) {
            if (!wrap) return null
            next = ((next % order.size) + order.size) % order.size
        }
        return order[next]
    }
}
