package app.polyphony.ui.settings.servers

import app.polyphony.core.domain.model.Server
import app.polyphony.core.domain.provider.ConnectRequest

/** A sign-in that server edit made with a typed password: what it sent and the server it got. */
data class SignIn(val request: ConnectRequest, val server: Server)

/**
 * Whether server edit may use an earlier sign-in instead of signing in again (#121), kept pure so
 * it's unit-tested.
 *
 * Every continuo sign-in creates a new device with its own app password, so Test followed by Save
 * would leave an extra device behind. Save (or a repeated Test) therefore uses the server the last
 * sign-in produced when it would send exactly the same request: same name, URL, username, typed
 * password and options. Anything changed since signs in again. Plain Subsonic and Jellyfin results
 * are never reused, so their behaviour is unchanged.
 */
object SignInReuse {

    fun reusable(last: SignIn?, request: ConnectRequest): Server? {
        if (last == null || last.request != request) return null
        return last.server.takeIf { it.isContinuo }
    }
}
