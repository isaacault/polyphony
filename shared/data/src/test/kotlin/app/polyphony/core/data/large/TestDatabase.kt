package app.polyphony.core.data.large

import android.content.ContextWrapper
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.sqlite.db.SimpleSQLiteQuery
import app.polyphony.core.data.db.PolyphonyDatabase
import kotlinx.coroutines.runBlocking

/**
 * The app's Room database in memory on the JVM, with no emulator and no Robolectric: Room runs
 * its generated DAOs, FTS tables and triggers on the bundled SQLite ([BundledOpenHelperFactory]).
 * An in-memory helper never asks the Context for anything, so an empty one is enough.
 *
 * The bundled SQLite is newer than most phones' (3.4x vs 3.18 on Android 8), so a query plan seen
 * here is what a recent device does; index use, the thing these tests guard, is the same.
 */
object TestDatabase {
    fun inMemory(): PolyphonyDatabase =
        Room.inMemoryDatabaseBuilder(EmptyContext, PolyphonyDatabase::class.java)
            .openHelperFactory(BundledOpenHelperFactory())
            .setJournalMode(RoomDatabase.JournalMode.TRUNCATE)
            // Room asks Looper whether this is the main thread unless told not to check.
            .allowMainThreadQueries()
            .build()

    private object EmptyContext : ContextWrapper(null)
}

/** `EXPLAIN QUERY PLAN` for [query], one detail line per step ("SEARCH tracks USING INDEX …"). */
fun PolyphonyDatabase.queryPlan(query: SimpleSQLiteQuery): List<String> {
    val plan = SimpleSQLiteQuery("EXPLAIN QUERY PLAN ${query.sql}", query.args())
    return openHelper.readableDatabase.query(plan).use { cursor ->
        buildList { while (cursor.moveToNext()) add(cursor.getString(3)) }
    }
}

fun PolyphonyDatabase.queryPlan(sql: String, vararg args: Any?): List<String> = queryPlan(SimpleSQLiteQuery(sql, args))

/** One integer from [sql] (a COUNT). */
fun PolyphonyDatabase.longValue(sql: String, vararg args: Any?): Long =
    openHelper.readableDatabase.query(SimpleSQLiteQuery(sql, args)).use { cursor ->
        cursor.moveToFirst()
        cursor.getLong(0)
    }

/** A [SimpleSQLiteQuery]'s bound arguments, read back by binding it to a recorder. */
private fun SimpleSQLiteQuery.args(): Array<Any?> {
    val args = arrayOfNulls<Any?>(argCount)
    bindTo(object : androidx.sqlite.db.SupportSQLiteProgram {
        override fun bindNull(index: Int) { args[index - 1] = null }
        override fun bindLong(index: Int, value: Long) { args[index - 1] = value }
        override fun bindDouble(index: Int, value: Double) { args[index - 1] = value }
        override fun bindString(index: Int, value: String) { args[index - 1] = value }
        override fun bindBlob(index: Int, value: ByteArray) { args[index - 1] = value }
        override fun clearBindings() = Unit
        override fun close() = Unit
    })
    return args
}

/**
 * A full scan of [table]: a `SCAN` line that walks the table itself rather than one of its
 * indexes. (`SCAN t USING COVERING INDEX …` only reads the index and is fine.)
 */
fun List<String>.scansTable(table: String): Boolean =
    any { line -> (line == "SCAN $table" || line.startsWith("SCAN $table ")) && "INDEX" !in line }

/** The plan sorts the rows itself (a temp B-tree) instead of reading them in index order. */
fun List<String>.sortsWholeResult(): Boolean = any { it == "USE TEMP B-TREE FOR ORDER BY" }

/**
 * Wall-clock timings, printed (they land in the JUnit XML report) rather than asserted: the CI
 * host is shared, so a threshold would be flaky. Each block runs [warmup] times, then the median
 * of [runs] is reported.
 */
object Timings {
    fun <T> measure(label: String, runs: Int = 5, warmup: Int = 1, block: suspend () -> T): T = runBlocking {
        repeat(warmup) { block() }
        var result: T? = null
        val samples = LongArray(runs) {
            val start = System.nanoTime()
            result = block()
            System.nanoTime() - start
        }
        samples.sort()
        report(label, samples[runs / 2])
        @Suppress("UNCHECKED_CAST")
        result as T
    }

    /** Times [block] once (a sync, which changes the database). */
    fun <T> once(label: String, block: suspend () -> T): T = runBlocking {
        val start = System.nanoTime()
        val result = block()
        report(label, System.nanoTime() - start)
        result
    }

    private fun report(label: String, nanos: Long) {
        println("PERF %-60s %9.1f ms".format(label, nanos / 1e6))
    }
}
