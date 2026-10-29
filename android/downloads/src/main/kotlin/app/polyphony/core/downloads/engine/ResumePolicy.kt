package app.polyphony.core.downloads.engine

sealed interface ResumeDecision {
    /** Continue with an HTTP `Range: bytes=offset-` request, appending to the partial file. */
    data class RangeFrom(val offset: Long) : ResumeDecision
    /** Start over from byte 0, truncating any partial file. */
    data object Restart : ResumeDecision
}

/** Original files are byte-stable and resumable; transcoded streams are generated on the fly and are not. */
object ResumePolicy {
    fun decide(isOriginal: Boolean, partExists: Boolean, partSizeBytes: Long): ResumeDecision =
        if (isOriginal && partExists && partSizeBytes > 0L) ResumeDecision.RangeFrom(partSizeBytes)
        else ResumeDecision.Restart
}
