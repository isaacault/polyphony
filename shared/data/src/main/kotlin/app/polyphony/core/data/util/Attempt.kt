package app.polyphony.core.data.util

import kotlinx.coroutines.CancellationException

/** Runs [block], returning null on failure but never swallowing coroutine cancellation. */
suspend fun <T> attempt(block: suspend () -> T): T? = try {
    block()
} catch (e: CancellationException) {
    throw e
} catch (e: Exception) {
    null
}
