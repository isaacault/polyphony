package app.polyphony.diagnostics

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Process
import androidx.core.content.FileProvider
import app.polyphony.BuildConfig
import app.polyphony.core.domain.playback.CastController
import app.polyphony.core.domain.playback.PlaybackController
import app.polyphony.core.domain.repository.ServerRepository
import app.polyphony.core.domain.repository.SettingsRepository
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withContext
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import javax.inject.Inject

/**
 * Builds a shareable text report: app and device info, servers (hosts only), settings, the live
 * playback and Cast state, recent crashes, and this process's recent logcat output. Credentials,
 * tokens and auth query parameters are redacted.
 */
class DiagnosticsExporter @Inject constructor(
    @ApplicationContext private val context: Context,
    private val settingsRepository: SettingsRepository,
    private val serverRepository: ServerRepository,
    private val playbackController: PlaybackController,
    private val castController: CastController,
) {
    private val json = Json { prettyPrint = true; encodeDefaults = true }

    suspend fun export(): Uri = withContext(Dispatchers.IO) {
        val dir = File(context.cacheDir, "diagnostics").apply { mkdirs() }
        dir.listFiles()?.forEach { it.delete() }
        val stamp = SimpleDateFormat("yyyyMMdd-HHmmss", Locale.US).format(Date())
        val file = File(dir, "polyphony-diagnostics-$stamp.txt")
        file.writeText(redact(buildReport()))
        FileProvider.getUriForFile(context, "${context.packageName}.files", file)
    }

    fun shareIntent(uri: Uri): Intent {
        val send = Intent(Intent.ACTION_SEND).apply {
            type = "text/plain"
            putExtra(Intent.EXTRA_STREAM, uri)
            putExtra(Intent.EXTRA_SUBJECT, "Polyphony diagnostics")
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        return Intent.createChooser(send, "Share diagnostics").addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
    }

    private suspend fun buildReport(): String = buildString {
        appendLine("Polyphony diagnostics ${SimpleDateFormat("yyyy-MM-dd HH:mm:ss Z", Locale.US).format(Date())}")
        appendLine("App: ${BuildConfig.VERSION_NAME} (${BuildConfig.VERSION_CODE}) ${context.packageName}${if (BuildConfig.DEBUG) " debug" else ""}")
        appendLine("Device: ${Build.MANUFACTURER} ${Build.MODEL} (${Build.DEVICE}), Android ${Build.VERSION.RELEASE} (SDK ${Build.VERSION.SDK_INT}), locale ${Locale.getDefault()}")
        appendLine()

        appendLine("== Servers ==")
        val active = runCatching { serverRepository.getActive() }.getOrNull()
        runCatching { serverRepository.servers.first() }.getOrDefault(emptyList()).forEach { s ->
            appendLine("- ${s.type} ${s.displayHost} name=\"${s.name}\" user=${s.username} options=${s.options.keys}${if (s.id == active?.id) " (active)" else ""}")
        }
        appendLine()

        val settings = runCatching { settingsRepository.current() }.getOrNull()
        if (settings != null) {
            appendLine("== Transcoding policy =="); appendLine(json.encodeToString(settings.transcode))
            appendLine("== Playback settings =="); appendLine(json.encodeToString(settings.playback))
            appendLine("== Downloads settings =="); appendLine(json.encodeToString(settings.downloads))
            appendLine("== Appearance =="); appendLine(json.encodeToString(settings.appearance))
            appendLine()
        }

        appendLine("== Playback state ==")
        val p = playbackController.state.value
        val t = p.currentTrack
        appendLine("track: ${t?.title} / ${t?.displayArtist} id=${t?.id} container=${t?.container} ${t?.bitrateKbps}kbps ${t?.sampleRateHz}Hz/${t?.bitDepth}bit")
        appendLine("queue: ${p.currentIndex + 1}/${p.queue.size} playing=${p.isPlaying} buffering=${p.isBuffering} shuffle=${p.shuffleEnabled} repeat=${p.repeatMode} speed=${p.playbackSpeed}")
        appendLine("output: ${p.outputTarget.key} \"${p.outputTarget.displayName}\"")
        appendLine("decision: ${p.transcode?.profile?.name} (${p.transcode?.profile?.summary}) reason=${p.transcode?.reason} cappedByLatency=${p.transcode?.cappedByLatency} degraded=${p.latencyDegraded}")
        p.error?.let { appendLine("error: $it") }
        val c = castController.state.value
        appendLine("cast: available=${c.isAvailable} connecting=${c.isConnecting} connected=${c.isConnected} target=${c.target?.key} \"${c.target?.displayName}\" volume=${c.volume}")
        appendLine()

        val crashes = CrashRecorder.recent(context)
        appendLine("== Recent crashes (${crashes.size}) ==")
        crashes.take(3).forEach { f ->
            appendLine("--- ${f.name}")
            appendLine(runCatching { f.readText() }.getOrDefault("<unreadable>").take(12_000))
        }
        appendLine()

        appendLine("== Logcat (this process, last 2500 lines) ==")
        appendLine(readLogcat())
    }

    private fun readLogcat(): String = runCatching {
        val process = ProcessBuilder("logcat", "-d", "-v", "threadtime", "-t", "2500", "--pid=${Process.myPid()}")
            .redirectErrorStream(true)
            .start()
        process.inputStream.bufferedReader().use { it.readText() }
    }.getOrElse { "<logcat unavailable: ${it.message}>" }

    private fun redact(text: String): String = text
        .replace(Regex("(?i)([?&](?:t|s|p|u|api_key|apikey|token|access_token|password|x-emby-token)=)[^&\\s\"'<>]+"), "$1<redacted>")
        .replace(Regex("(?i)(authorization:\\s*)[^\\n]+"), "$1<redacted>")
        .replace(Regex("(?i)(token=\")[^\"]+(\")"), "$1<redacted>$2")
        .replace(Regex("(?i)(\"(?:credential|password|accessToken|storePassword)\"\\s*:\\s*\")[^\"]*(\")"), "$1<redacted>$2")
}
