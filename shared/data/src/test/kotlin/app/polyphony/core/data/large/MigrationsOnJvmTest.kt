package app.polyphony.core.data.large

import android.content.ContextWrapper
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.sqlite.db.SupportSQLiteDatabase
import app.polyphony.core.data.db.Migrations
import app.polyphony.core.data.db.PolyphonyDatabase
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * Every schema a release shipped (the exported JSON under `schemas/`), upgraded with the app's
 * own [Migrations] and then checked by Room against today's entities, as an upgraded phone's
 * first start does. A migration that leaves a column, index, FTS table or trigger different from
 * what the entities declare fails here rather than crashing every upgraded install
 * (MigrationsChainTest only checks that the steps exist). The JVM twin of the instrumented
 * MigrationsTest, so it runs in every CI run, not only on an emulator.
 */
class MigrationsOnJvmTest {
    @Test
    fun `every exported schema upgrades to the current one`() {
        for (from in 1 until PolyphonyDatabase.VERSION) {
            val db = Room.inMemoryDatabaseBuilder(EmptyContext, PolyphonyDatabase::class.java)
                .openHelperFactory(BundledOpenHelperFactory { seed(it, from) })
                .addMigrations(*Migrations.ALL)
                // Room asks Android which journal mode suits the device unless told (TestDatabase).
                .setJournalMode(RoomDatabase.JournalMode.TRUNCATE)
                .allowMainThreadQueries()
                .build()
            try {
                // Opening runs the migrations from `from`, then Room's check of the result.
                val indexes = db.openHelper.writableDatabase
                    .query("SELECT name FROM sqlite_master WHERE type = 'index' AND tbl_name = 'tracks'")
                    .use { c -> buildList { while (c.moveToNext()) add(c.getString(0)) } }
                assertTrue("from v$from: $indexes", "index_tracks_serverId_durationMs" in indexes)
                assertTrue("from v$from: $indexes", "index_tracks_serverId_rating" in indexes)
            } finally {
                db.close()
            }
        }
    }

    /** The database exactly as Room created it at [version], from that version's exported schema. */
    private fun seed(db: SupportSQLiteDatabase, version: Int) {
        val schema = Json.parseToJsonElement(File("$SCHEMAS/$version.json").readText())
            .jsonObject.getValue("database").jsonObject
        for (entity in schema.getValue("entities").jsonArray.map { it.jsonObject }) {
            val table = entity.text("tableName")
            db.execSQL(entity.text("createSql").replace(TABLE_NAME, table))
            entity["indices"]?.jsonArray?.forEach { db.execSQL(it.jsonObject.text("createSql").replace(TABLE_NAME, table)) }
            entity["contentSyncTriggers"]?.jsonArray?.forEach { db.execSQL(it.jsonPrimitive.content) }
        }
        schema["views"]?.jsonArray?.forEach {
            val view = it.jsonObject
            db.execSQL(view.text("createSql").replace(VIEW_NAME, view.text("viewName")))
        }
        schema.getValue("setupQueries").jsonArray.forEach { db.execSQL(it.jsonPrimitive.content) }
        db.version = version
    }

    private fun JsonObject.text(key: String): String = getValue(key).jsonPrimitive.content

    private object EmptyContext : ContextWrapper(null)

    private companion object {
        const val SCHEMAS = "schemas/app.polyphony.core.data.db.PolyphonyDatabase"
        const val TABLE_NAME = "\${TABLE_NAME}"
        const val VIEW_NAME = "\${VIEW_NAME}"
    }
}
