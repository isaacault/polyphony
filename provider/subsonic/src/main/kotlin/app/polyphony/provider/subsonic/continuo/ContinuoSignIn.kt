package app.polyphony.provider.subsonic.continuo

import app.polyphony.core.domain.provider.ProviderException
import app.polyphony.provider.subsonic.internal.await
import kotlinx.coroutines.CancellationException
import kotlinx.serialization.Serializable
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonPrimitive
import okhttp3.Call
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.io.IOException

/**
 * Sign-in for a continuo server (https://github.com/isaacault/continuo), which serves Navidrome's
 * Subsonic API at `/rest` behind its own accounts.
 *
 * A Subsonic client can't sign in to continuo with the account password: token auth sends
 * md5(password + salt), which the server can only check against a plain-text password, and
 * continuo only keeps a bcrypt hash. So the account password is exchanged once, at
 * `POST /v1/auth/login`, for a per-device app password that the Subsonic provider then uses like
 * any other credential. Revoking the device in continuo ends both its requests and its playback.
 */
internal class ContinuoSignIn(private val client: Call.Factory, private val json: Json) {

    @Serializable
    data class Library(val baseUrl: String, val username: String, val password: String)

    @Serializable
    data class Device(val id: String, val name: String? = null)

    @Serializable
    data class Session(
        val refreshToken: String,
        val device: Device,
        val library: Library,
    )

    sealed interface Result {
        data class SignedIn(val session: Session) : Result

        /** The account password was refused (it may be an app password typed by hand). */
        data object WrongPassword : Result
    }

    @Serializable
    private data class LoginBody(val username: String, val password: String, val device: DeviceBody)

    // No default for platform: Json skips default-valued fields unless encodeDefaults is on.
    @Serializable
    private data class DeviceBody(val name: String, val platform: String)

    @Serializable
    private data class ErrorBody(val error: ErrorDetail? = null)

    @Serializable
    private data class ErrorDetail(val code: String? = null, val message: String? = null)

    /**
     * True when [baseUrl] answers `GET /` with continuo's service banner. Anything else (Navidrome's
     * web UI, an error, no answer) is "not continuo", and the caller connects as plain Subsonic.
     */
    suspend fun isContinuo(baseUrl: String): Boolean {
        return try {
            client.newCall(Request.Builder().url("$baseUrl/").get().build()).await().use { response ->
                if (!response.isSuccessful) return false
                val obj = json.parseToJsonElement(response.body.string()) as? JsonObject ?: return false
                obj["service"]?.jsonPrimitive?.content == SERVICE
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: IOException) {
            false
        } catch (e: SerializationException) {
            false
        } catch (e: IllegalArgumentException) {
            false
        }
    }

    suspend fun signIn(baseUrl: String, username: String, password: String, deviceName: String): Result {
        val payload = json.encodeToString(LoginBody.serializer(), LoginBody(username, password, DeviceBody(deviceName, PLATFORM)))
        val request = Request.Builder()
            .url("$baseUrl/v1/auth/login")
            .post(payload.toRequestBody(JSON_MEDIA))
            .build()
        val response = try {
            client.newCall(request).await()
        } catch (e: CancellationException) {
            throw e
        } catch (e: IOException) {
            throw ProviderException.Network(e.message ?: "Couldn't reach continuo", e)
        }
        return response.use {
            val body = it.body.string()
            when {
                it.isSuccessful -> try {
                    Result.SignedIn(json.decodeFromString(Session.serializer(), body))
                } catch (e: SerializationException) {
                    throw ProviderException.Unknown("Unexpected sign-in response from continuo", e)
                }
                it.code == 401 -> Result.WrongPassword
                else -> {
                    val detail = runCatching { json.decodeFromString(ErrorBody.serializer(), body).error?.message }
                        .getOrNull()
                    throw ProviderException.Server(it.code, detail ?: "continuo sign-in failed (HTTP ${it.code})")
                }
            }
        }
    }

    private companion object {
        const val SERVICE = "continuo"
        const val PLATFORM = "android"
        val JSON_MEDIA = "application/json".toMediaType()
    }
}
