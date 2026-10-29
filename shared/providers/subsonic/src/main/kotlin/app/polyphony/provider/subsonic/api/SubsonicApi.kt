package app.polyphony.provider.subsonic.api

import app.polyphony.provider.subsonic.dto.ErrorDto
import app.polyphony.provider.subsonic.internal.await
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.decodeFromJsonElement
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import okhttp3.Call
import okhttp3.Request

/**
 * Minimal Subsonic transport: GET `/rest/<endpoint>`, unwrap the `subsonic-response` envelope,
 * raise [SubsonicApiException] on `status: failed`.
 */
internal class SubsonicApi(
    private val callFactory: Call.Factory,
    val json: Json,
    val urls: SubsonicUrlBuilder,
) {
    /** Performs the request and returns the envelope object (status already verified). */
    suspend fun request(endpoint: String, params: Map<String, Any?> = emptyMap()): JsonObject {
        val request = Request.Builder().url(urls.build(endpoint, params)).get().build()
        val body = callFactory.newCall(request).await().use { response ->
            if (!response.isSuccessful) throw SubsonicHttpException(response.code, response.message)
            response.body.string()
        }
        return withContext(Dispatchers.Default) { parseEnvelope(body) }
    }

    /** Decodes `envelope[key]` as [T]. Throws when the key is absent. */
    suspend inline fun <reified T> get(endpoint: String, params: Map<String, Any?> = emptyMap(), key: String): T {
        val envelope = request(endpoint, params)
        val element = envelope[key] ?: throw SubsonicProtocolException("Response is missing '$key'")
        return json.decodeFromJsonElement<T>(element)
    }

    /** Decodes `envelope[key]` as [T], or null when the key is absent. */
    suspend inline fun <reified T> getOrNull(endpoint: String, params: Map<String, Any?> = emptyMap(), key: String): T? {
        val envelope = request(endpoint, params)
        val element = envelope[key] ?: return null
        return json.decodeFromJsonElement<T>(element)
    }

    fun parseEnvelope(body: String): JsonObject {
        val root = try {
            json.parseToJsonElement(body)
        } catch (e: Exception) {
            throw SubsonicProtocolException("Not a Subsonic response (is the address right?)")
        }
        val envelope = (root as? JsonObject)?.get(ENVELOPE)?.let { it as? JsonObject }
            ?: throw SubsonicProtocolException("Not a Subsonic response (is the address right?)")
        val status = envelope["status"]?.jsonPrimitive?.contentOrNull
        if (status != "ok") {
            val error = envelope["error"]?.let { runCatching { json.decodeFromJsonElement<ErrorDto>(it) }.getOrNull() }
            throw SubsonicApiException(error?.code ?: SubsonicApiException.GENERIC, error?.message ?: "Request failed")
        }
        return envelope
    }

    companion object {
        const val ENVELOPE = "subsonic-response"

        fun JsonObject.stringField(name: String): String? = this[name]?.jsonPrimitive?.contentOrNull
        fun JsonObject.booleanField(name: String): Boolean? = this[name]?.jsonPrimitive?.booleanOrNull
    }
}
