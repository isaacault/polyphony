package app.polyphony.core.data.large

import android.content.ContentResolver
import android.content.ContentValues
import android.database.CharArrayBuffer
import android.database.ContentObserver
import android.database.Cursor
import android.database.DataSetObserver
import android.database.sqlite.SQLiteConstraintException
import android.database.sqlite.SQLiteException
import android.database.sqlite.SQLiteTransactionListener
import android.net.Uri
import android.os.Bundle
import android.os.CancellationSignal
import androidx.sqlite.SQLITE_DATA_BLOB
import androidx.sqlite.SQLITE_DATA_FLOAT
import androidx.sqlite.SQLITE_DATA_INTEGER
import androidx.sqlite.SQLITE_DATA_TEXT
import androidx.sqlite.SQLiteConnection
import androidx.sqlite.SQLiteStatement
import androidx.sqlite.db.SimpleSQLiteQuery
import androidx.sqlite.db.SupportSQLiteDatabase
import androidx.sqlite.db.SupportSQLiteOpenHelper
import androidx.sqlite.db.SupportSQLiteProgram
import androidx.sqlite.db.SupportSQLiteQuery
import androidx.sqlite.db.SupportSQLiteStatement
import androidx.sqlite.driver.bundled.BundledSQLiteDriver
import java.util.Locale
import java.util.concurrent.locks.ReentrantLock
import kotlin.concurrent.withLock

/**
 * `SupportSQLiteOpenHelper` on the bundled SQLite driver, so Room runs in its compatibility mode
 * (no `setDriver`), exactly as the app does on the framework SQLite: same generated code, same
 * `withTransaction`, same paging source. Only what Room calls is implemented.
 *
 * One connection, guarded by a lock that a transaction holds until it ends, which is what a
 * framework database with one writer gives the rest of the app. Nested transactions behave as on
 * Android: a child that ends without success makes the outermost one roll back. Errors come out
 * as `android.database.sqlite.SQLiteException`s carrying the SQLite message, which Room's `@Upsert`
 * reads to tell a primary-key conflict from other failures.
 */
class BundledOpenHelperFactory : SupportSQLiteOpenHelper.Factory {
    override fun create(configuration: SupportSQLiteOpenHelper.Configuration): SupportSQLiteOpenHelper =
        BundledOpenHelper(configuration)
}

private class BundledOpenHelper(private val configuration: SupportSQLiteOpenHelper.Configuration) : SupportSQLiteOpenHelper {
    private var database: BundledDatabase? = null

    override val databaseName: String? get() = configuration.name
    override fun setWriteAheadLoggingEnabled(enabled: Boolean) = Unit
    override val writableDatabase: SupportSQLiteDatabase get() = open()
    override val readableDatabase: SupportSQLiteDatabase get() = open()

    @Synchronized
    private fun open(): BundledDatabase {
        database?.let { return it }
        require(configuration.name == null) { "in-memory databases only" }
        val db = BundledDatabase(BundledSQLiteDriver().open(":memory:"))
        val callback = configuration.callback
        callback.onConfigure(db)
        val current = db.version
        if (current != callback.version) {
            db.beginTransaction()
            try {
                when {
                    current == 0 -> callback.onCreate(db)
                    current < callback.version -> callback.onUpgrade(db, current, callback.version)
                    else -> callback.onDowngrade(db, current, callback.version)
                }
                db.version = callback.version
                db.setTransactionSuccessful()
            } finally {
                db.endTransaction()
            }
        }
        callback.onOpen(db)
        database = db
        return db
    }

    @Synchronized
    override fun close() {
        database?.close()
        database = null
    }
}

private class BundledDatabase(private val connection: SQLiteConnection) : SupportSQLiteDatabase {
    private val lock = ReentrantLock()
    private var open = true

    private class Frame(val listener: SQLiteTransactionListener?) {
        var successful = false
        var childFailed = false
    }

    /** Only the thread holding [lock] touches this. */
    private val frames = ArrayDeque<Frame>()

    fun <R> locked(block: () -> R): R = lock.withLock {
        try {
            block()
        } catch (e: android.database.SQLException) {
            throw e
        } catch (e: RuntimeException) {
            // The bundled driver throws its own androidx.sqlite.SQLiteException.
            throw if (e.javaClass.name == "androidx.sqlite.SQLiteException") translate(e) else e
        }
    }

