package app.polyphony.diagnostics

import android.content.Context
import android.os.Build
import java.io.File
import java.io.PrintWriter
import java.io.StringWriter
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Writes uncaught exceptions to app-private storage so they can be included in a diagnostics
 * export. There is deliberately no network reporting. Keeps the last [MAX_FILES] crashes.
 */
object CrashRecorder {
    private const val MAX_FILES = 5

    fun dir(context: Context): File = File(context.filesDir, "diagnostics/crashes").apply { mkdirs() }

    fun install(context: Context) {
        val appContext = context.applicationContext
        val previous = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { thread, throwable ->
            runCatching { write(appContext, thread, throwable) }
            previous?.uncaughtException(thread, throwable)
        }
    }

    fun recent(context: Context): List<File> =
        dir(context).listFiles()?.sortedByDescending { it.lastModified() }.orEmpty()

    private fun write(context: Context, thread: Thread, throwable: Throwable) {
        val dir = dir(context)
        val stamp = SimpleDateFormat("yyyyMMdd-HHmmss", Locale.US).format(Date())
        val trace = StringWriter().also { throwable.printStackTrace(PrintWriter(it)) }.toString()
        File(dir, "crash-$stamp.txt").writeText(
            buildString {
                appendLine("Polyphony crash $stamp")
                appendLine("Thread: ${thread.name}")
                appendLine("Device: ${Build.MANUFACTURER} ${Build.MODEL}, Android ${Build.VERSION.RELEASE} (SDK ${Build.VERSION.SDK_INT})")
                appendLine()
                append(trace)
            },
        )
        dir.listFiles()?.sortedByDescending { it.lastModified() }?.drop(MAX_FILES)?.forEach { it.delete() }
    }
}
