package app.polyphony.core.downloads.db

import androidx.room.Database
import androidx.room.RoomDatabase

/**
 * Separate from the library cache on purpose: a library re-sync (or destructive migration of the
 * library DB) must never drop files the user downloaded.
 */
@Database(
    entities = [DownloadEntity::class, DownloadRuleEntity::class],
    version = 1,
    exportSchema = false,
)
abstract class DownloadsDatabase : RoomDatabase() {
    abstract fun downloadDao(): DownloadDao
    abstract fun downloadRuleDao(): DownloadRuleDao

    companion object {
        const val NAME = "polyphony-downloads.db"
    }
}