    fun prepare(sql: String): SQLiteStatement = connection.prepare(sql)

    private fun exec(sql: String) {
        locked { prepare(sql).use { it.step() } }
    }

    private fun begin(mode: String, listener: SQLiteTransactionListener?) {
        lock.lock()
        try {
            if (frames.isEmpty()) exec("BEGIN $mode")
            frames.addLast(Frame(listener))
            listener?.onBegin()
        } catch (t: Throwable) {
            lock.unlock()
            throw t
        }
    }

    override fun beginTransaction() = begin("IMMEDIATE", null)
    override fun beginTransactionNonExclusive() = begin("IMMEDIATE", null)
    override fun beginTransactionReadOnly() = begin("DEFERRED", null)
    override fun beginTransactionWithListener(transactionListener: SQLiteTransactionListener) = begin("IMMEDIATE", transactionListener)
    override fun beginTransactionWithListenerNonExclusive(transactionListener: SQLiteTransactionListener) = begin("IMMEDIATE", transactionListener)
    override fun beginTransactionWithListenerReadOnly(transactionListener: SQLiteTransactionListener) = begin("DEFERRED", transactionListener)

    override fun setTransactionSuccessful() {
        check(lock.isHeldByCurrentThread && frames.isNotEmpty()) { "no transaction on this thread" }
        frames.last().successful = true
    }

    override fun endTransaction() {
        check(lock.isHeldByCurrentThread && frames.isNotEmpty()) { "no transaction on this thread" }
        try {
            val frame = frames.removeLast()
            val ok = frame.successful && !frame.childFailed
            val parent = frames.lastOrNull()
            if (parent != null) {
                if (!ok) parent.childFailed = true
            } else {
                exec(if (ok) "COMMIT" else "ROLLBACK")
            }
            if (ok) frame.listener?.onCommit() else frame.listener?.onRollback()
        } finally {
            lock.unlock()
        }
    }

    override fun inTransaction(): Boolean = lock.isHeldByCurrentThread && frames.isNotEmpty()
    override val isDbLockedByCurrentThread: Boolean get() = lock.isHeldByCurrentThread
    override fun yieldIfContendedSafely(): Boolean = false
    override fun yieldIfContendedSafely(sleepAfterYieldDelayMillis: Long): Boolean = false

    override var version: Int
        get() = locked { prepare("PRAGMA user_version").use { it.step(); it.getLong(0).toInt() } }
        set(value) = exec("PRAGMA user_version = $value")

    override val maximumSize: Long get() = Long.MAX_VALUE
    override fun setMaximumSize(numBytes: Long): Long = Long.MAX_VALUE
    override var pageSize: Long
        get() = locked { prepare("PRAGMA page_size").use { it.step(); it.getLong(0) } }
        set(value) = exec("PRAGMA page_size = $value")

    override fun query(query: String): Cursor = query(SimpleSQLiteQuery(query))
    override fun query(query: String, bindArgs: Array<out Any?>): Cursor = query(SimpleSQLiteQuery(query, bindArgs))
    override fun query(query: SupportSQLiteQuery, cancellationSignal: CancellationSignal?): Cursor = query(query)

    /** Reads every row up front, as a framework cursor fills its window. */
    override fun query(query: SupportSQLiteQuery): Cursor = locked {
        prepare(query.sql).use { statement ->
            query.bindTo(StatementBinder(statement))
            val columns = Array(statement.getColumnCount()) { statement.getColumnName(it) }
            val rows = ArrayList<Array<Any?>>()
            while (statement.step()) {
                rows += Array(columns.size) { i ->
                    when (statement.getColumnType(i)) {
                        SQLITE_DATA_INTEGER -> statement.getLong(i)
                        SQLITE_DATA_FLOAT -> statement.getDouble(i)
                        SQLITE_DATA_TEXT -> statement.getText(i)
                        SQLITE_DATA_BLOB -> statement.getBlob(i)
                        else -> null
                    }
                }
            }
            RowsCursor(columns, rows)
        }
    }

    override fun compileStatement(sql: String): SupportSQLiteStatement = BundledStatement(this, sql)

    override fun execSQL(sql: String) = exec(sql)

    override fun execSQL(sql: String, bindArgs: Array<out Any?>) = locked {
        prepare(sql).use { statement ->
            SimpleSQLiteQuery(sql, bindArgs).bindTo(StatementBinder(statement))
            statement.step()
            Unit
        }
    }

