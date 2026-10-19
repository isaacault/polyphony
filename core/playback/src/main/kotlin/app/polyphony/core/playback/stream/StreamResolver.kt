package app.polyphony.core.playback.stream

import android.net.Uri
import androidx.media3.datasource.DataSpec
import androidx.media3.datasource.ResolvingDataSource
import app.polyphony.core.domain.repository.DownloadRepository
import app.polyphony.core.domain.repository.LibraryRepository
import app.polyphony.core.domain.repository.SettingsRepository
import app.polyphony.core.domain.transcode.TranscodeProfile
import app.polyphony.core.domain.transcode.TranscodeDecision
import app.polyphony.core.playback.engine.ServerLookup
import app.polyphony.core.playback.engine.TranscodeDecisionEngine
import app.polyphony.core.playback.queue.MediaItemFactory
import app.polyphony.core.playback.queue.QueueStore
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.runBlocking
import java.io.File
import java.io.IOException
import javax.inject.Inject
import javax.inject.Singleton

/** The profile applied to a track when its stream was last opened. */
data class AppliedDecision(val trackId: String, val decision: TranscodeDecision)

/**
 * Turns opaque `polyphony://track/...` URIs into real stream URLs at load time, applying the
 * transcoding decision in force at that moment. Runs on ExoPlayer's loader thread.
 */
@Singleton
class StreamResolver @Inject constructor(
    private val queueStore: QueueStore,
    private val libraryRepository: LibraryRepository,
    private val serverLookup: ServerLookup,
    private val engine: TranscodeDecisionEngine,
    private val downloadRepository: DownloadRepository,
    private val settingsRepository: SettingsRepository,
) : ResolvingDataSource.Resolver {

    private val _lastApplied = MutableStateFlow<AppliedDecision?>(null)
    val lastApplied: StateFlow<AppliedDecision?> = _lastApplied

    @Throws(IOException::class)
    override fun resolveDataSpec(dataSpec: DataSpec): DataSpec {
        val (serverId, trackId) = MediaItemFactory.parseUri(dataSpec.uri) ?: return dataSpec
        val track = queueStore.get(trackId)
            ?: runBlocking { runCatching { libraryRepository.getTrack(trackId) }.getOrNull() }
            ?: throw IOException("Unknown track $trackId")
        resolveDownloaded(trackId, dataSpec)?.let { return it }
        val provider = serverLookup.providerFor(serverId)
            ?: runBlocking { serverLookup.awaitProvider(serverId) }
            ?: throw IOException("Unknown server $serverId")
        val decision = engine.localDecision()
        val request = provider.streamRequest(track, decision.profile)
        _lastApplied.value = AppliedDecision(trackId, decision)
        return dataSpec.buildUpon()
            .setUri(Uri.parse(request.url))
            .setHttpRequestHeaders(dataSpec.httpRequestHeaders + request.headers)
            .setKey("$trackId|${decision.profile.id}")
            .build()
    }

    /**
     * Prefer a downloaded copy when the user asked for it (or is in offline mode). In offline mode a
     * track without a local file is an error rather than a silent network fetch.
     */
    private fun resolveDownloaded(trackId: String, dataSpec: DataSpec): DataSpec? {
        val downloads = runBlocking { runCatching { settingsRepository.current().downloads }.getOrNull() } ?: return null
        if (!downloads.preferDownloadedForPlayback && !downloads.offlineMode) return null
        val local = runBlocking { runCatching { downloadRepository.localFile(trackId) }.getOrNull() }
        val path = local?.filePath
        if (path == null) {
            if (downloads.offlineMode) throw IOException("Not downloaded; offline mode is on")
            return null
        }
        val file = File(path)
        if (!file.exists()) return if (downloads.offlineMode) throw IOException("Downloaded file is missing") else null
        val profile = engine.profileById(local.profileId) ?: TranscodeProfile.ORIGINAL
        _lastApplied.value = AppliedDecision(trackId, TranscodeDecision(profile, TranscodeDecision.Reason.DOWNLOADED))
        return dataSpec.buildUpon()
            .setUri(Uri.fromFile(file))
            .setHttpRequestHeaders(emptyMap())
            .setKey("$trackId|local")
            .build()
    }

    /** Keep the opaque URI in reported events so logs never leak auth tokens. */
    override fun resolveReportedUri(uri: Uri): Uri = uri
}
