package app.polyphony.core.dlna.upnp

import app.polyphony.core.dlna.soap.DlnaException
import app.polyphony.core.dlna.soap.await
import okhttp3.OkHttpClient
import okhttp3.Request
import java.util.concurrent.TimeUnit

/** Fetches and parses `LOCATION` documents announced over SSDP. */
class DeviceDescriptionClient(baseClient: OkHttpClient) {
    private val client = baseClient.newBuilder()
        .connectTimeout(5, TimeUnit.SECONDS)
        .readTimeout(8, TimeUnit.SECONDS)
        .followRedirects(true)
        .build()

    /** @throws DlnaException with a reason that names what the document contained when it cannot be used. */
    suspend fun fetch(location: String): UpnpDevice {
        val request = Request.Builder()
            .url(location)
            .header("User-Agent", "Android UPnP/1.1 Polyphony/0.1")
            .header("Accept", "text/xml, application/xml, */*")
            .get()
            .build()
        val response = client.newCall(request).await()
        response.use {
            if (!it.isSuccessful) throw DlnaException(it.code, "Device description HTTP ${it.code}")
            val body = it.body.string()
            return DeviceDescriptionParser.parseOrExplain(body, location).getOrElse { reason ->
                val preview = body.take(160).replace(Regex("\\s+"), " ")
                throw DlnaException(0, "${reason.message}; ${body.length} bytes starting \"$preview\"", reason)
            }
        }
    }
}
