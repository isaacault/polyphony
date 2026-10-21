package app.polyphony.diagnostics

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class RedactSecretsTest {
    @Test
    fun `subsonic auth params are redacted but ids survive`() {
        val line = "GET http://10.0.2.2:4533/rest/stream?u=polyphony&t=4f4dbeeaadb15f0b8872a2aea6a57871&s=bfa9eeffcdd9d111&v=1.16.1&c=Polyphony&id=2y3h3kcYqanv2F5r9WKZj5"
        val out = redactSecrets(line)
        assertFalse(out.contains("4f4dbeeaadb15f0b"))
        assertFalse(out.contains("bfa9eeffcdd9d111"))
        assertFalse(out.contains("u=polyphony"))
        assertTrue(out.contains("id=2y3h3kcYqanv2F5r9WKZj5"))
        assertTrue(out.contains("v=1.16.1"))
        assertTrue(out.contains("t=<redacted>&s=<redacted>"))
    }

    @Test
    fun `jellyfin api key and authorization header are redacted`() {
        val out = redactSecrets("url: https://jf.example/Audio/x/universal?api_key=abc123DEF&DeviceId=d1\nAuthorization: MediaBrowser Token=\"secret\"")
        assertTrue(out.contains("api_key=<redacted>&DeviceId=d1"))
        assertTrue(out.contains("Authorization: <redacted>"))
        assertFalse(out.contains("secret"))
    }

    @Test
    fun `json credential fields are redacted`() {
        val out = redactSecrets("{\"credential\": \"hunter2\", \"username\": \"isaac\"}")
        assertEquals("{\"credential\": \"<redacted>\", \"username\": \"isaac\"}", out)
    }

    @Test
    fun `continuo sign-in responses are redacted`() {
        val out = redactSecrets("{\"accessToken\": \"eyJ\", \"refreshToken\": \"crt_abc\", \"password\": \"app-secret\", \"username\": \"isaac\"}")
        assertFalse(out.contains("crt_abc"))
        assertFalse(out.contains("app-secret"))
        assertFalse(out.contains("eyJ"))
        assertTrue(out.contains("\"username\": \"isaac\""))
    }

    @Test
    fun `ordinary text is untouched`() {
        val text = "decision: Original quality reason=DEFAULT host=10.0.2.2 t=now"
        assertEquals(text, redactSecrets(text))
    }
}
