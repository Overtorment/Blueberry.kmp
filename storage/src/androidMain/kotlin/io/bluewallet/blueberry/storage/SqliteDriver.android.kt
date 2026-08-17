package io.bluewallet.blueberry.storage

import android.database.sqlite.SQLiteDatabase
import androidx.sqlite.db.SupportSQLiteDatabase
import androidx.sqlite.db.SupportSQLiteOpenHelper
import app.cash.sqldelight.db.SqlDriver
import app.cash.sqldelight.driver.android.AndroidSqliteDriver

internal actual fun openSqliteDriver(path: String): SqlDriver {
    return AndroidSqliteDriver(PathSqliteOpenHelper(path))
}

private class PathSqliteOpenHelper(
    private val path: String,
) : SupportSQLiteOpenHelper {
    private var database: SupportSQLiteDatabase? = null
    private var writeAheadLoggingEnabled = false

    override val databaseName: String? = path

    override fun setWriteAheadLoggingEnabled(enabled: Boolean) {
        writeAheadLoggingEnabled = enabled
        database?.let { db ->
            if (enabled) {
                db.enableWriteAheadLogging()
            } else {
                db.disableWriteAheadLogging()
            }
        }
    }

    override val writableDatabase: SupportSQLiteDatabase
        get() = database ?: openDatabase().also { database = it }

    override val readableDatabase: SupportSQLiteDatabase
        get() = writableDatabase

    override fun close() {
        database?.close()
        database = null
    }

    private fun openDatabase(): SupportSQLiteDatabase {
        val sqlite = if (path == ":memory:") {
            SQLiteDatabase.create(null)
        } else {
            SQLiteDatabase.openOrCreateDatabase(path, null)
        }
        val db = wrapFrameworkDatabase(sqlite)
        if (writeAheadLoggingEnabled) {
            db.enableWriteAheadLogging()
        }
        return db
    }
}

// Release minification (R8) can strip FrameworkSQLiteDatabase; keep it if minify is enabled.
private fun wrapFrameworkDatabase(sqlite: SQLiteDatabase): SupportSQLiteDatabase {
    val className = "androidx.sqlite.db.framework.FrameworkSQLiteDatabase"
    val clazz = try {
        Class.forName(className)
    } catch (_: ClassNotFoundException) {
        throw IllegalStateException(
            "$className: AndroidX SQLite framework constructor could not be found.",
        )
    }
    val ctor = try {
        clazz.getDeclaredConstructor(SQLiteDatabase::class.java)
    } catch (_: ReflectiveOperationException) {
        throw IllegalStateException(
            "$className: AndroidX SQLite framework constructor could not be found.",
        )
    }
    ctor.isAccessible = true
    return try {
        @Suppress("UNCHECKED_CAST")
        ctor.newInstance(sqlite) as SupportSQLiteDatabase
    } catch (e: ReflectiveOperationException) {
        throw IllegalStateException(
            "$className: AndroidX SQLite framework constructor could not be found.",
            e,
        )
    }
}
