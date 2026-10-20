package app.polyphony.core.playback.remote

import app.polyphony.core.domain.model.Server
import app.polyphony.core.domain.repository.LibraryRepository
import app.polyphony.core.domain.repository.ProviderRegistry
import app.polyphony.core.domain.transcode.TranscodeProfile
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import java.util.concurrent.TimeUnit
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Fetches the first kilobyte of a real stream through the receiver address, from this phone.
 * It proves the address serves media on the current Wi-Fi; it cannot prove the receiver itself
 * can reach it, but a wrong host, port, scheme or firewall shows up here immediately.
 */
@Singleton
class ReceiverAddressCheck @Inject constructor(
    private val providerRegistry: ProviderRegistry,
    private val libraryRepository: LibraryRepository,
    private val remoteUrls: RemoteUrlResolver,
    baseClient: OkHttpClient,
) {
    sealed interface Outcome {
        data class Reachable(val url: String, val httpStatus: Int, val millis: Long, val viaStream: Boolean) : Outcome
        data class Unreachable(val url: String, val reason: String) : Outcome
    }

    // Plain client on purpose: receivers do not send custom headers or trust private CAs.
    private val client = baseClient.newBuilder()
        .connectTimeout(6, TimeUnit.SECONDS)
        .readTimeout(6, TimeUnit.SECONDS)
        .build()

    suspend fun check(server: Server, overrideBaseUrl: String): Outcome = withContext(Dispatchers.IO) {
        val provider = providerRegistry.forServer(server)
        val track = runCatching { libraryRepository.tracks().first() }.getOrDefault(emptyList())
            .firstOrNull { it.serverId == server.id }
        val (url, viaStream) = if (track != null) {
            remoteUrls.previewForReceiver(provider.streamRequest(track, TranscodeProfile.ORIGINAL).url, server, overrideBaseUrl) to true
        } else {
            val base = overrideBaseUrl.trim().trimEnd('/').let { if (it.contains("://")) it else "http://$it" }
            "$base/" to false
        }
        val started = System.nanoTime()
        runCatching {
            val request = Request.Builder().url(url).header("Range", "bytes=0-1023").build()
            client.newCall(request).execute().use { response ->
                val bytes = response.body?.byteStream()?.let { stream ->
                    val buf = ByteArray(1024); var total = 0
                    while (total < 1024) { val n = stream.read(buf); if (n < 0) break; total += n }
                    total
                } ?: 0
                val ms = (System.nanoTime() - started) / 1_000_000
                when {
                    viaStream && response.isSuccessful && bytes > 0 -> Outcome.Reachable(url, response.code, ms, true)
                    viaStream -> Outcome.Unreachable(url, "HTTP ${response.code}${if (bytes == 0) ", no data" else ""}")
                    else -> Outcome.Reachable(url, response.code, ms, false) // any answer means the host is there
                }
            }
        }.getOrElse { e -> Outcome.Unreachable(url, e.message ?: e.javaClass.simpleName) }
    }
}
