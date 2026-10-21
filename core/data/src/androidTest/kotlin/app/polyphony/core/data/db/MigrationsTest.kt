package app.polyphony.core.data.db

import androidx.room.testing.MigrationTestHelper
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Migrates a real database from the oldest exported schema to the current one and lets Room
 * validate the result against `core/data/schemas`. Needs a device or emulator:
 * `./gradlew :core:data:connectedDebugAndroidTest`.
 */
@RunWith(AndroidJUnit4::class)
class MigrationsTest {

    @get:Rule
    val helper = MigrationTestHelper(InstrumentationRegistry.getInstrumentation(), PolyphonyDatabase::class.java)

    @Test
    fun migratesFromV1KeepingServers() {
        helper.createDatabase(DB, 1).use { db ->
            db.execSQL(
                "INSERT INTO servers (id, type, name, baseUrl, username, credential, userId, deviceId, optionsJson, createdAt) " +
                    "VALUES ('s1', 'SUBSONIC', 'Home', 'http://music.local:4533', 'me', 'secret', NULL, 'dev', '{}', 1)",
            )
        }
        helper.runMigrationsAndValidate(DB, PolyphonyDatabase.VERSION, true, *Migrations.ALL).use { db ->
            db.query("SELECT name FROM servers").use { c ->
                assertEquals(1, c.count)
                c.moveToFirst()
                assertEquals("Home", c.getString(0))
            }
            db.query("SELECT COUNT(*) FROM play_contexts").use { c ->
                c.moveToFirst()
                assertEquals(0, c.getInt(0))
            }
        }
    }

    @Test
    fun migratesFromV2BackfillingArticleFreeSortKeys() {
        helper.createDatabase(DB, 2).use { db ->
            db.execSQL(
                "INSERT INTO artists (id, serverId, name, sortName, albumCount, artworkId, artworkUrl, starred, rating, " +
                    "musicBrainzId, biography, syncGeneration) VALUES ('a1', 's1', 'The Strokes', '', 0, NULL, NULL, 0, 0, NULL, NULL, 0)",
            )
        }
        helper.runMigrationsAndValidate(DB, 3, true, *Migrations.ALL).use { db ->
            db.query("SELECT nameSortKey FROM artists").use { c ->
                c.moveToFirst()
                assertEquals("Strokes", c.getString(0))
            }
        }
    }

    private companion object {
        const val DB = "migration-test.db"
    }
}
