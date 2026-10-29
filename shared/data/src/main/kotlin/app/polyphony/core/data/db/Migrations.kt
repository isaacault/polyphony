package app.polyphony.core.data.db

import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase

/**
 * Hand-written migrations. The schema is not exported, so each statement must match what Room
 * generates for the entity exactly (column order, affinity, NOT NULL, index name); copy it from
 * `createAllTables` in the generated `PolyphonyDatabase_Impl` when adding one.
 */
object Migrations {
    /** v2: `play_contexts` for Home's "Continue listening" shelf. */
    private val MIGRATION_1_2 = object : Migration(1, 2) {
        override fun migrate(db: SupportSQLiteDatabase) {
            db.execSQL(
                "CREATE TABLE IF NOT EXISTS `play_contexts` (`key` TEXT NOT NULL, `serverId` TEXT NOT NULL, " +
                    "`kind` TEXT NOT NULL, `contextId` TEXT NOT NULL, `title` TEXT NOT NULL, `subtitle` TEXT, " +
                    "`artworkUrl` TEXT, `trackId` TEXT, `trackTitle` TEXT, `trackIndex` INTEGER NOT NULL, " +
                    "`positionMs` INTEGER NOT NULL, `shuffle` INTEGER NOT NULL, `updatedAt` INTEGER NOT NULL, " +
                    "PRIMARY KEY(`key`))",
            )
            db.execSQL(
                "CREATE INDEX IF NOT EXISTS `index_play_contexts_serverId_updatedAt` ON `play_contexts` (`serverId`, `updatedAt`)",
            )
        }
    }

    /**
     * v3: stored A–Z sort keys (leading "The"/"A"/"An" dropped, NOCASE) on artists, albums and
     * tracks, plus indexes for the Songs sorts. Existing rows are backfilled with the SQL twin of
     * `sortKey`; rows written from then on get their keys from the entity defaults.
     */
    private val MIGRATION_2_3 = object : Migration(2, 3) {
        override fun migrate(db: SupportSQLiteDatabase) {
            val columns = mapOf(
                "artists" to listOf("nameSortKey" to "COALESCE(NULLIF(trim(sortName), ''), name)"),
                "albums" to listOf(
                    "nameSortKey" to "COALESCE(NULLIF(trim(sortName), ''), name)",
                    "artistSortKey" to "COALESCE(artistName, '')",
                ),
                "tracks" to listOf(
                    "titleSortKey" to "title",
                    "artistSortKey" to "COALESCE(artistName, '')",
                    "albumSortKey" to "COALESCE(albumName, '')",
                ),
            )
            for ((table, keys) in columns) {
                for ((column, source) in keys) {
                    db.execSQL("ALTER TABLE `$table` ADD COLUMN `$column` TEXT NOT NULL COLLATE NOCASE DEFAULT ''")
                    db.execSQL("UPDATE `$table` SET `$column` = ${SortQueries.sortable(source)}")
                }
            }
            for (column in listOf("titleSortKey", "artistSortKey", "albumSortKey")) {
                db.execSQL("CREATE INDEX IF NOT EXISTS `index_tracks_serverId_$column` ON `tracks` (`serverId`, `$column`)")
            }
        }
    }

    val ALL: Array<Migration> = arrayOf(MIGRATION_1_2, MIGRATION_2_3)
}
