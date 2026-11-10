package app.polyphony.core.scrobble

import app.polyphony.core.scrobble.http.HttpRequest
import app.polyphony.core.scrobble.http.HttpResponse
import app.polyphony.core.scrobble.http.HttpTransport
import java.io.IOException
import java.net.URLDecoder

/** Answers each request with the next queued response (or throws, to play offline) and keeps what was sent. */
class FakeHttp(vararg responses: HttpResponse) : HttpTransport {
    private val answers = ArrayDeque(responses.toList())
    val requests = mutableListOf<HttpRequest>()
    var offline = false

    fun answer(code: Int, body: String, headers: Map<String, String> = emptyMap()) {
        answers.addLast(HttpResponse(code, body, headers))
    }

    override suspend fun execute(request: HttpRequest): HttpResponse {
        requests += request
        if (offline) throw IOException("offline")
        return answers.removeFirstOrNull() ?: error("no answer queued for $request")
    }
}

/** A form-encoded body as its fields, in order. */
fun formFields(body: String?): List<Pair<String, String>> = body.orEmpty().split("&").filter { it.isNotEmpty() }.map {
    val (k, v) = it.split("=", limit = 2)
    URLDecoder.decode(k, "UTF-8") to URLDecoder.decode(v, "UTF-8")
}
