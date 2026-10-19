package app.polyphony.core.downloads.storage

import android.content.Context
import android.os.StatFs
import app.polyphony.core.domain.repository.DownloadLocation
import dagger.hilt.android.qualifiers.ApplicationContext
import java.io.File
import javax.inject.Inject
import javax.inject.Singleton

/**
 * App-private storage for downloaded audio. Both locations need no runtime permission.
 * TODO: migrate existing files when the user switches [DownloadLocation]; today old files stay
 * where they are (absolute paths are stored per download) and new ones go to the new root.
 */
@Singleton
class DownloadStorage @Inject constructor(@ApplicationContext private val context: Context) {

    fun root(location: DownloadLocation): File {
        val dir = when (location) {
            DownloadLocation.INTERNAL -> File(context.filesDir, DIR_NAME)
            DownloadLocation.EXTERNAL -> context.getExternalFilesDir(null)?.let { File(it, DIR_NAME) }
                ?: File(context.filesDir, DIR_NAME)
        }
        if (!dir.exists()) dir.mkdirs()
        return dir
    }

    fun fileFor(location: DownloadLocation, serverId: String, trackId: String, extension: String): File =
        File(root(location), DownloadPaths.relativePath(serverId, trackId, extension)).also { it.parentFile?.mkdirs() }

    fun partFile(file: File): File = File(file.path + PART_SUFFIX)

    /** Atomically promotes a finished `.part` file. */
    fun commit(part: File, target: File): Boolean {
        if (target.exists()) target.delete()
        return part.renameTo(target) || (part.copyTo(target, overwrite = true).exists() && part.delete())
    }

    fun delete(path: String?): Boolean {
        if (path.isNullOrBlank()) return false
        val f = File(path)
        val partDeleted = File(f.path + PART_SUFFIX).let { it.exists() && it.delete() }
        return (f.exists() && f.delete()) || partDeleted
    }

    fun exists(path: String?, expectedSize: Long = 0L): Boolean {
        if (path.isNullOrBlank()) return false
        val f = File(path)
        if (!f.isFile) return false
        return expectedSize <= 0L || f.length() == expectedSize
    }

    fun freeBytes(location: DownloadLocation): Long = runCatching {
        StatFs(root(location).path).availableBytes
    }.getOrDefault(0L)

    /** Bytes used on disk under [location] (walks the tree; call off the main thread). */
    fun usedBytesOnDisk(location: DownloadLocation): Long =
        root(location).walkTopDown().filter { it.isFile }.sumOf { it.length() }

    fun deleteAll(location: DownloadLocation) {
        root(location).deleteRecursively()
    }

    companion object {
        const val DIR_NAME = "downloads"
        const val PART_SUFFIX = ".part"
    }
}
