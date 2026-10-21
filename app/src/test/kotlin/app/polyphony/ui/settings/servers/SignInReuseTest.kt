package app.polyphony.ui.settings.servers

import app.polyphony.core.domain.model.Server
import app.polyphony.core.domain.model.ServerOptions
import app.polyphony.core.domain.model.ServerType
import app.polyphony.core.domain.provider.ConnectRequest
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Test

class SignInReuseTest {

    private val request = ConnectRequest(
        type = ServerType.SUBSONIC,
        name = "Home",
        baseUrl = "https://music.example.com",
        username = "isaac",
        password = "account-password",
        options = mapOf(ServerOptions.TRUST_SELF_SIGNED to "true"),
    )

    private fun server(options: Map<String, String>) = Server(
        id = "s1",
        type = ServerType.SUBSONIC,
        name = "Home",
        baseUrl = "https://music.example.com",
        username = "isaac-library",
        credential = "app-password",
        deviceId = "d1",
        options = options,
    )

    private val continuo = server(
        mapOf(
            ServerOptions.CONTINUO to "true",
            ServerOptions.CONTINUO_DEVICE_ID to "dev_1",
            ServerOptions.CONTINUO_REFRESH_TOKEN to "rt",
        ),
    )

    @Test
    fun `an identical request reuses the continuo sign-in`() {
        assertSame(continuo, SignInReuse.reusable(SignIn(request, continuo), request.copy()))
    }

    @Test
    fun `nothing to reuse before the first sign-in`() {
        assertNull(SignInReuse.reusable(null, request))
    }

    @Test
    fun `any change to the request signs in again`() {
        val last = SignIn(request, continuo)
        assertNull(SignInReuse.reusable(last, request.copy(password = "other-password")))
        assertNull(SignInReuse.reusable(last, request.copy(baseUrl = "https://other.example.com")))
        assertNull(SignInReuse.reusable(last, request.copy(username = "someone")))
        assertNull(SignInReuse.reusable(last, request.copy(name = "Away")))
        assertNull(SignInReuse.reusable(last, request.copy(options = emptyMap())))
    }

    @Test
    fun `plain subsonic and jellyfin sign-ins are never reused`() {
        val plain = server(mapOf(ServerOptions.SERVER_VERSION to "Navidrome 0.53"))
        assertNull(SignInReuse.reusable(SignIn(request, plain), request))
        val jellyfinRequest = request.copy(type = ServerType.JELLYFIN)
        assertNull(SignInReuse.reusable(SignIn(jellyfinRequest, plain.copy(type = ServerType.JELLYFIN)), jellyfinRequest))
    }
}
