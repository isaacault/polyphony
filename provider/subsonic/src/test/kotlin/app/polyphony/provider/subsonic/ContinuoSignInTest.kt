package app.polyphony.provider.subsonic

import app.polyphony.core.domain.provider.ProviderException
import app.polyphony.provider.subsonic.continuo.ContinuoSignIn
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import okhttp3.Interceptor
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Request
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody
import okio.Buffer
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import java.io.IOException

class ContinuoSignInTest {

    private class Fake {
        val routes = mutableMapOf<String, () -> Pair<Int, String>>()
        val requests = mutableListOf<Pair<Request, String?>>()
        var offline = false

        val client: OkHttpClient = OkHttpClient.Builder()
            .addInterceptor(Interceptor { chain ->
                val req = chain.request()
                val body = req.body?.let { b -> Buffer().also { b.writeTo(it) }.readUtf8() }
                requests += req to body
                if (offline) throw IOException("unreachable")
                val (status, text) = routes["${req.method} ${req.url.encodedPath}"]?.invoke() ?: (404 to "")
                Response.Builder()
                    .request(req)
                    .protocol(Protocol.HTTP_1_1)
                    .code(status)
                    .message("x")
                    .body(text.toResponseBody("application/json".toMediaType()))
                    .build()
            })
            .build()
    }

    private val base = "https://music-api.example.com"
    private val session = """{"accessToken":"a","expiresIn":3600,"refreshToken":"crt_r",
        "user":{"id":2,"username":"isaac","role":"user"},
        "device":{"id":"dev_1","name":"Google Pixel 8"},
        "library":{"type":"subsonic","baseUrl":"$base","username":"isaac","password":"app-secret"}}"""

    @Test
    fun `recognises continuo by its banner`() = runTest {
        val fake = Fake()
        fake.routes["GET /"] = { 200 to """{"service":"continuo","version":"0.1.0","health":"/v1/health"}""" }
        assertTrue(ContinuoSignIn(fake.client, testJson).isContinuo(base))
    }

    @Test
    fun `anything else is plain Subsonic`() = runTest {
        val fake = Fake()
        val signIn = ContinuoSignIn(fake.client, testJson)
        fake.routes["GET /"] = { 200 to "<html>Navidrome</html>" }
        assertFalse(signIn.isContinuo(base))
        fake.routes["GET /"] = { 200 to """{"service":"something-else"}""" }
        assertFalse(signIn.isContinuo(base))
        fake.routes["GET /"] = { 302 to "" }
        assertFalse(signIn.isContinuo(base))
        fake.offline = true
        assertFalse(signIn.isContinuo(base))
    }

    @Test
    fun `sign-in exchanges the account password for the device's app password`() = runTest {
        val fake = Fake()
        fake.routes["POST /v1/auth/login"] = { 200 to session }
        val result = ContinuoSignIn(fake.client, testJson).signIn(base, "isaac", "hunter22", "Google Pixel 8")

        val signed = result as ContinuoSignIn.Result.SignedIn
        assertEquals("app-secret", signed.session.library.password)
        assertEquals("isaac", signed.session.library.username)
        assertEquals("dev_1", signed.session.device.id)
        assertEquals("crt_r", signed.session.refreshToken)

        val sent = testJson.parseToJsonElement(fake.requests.single().second!!).jsonObject
        assertEquals("isaac", sent["username"]!!.jsonPrimitive.content)
        assertEquals("hunter22", sent["password"]!!.jsonPrimitive.content)
        val device = sent["device"]!!.jsonObject
        assertEquals("Google Pixel 8", device["name"]!!.jsonPrimitive.content)
        assertEquals("android", device["platform"]!!.jsonPrimitive.content)
    }

    @Test
    fun `a refused password is reported, not thrown`() = runTest {
        val fake = Fake()
        fake.routes["POST /v1/auth/login"] = {
            401 to """{"error":{"code":"invalid_credentials","message":"Invalid username or password.","retryable":false}}"""
        }
        val result = ContinuoSignIn(fake.client, testJson).signIn(base, "isaac", "nope", "Pixel")
        assertEquals(ContinuoSignIn.Result.WrongPassword, result)
    }

    @Test
    fun `other failures carry continuo's message`() = runTest {
        val fake = Fake()
        fake.routes["POST /v1/auth/login"] = {
            429 to """{"error":{"code":"rate_limited","message":"Too many failed sign-ins.","retryable":true}}"""
        }
        try {
            ContinuoSignIn(fake.client, testJson).signIn(base, "isaac", "x", "Pixel")
            fail("expected a ProviderException")
        } catch (e: ProviderException.Server) {
            assertEquals(429, e.code)
            assertEquals("Too many failed sign-ins.", e.message)
        }
    }

    @Test
    fun `unreachable server is a network error`() = runTest {
        val fake = Fake().apply { offline = true }
        try {
            ContinuoSignIn(fake.client, testJson).signIn(base, "isaac", "x", "Pixel")
            fail("expected a ProviderException")
        } catch (e: ProviderException.Network) {
            assertTrue(e.cause is IOException)
        }
    }
}
