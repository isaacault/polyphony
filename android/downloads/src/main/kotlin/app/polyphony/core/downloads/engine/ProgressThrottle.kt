package app.polyphony.core.downloads.engine

/** Limits progress writes (DB + notification) to roughly [minIntervalMs]; always lets 100% through. */
class ProgressThrottle(
    private val minIntervalMs: Long = 500L,
    private val clock: () -> Long = System::currentTimeMillis,
) {
    private var lastEmitAt: Long = Long.MIN_VALUE
    var lastValue: Float = -1f
        private set

    fun shouldEmit(progress: Float, force: Boolean = false): Boolean {
        val now = clock()
        val due = lastEmitAt == Long.MIN_VALUE || now - lastEmitAt >= minIntervalMs
        if (force || progress >= 1f || due) {
            if (progress == lastValue && !force && progress < 1f) return false
            lastEmitAt = now
            lastValue = progress
            return true
        }
        return false
    }
}
