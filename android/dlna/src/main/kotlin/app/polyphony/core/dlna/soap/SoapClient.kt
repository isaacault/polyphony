package app.polyphony.core.dlna.soap

import app.polyphony.core.dlna.upnp.UpnpService
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.io.IOException
import java.util.concurrent.TimeUnit

/** Minimal SOAP action invoker for UPnP control URLs. Retries once on I/O errors (not on faults). */
class SoapClient(baseClient: OkHttpClient) {
    private val client = baseClient.newBuilder()
        .connectTimeout(5, TimeUnit.SECONDS)
        .readTimeout(10, TimeUnit.SECONDS)
        .writeTimeout(10, TimeUnit.SECONDS)
        .retryOnConnectionFailure(false)
        .build()

    suspend fun call(
        service: UpnpService,
        action: String,
        args: Map<String, String> = emptyMap(),
    ): Map<String, String> = call(service.controlUrl, service.serviceType, action, args)

    suspend fun call(
        controlUrl: String,
        serviceType: String,
        action: String,
        args: Map<String, String> = emptyMap(),
    ): Map<String, String> = withContext(Dispatchers.IO) {
        val envelope = SoapEnvelope.build(serviceType, action, args)
        var lastIo: IOException? = null
        repeat(2) { attempt ->
            try {
                return@withContext execute(controlUrl, serviceType, action, envelope)
            } catch (e: IOException) {
                lastIo = e
                if (attempt == 0) return@repeat
            }
        }
        throw DlnaException(0, "Renderer unreachable: ${lastIo?.message}", lastIo)
    }

    private suspend fun execute(controlUrl: String, serviceType: String, action: String, envelope: String): Map<String, String> {
        val request = Request.Builder()
            .url(controlUrl)
            .header("SOAPACTION", "\"$serviceType#$action\"")
            .header("Content-Type", "text/xml; charset=\"utf-8\"")
            .header("User-Agent", "Android UPnP/1.1 Polyphony/0.1")
            .header("Connection", "close")
            .post(envelope.toRequestBody(TEXT_XML))
            .build()
        client.newCall(request).await().use { response ->
            val body = runCatching { response.body.string() }.getOrDefault("")
            if (!response.isSuccessful) {
                SoapEnvelope.parseFault(body)?.let { throw it }
                throw DlnaException(response.code, "HTTP ${response.code} for $action")
            }
            return SoapEnvelope.parseResponse(body, action)
        }
    }

    private companion object {
        val TEXT_XML = "text/xml; charset=\"utf-8\"".toMediaType()
    }
}
