package app.polyphony.core.scrobble

import app.polyphony.core.domain.provider.ProviderException
import app.polyphony.core.scrobble.server.ServerReports
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.net.SocketTimeoutException

class ServerReportsTest {

    @Test fun `offline, a server down or busy, a sign-in to renew are tried again`() {
        assertTrue(ServerReports.worthRetrying(SocketTimeoutException()))
        assertTrue(ServerReports.worthRetrying(ProviderException.Network()))
        assertTrue(ServerReports.worthRetrying(ProviderException.Server(503, "down")))
        assertTrue(ServerReports.worthRetrying(ProviderException.Server(429, "busy")))
        assertTrue(ServerReports.worthRetrying(ProviderException.Unauthorized()))
    }

    @Test fun `a song or stream that's gone isn't`() {
        assertFalse(ServerReports.worthRetrying(ProviderException.NotFound()))
        assertFalse(ServerReports.worthRetrying(ProviderException.Server(404, "gone")))
        assertFalse(ServerReports.worthRetrying(ProviderException.Unsupported()))
    }
}
