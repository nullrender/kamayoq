package app.kamayoq.android

import android.content.Context
import android.database.DatabaseErrorHandler
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteDatabaseCorruptException
import android.database.sqlite.SQLiteException
import android.util.Log
import androidx.sqlite.db.SupportSQLiteDatabase
import app.cash.sqldelight.db.QueryResult
import app.cash.sqldelight.driver.android.AndroidSqliteDriver
import app.kamayoq.data.LibraryStore
import java.io.File
import java.io.IOException

private const val TAG = "Kamayoq"

/** Why the library cannot be shown. */
enum class LibraryFailure { NEWER_VERSION, NOT_OPENED }

sealed interface LibraryOpening {
    data class Opened(val store: LibraryStore) : LibraryOpening
    data class Failed(val failure: LibraryFailure) : LibraryOpening
}

/** Opens the app's own database, the one that keeps the works and the reading progress. */
object LibraryDatabase {
    const val FILE_NAME = "kamayoq.db"

    // Android's default erases a file it finds corrupted; a library is never erased to open it.
    private val keepTheFile = DatabaseErrorHandler { Log.e(TAG, "SQLite reported a corrupted database") }

    fun open(context: Context, name: String = FILE_NAME): LibraryOpening {
        val schema = LibraryStore.schema
        val file = context.getDatabasePath(name)
        return try {
            val stored = if (file.exists()) readOnly(file) { it.version.toLong() } else 0L
            if (stored > schema.version) return LibraryOpening.Failed(LibraryFailure.NEWER_VERSION)
            if (stored in 1 until schema.version) copyBeforeMigrating(file, context.noBackupFilesDir, stored)
            LibraryOpening.Opened(opened(context, name))
        } catch (e: SQLiteException) {
            notOpened(e)
        } catch (e: IOException) {
            notOpened(e)
        }
    }

    private fun notOpened(cause: Exception): LibraryOpening {
        Log.e(TAG, "Opening the library failed", cause)
        return LibraryOpening.Failed(LibraryFailure.NOT_OPENED)
    }

    // The driver only opens the file, and migrates it, on its first use: that has to happen here,
    // where a failure becomes a screen. So does finding a damaged table, which opens as usual and
    // only fails when a screen reads it.
    private fun opened(context: Context, name: String): LibraryStore {
        val schema = LibraryStore.schema
        val driver = AndroidSqliteDriver(
            schema = schema,
            context = context,
            name = name,
            callback = object : AndroidSqliteDriver.Callback(schema) {
                // The tables refer to each other, and SQLite only checks that when told to, on
                // every connection.
                override fun onConfigure(db: SupportSQLiteDatabase) {
                    db.setForeignKeyConstraintsEnabled(true)
                }

                override fun onCorruption(db: SupportSQLiteDatabase) {
                    Log.e(TAG, "SQLite reported a corrupted database")
                }
            },
        )
        try {
            val check = driver.executeQuery(
                null,
                "PRAGMA quick_check",
                { row ->
                    row.next()
                    QueryResult.Value(row.getString(0))
                },
                0,
            ).value
            if (check != "ok") throw SQLiteDatabaseCorruptException("quick_check found damage")
        } catch (e: SQLiteException) {
            driver.close()
            throw e
        }
        return LibraryStore(driver)
    }

    /**
     * Keeps version [version] as it is before a migration touches it. VACUUM INTO and not a file
     * copy, which would miss what is still in the write-ahead log; the app does not restore this
     * copy, it exists for the person to get the library back by hand. Under a temporary name, so a
     * process killed halfway never leaves a file that looks finished. A good copy already there is
     * kept: it is the one from the first attempt, and a failed migration leaves the database as it
     * was.
     */
    private fun copyBeforeMigrating(database: File, folder: File, version: Long) {
        val copy = File(folder, "kamayoq-v$version.db")
        if (isGood(copy, version)) return
        folder.mkdirs()
        val unfinished = File(folder, "${copy.name}.tmp")
        if (unfinished.exists() && !unfinished.delete()) throw IOException("Cannot clear ${unfinished.name}")
        readOnly(database) { it.execSQL("VACUUM INTO ?", arrayOf(unfinished.path)) }
        if (!isGood(unfinished, version)) throw IOException("The copy of version $version is not good")
        if (!unfinished.renameTo(copy)) throw IOException("Cannot finish ${copy.name}")
    }

    private fun isGood(copy: File, version: Long): Boolean {
        if (!copy.exists()) return false
        return try {
            readOnly(copy) { db ->
                db.version.toLong() == version && db.rawQuery("PRAGMA integrity_check", null).use { result ->
                    result.moveToFirst() && result.getString(0) == "ok"
                }
            }
        } catch (e: SQLiteException) {
            Log.e(TAG, "A database copy is not good", e)
            false
        }
    }

    private fun <T> readOnly(file: File, block: (SQLiteDatabase) -> T): T =
        SQLiteDatabase.openDatabase(file.path, null, SQLiteDatabase.OPEN_READONLY, keepTheFile).use(block)
}