    fun changes(): Int = prepare("SELECT changes()").use { it.step(); it.getLong(0).toInt() }
    fun lastInsertRowId(): Long = prepare("SELECT last_insert_rowid()").use { it.step(); it.getLong(0) }

    override fun insert(table: String, conflictAlgorithm: Int, values: ContentValues): Long = throw UnsupportedOperationException()
    override fun delete(table: String, whereClause: String?, whereArgs: Array<out Any?>?): Int = throw UnsupportedOperationException()
    override fun update(table: String, conflictAlgorithm: Int, values: ContentValues, whereClause: String?, whereArgs: Array<out Any?>?): Int =
        throw UnsupportedOperationException()

    override val isReadOnly: Boolean get() = false
    override val isOpen: Boolean get() = open
    override fun needUpgrade(newVersion: Int): Boolean = newVersion > version
    override val path: String? get() = null
    override fun setLocale(locale: Locale) = Unit
    override fun setMaxSqlCacheSize(cacheSize: Int) = Unit
    override fun setForeignKeyConstraintsEnabled(enabled: Boolean) = exec("PRAGMA foreign_keys = ${if (enabled) 1 else 0}")
    override fun enableWriteAheadLogging(): Boolean = false
    override fun disableWriteAheadLogging() = Unit
    override val isWriteAheadLoggingEnabled: Boolean get() = false
    override val attachedDbs: List<android.util.Pair<String, String>>? get() = null
    override val isDatabaseIntegrityOk: Boolean get() = true

    override fun close() = lock.withLock {
        if (open) connection.close()
        open = false
    }

    private fun translate(e: RuntimeException): RuntimeException {
        val message = e.message ?: e.javaClass.name
        return if (message.contains("constraint", ignoreCase = true)) ConstraintError(message) else SqliteError(message)
    }
}

/** The stubbed framework constructors drop their message, so these carry it themselves. */
private class ConstraintError(override val message: String) : SQLiteConstraintException()
private class SqliteError(override val message: String) : SQLiteException()

/** A statement compiled once and re-executed with fresh bindings, like `SQLiteStatement`. */
private class BundledStatement(private val db: BundledDatabase, private val sql: String) : SupportSQLiteStatement {
    private val bindings = HashMap<Int, Any?>()
    private var prepared: SQLiteStatement? = null

    private fun <R> withStatement(block: (SQLiteStatement) -> R): R = db.locked {
        val statement = prepared ?: db.prepare(sql).also { prepared = it }
        try {
            statement.clearBindings()
            for ((index, value) in bindings) {
                when (value) {
                    null -> statement.bindNull(index)
                    is Long -> statement.bindLong(index, value)
                    is Double -> statement.bindDouble(index, value)
                    is String -> statement.bindText(index, value)
                    is ByteArray -> statement.bindBlob(index, value)
                }
            }
            block(statement)
        } finally {
            statement.reset()
        }
    }

    override fun execute() = withStatement { while (it.step()) Unit }
    override fun executeUpdateDelete(): Int = withStatement { it.step(); db.changes() }
    override fun executeInsert(): Long = withStatement { it.step(); if (db.changes() > 0) db.lastInsertRowId() else -1L }
    override fun simpleQueryForLong(): Long = withStatement { it.step(); it.getLong(0) }
    override fun simpleQueryForString(): String? = withStatement { if (it.step() && !it.isNull(0)) it.getText(0) else null }

    override fun bindNull(index: Int) { bindings[index] = null }
    override fun bindLong(index: Int, value: Long) { bindings[index] = value }
    override fun bindDouble(index: Int, value: Double) { bindings[index] = value }
    override fun bindString(index: Int, value: String) { bindings[index] = value }
    override fun bindBlob(index: Int, value: ByteArray) { bindings[index] = value }
    override fun clearBindings() = bindings.clear()

    override fun close() {
        db.locked { prepared?.close() }
        prepared = null
    }
}

/** Binds a `SupportSQLiteQuery`'s arguments straight onto a driver statement. */
class StatementBinder(private val statement: SQLiteStatement) : SupportSQLiteProgram {
    override fun bindNull(index: Int) = statement.bindNull(index)
    override fun bindLong(index: Int, value: Long) = statement.bindLong(index, value)
    override fun bindDouble(index: Int, value: Double) = statement.bindDouble(index, value)
    override fun bindString(index: Int, value: String) = statement.bindText(index, value)
    override fun bindBlob(index: Int, value: ByteArray) = statement.bindBlob(index, value)
    override fun clearBindings() = statement.clearBindings()
    override fun close() = Unit
}

