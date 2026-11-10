package app.polyphony.core.scrobble.http

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody

/** One request to a scrobbling service. Headers may carry a secret: never log them. */
data class HttpRequest(
    val method: String,
    val url: String,
    val headers: Map<String, String> = emptyMap(),
    val body: String? = null,
    val contentType: String? = null,
) {
    override fun toString(): String = "HttpRequest($method $url)"
}

data class HttpResponse(val code: Int, val body: String, val headers: Map<String, String> = emptyMap()) {
    /** A header, by name in any case. */
    fun header(name: String): String? = headers.entries.firstOrNull { it.key.equals(name, ignoreCase = true) }?.value
}

/** The clients' only way out to the network, so their tests can answer with a fake. Throws IOException when offline. */
fun interface HttpTransport {
    suspend fun execute(request: HttpRequest): HttpResponse
}

class OkHttpTransport(private val client: OkHttpClient) : HttpTransport {
    override suspend fun execute(request: HttpRequest): HttpResponse = withContext(Dispatchers.IO) {
        val body = request.body?.toRequestBody(request.contentType?.toMediaType())
        val builder = Request.Builder().url(request.url).method(request.method, body)
        request.headers.forEach { (k, v) -> builder.header(k, v) }
        client.newCall(builder.build()).execute().use { response ->
            HttpResponse(
                code = response.code,
                body = response.body.string(),
                headers = response.headers.toMap(),
            )
        }
    }

    private fun okhttp3.Headers.toMap(): Map<String, String> = names().associateWith { get(it).orEmpty() }
}
