package app.polyphony.core.dlna.soap

/**
 * A UPnP/SOAP failure. [code] is the UPnP error code (e.g. 401 Invalid Action, 701 Transition not
 * available, 714 Illegal MIME type, 716 Resource not found) or the HTTP status when no fault body
 * was returned; 0 for transport errors.
 */
class DlnaException(
    val code: Int,
    message: String,
    cause: Throwable? = null,
) : Exception("UPnP error $code: $message", cause) {
    val isActionUnsupported: Boolean get() = code == 401 || code == 402 || code == 602
    val isTransitionUnavailable: Boolean get() = code == 701
    val isTransportError: Boolean get() = code == 0
}