/** A cursor over rows already read. */
private class RowsCursor(private val columns: Array<String>, private val rows: List<Array<Any?>>) : Cursor {
    private var position = -1
    private var closed = false

    private fun value(column: Int): Any? = rows[position][column]

    override fun getCount(): Int = rows.size
    override fun getPosition(): Int = position
    override fun move(offset: Int): Boolean = moveToPosition(position + offset)
    override fun moveToPosition(position: Int): Boolean {
        this.position = position.coerceIn(-1, rows.size)
        return this.position in rows.indices
    }
    override fun moveToFirst(): Boolean = moveToPosition(0)
    override fun moveToLast(): Boolean = moveToPosition(rows.size - 1)
    override fun moveToNext(): Boolean = moveToPosition(position + 1)
    override fun moveToPrevious(): Boolean = moveToPosition(position - 1)
    override fun isFirst(): Boolean = rows.isNotEmpty() && position == 0
    override fun isLast(): Boolean = rows.isNotEmpty() && position == rows.size - 1
    override fun isBeforeFirst(): Boolean = rows.isEmpty() || position == -1
    override fun isAfterLast(): Boolean = rows.isEmpty() || position == rows.size

    override fun getColumnIndex(columnName: String): Int = columns.indexOfFirst { it.equals(columnName, ignoreCase = true) }
    override fun getColumnIndexOrThrow(columnName: String): Int =
        getColumnIndex(columnName).also { require(it >= 0) { "no column $columnName" } }
    override fun getColumnName(columnIndex: Int): String = columns[columnIndex]
    override fun getColumnNames(): Array<String> = columns.copyOf()
    override fun getColumnCount(): Int = columns.size

    override fun getBlob(columnIndex: Int): ByteArray? = value(columnIndex) as ByteArray?
    override fun getString(columnIndex: Int): String? = value(columnIndex)?.toString()
    override fun copyStringToBuffer(columnIndex: Int, buffer: CharArrayBuffer) = throw UnsupportedOperationException()
    override fun getShort(columnIndex: Int): Short = getLong(columnIndex).toShort()
    override fun getInt(columnIndex: Int): Int = getLong(columnIndex).toInt()
    override fun getLong(columnIndex: Int): Long = when (val v = value(columnIndex)) {
        null -> 0L
        is Long -> v
        is Double -> v.toLong()
        is String -> v.toLongOrNull() ?: 0L
        else -> 0L
    }
    override fun getFloat(columnIndex: Int): Float = getDouble(columnIndex).toFloat()
    override fun getDouble(columnIndex: Int): Double = when (val v = value(columnIndex)) {
        null -> 0.0
        is Long -> v.toDouble()
        is Double -> v
        is String -> v.toDoubleOrNull() ?: 0.0
        else -> 0.0
    }
    override fun getType(columnIndex: Int): Int = when (value(columnIndex)) {
        null -> Cursor.FIELD_TYPE_NULL
        is Long -> Cursor.FIELD_TYPE_INTEGER
        is Double -> Cursor.FIELD_TYPE_FLOAT
        is String -> Cursor.FIELD_TYPE_STRING
        else -> Cursor.FIELD_TYPE_BLOB
    }
    override fun isNull(columnIndex: Int): Boolean = value(columnIndex) == null

    @Deprecated("Deprecated in Java")
    override fun deactivate() = Unit
    @Deprecated("Deprecated in Java")
    override fun requery(): Boolean = false
    override fun close() { closed = true }
    override fun isClosed(): Boolean = closed

    override fun registerContentObserver(observer: ContentObserver) = Unit
    override fun unregisterContentObserver(observer: ContentObserver) = Unit
    override fun registerDataSetObserver(observer: DataSetObserver) = Unit
    override fun unregisterDataSetObserver(observer: DataSetObserver) = Unit
    override fun setNotificationUri(cr: ContentResolver, uri: Uri) = Unit
    override fun getNotificationUri(): Uri? = null
    override fun getWantsAllOnMoveCalls(): Boolean = false
    override fun setExtras(extras: Bundle) = Unit
    override fun getExtras(): Bundle? = null
    override fun respond(extras: Bundle): Bundle? = null
}
