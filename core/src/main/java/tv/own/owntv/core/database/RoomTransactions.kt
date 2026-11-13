package tv.own.owntv.core.database

import androidx.room.RoomDatabase
import androidx.room.immediateTransaction
import androidx.room.useWriterConnection

/**
 * Runs [block] inside a suspending write transaction on the driver connection pool.
 *
 * Use this instead of `androidx.room.withTransaction`. That Android-only Room extension drives its
 * transaction through `SupportSQLiteOpenHelper` (`beginTransaction` / `setTransactionSuccessful` /
 * `endTransaction`); when the database is built with `.setDriver(BundledSQLiteDriver())` — the way
 * OwnTV builds it in `DatabaseModule` — there is no `SupportSQLiteOpenHelper.Factory`, and the first
 * call throws
 *
 *     Cannot return a SupportSQLiteOpenHelper since no SupportSQLiteOpenHelper.Factory
 *     was configured with Room.
 *
 * Backup restore was the first code path to hit it; every EPG / user-data write transaction hits
 * the same wall. This is Room's own 2.x → 3.0 migration shape:
 * https://developer.android.com/training/data-storage/room/migration-2-to-3
 *
 * `BEGIN IMMEDIATE` matches the old WAL behaviour (`beginTransactionNonExclusive`): writers take
 * the write lock up front instead of failing with SQLITE_BUSY while upgrading a deferred
 * transaction. Suspend DAO calls inside [block] reuse this transaction's pooled connection, so
 * they see its uncommitted writes and commit/roll back atomically with it.
 */
suspend fun <R> RoomDatabase.withWriteTransaction(block: suspend () -> R): R =
    useWriterConnection { connection ->
        connection.immediateTransaction { block() }
    }
