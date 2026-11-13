package tv.own.owntv.core.database

import android.content.Context
import androidx.room.Room
import androidx.room.withTransaction
import androidx.sqlite.driver.AndroidSQLiteDriver
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import tv.own.owntv.core.database.entity.ProfileEntity

/**
 * Regression test for the backup-restore crash
 *
 *     Cannot return a SupportSQLiteOpenHelper since no SupportSQLiteOpenHelper.Factory
 *     was configured with Room.
 *
 * The production database (DatabaseModule) is built with `.setDriver(BundledSQLiteDriver())`, in
 * which mode Room never creates a SupportSQLiteOpenHelper — but the Android-only
 * `androidx.room.withTransaction` extension still drives its transaction through one
 * (beginTransaction/setTransactionSuccessful/endTransaction), so it throws on the very first
 * write transaction. Restore hit that first; every EPG/user-data transaction would hit it too.
 * [withWriteTransaction] is the driver-backed replacement; these tests run real SQLite on the JVM
 * via Robolectric, so the production driver code path is exercised instead of mocked.
 *
 * BundledSQLiteDriver ships only Android .so artifacts and cannot satisfy System.loadLibrary on a
 * host JVM, so the test uses AndroidSQLiteDriver backed by Robolectric's native SQLite runtime.
 * Both implement the same SQLiteDriver/connection API that [withWriteTransaction] is written
 * against, and — crucially — both leave Room without a SupportSQLiteOpenHelper, which is the
 * exact condition that makes the legacy extension throw.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class RoomDriverTransactionTest {

    private lateinit var db: OwnTVDatabase

    @Before
    fun setUp() {
        val context = RuntimeEnvironment.getApplication() as Context
        db = Room.inMemoryDatabaseBuilder(context, OwnTVDatabase::class.java)
            .setDriver(AndroidSQLiteDriver())
            .build()
    }

    @After
    fun tearDown() {
        db.close()
    }

    @Test
    fun writeTransactionCommits() = runBlocking {
        db.withWriteTransaction {
            db.profileDao().insert(ProfileEntity(name = "Ada", avatarColor = 1))
        }

        assertEquals(1, db.profileDao().getAllOnce().size)
    }

    @Test
    fun writeTransactionSeesUncommittedWritesAndSupportsMultipleStatements() = runBlocking {
        // The suspend DAO call inside the block must reuse the transaction's pooled connection:
        // a read-your-writes failure here would mean inserts land on a different connection and
        // the "one transaction" guarantee the backup merge relies on is gone.
        val id = db.withWriteTransaction {
            val newId = db.profileDao().insert(ProfileEntity(name = "Grace", avatarColor = 2))
            val seenInside = db.profileDao().getById(newId)
            assertEquals("Grace", seenInside?.name)
            newId
        }
        assertTrue(id > 0)
    }

    @Test
    fun writeTransactionRollsBackWhenBlockThrows() = runBlocking {
        runCatching {
            db.withWriteTransaction {
                db.profileDao().insert(ProfileEntity(name = "Doomed", avatarColor = 3))
                error("halfway failure")
            }
        }

        assertEquals(0, db.profileDao().getAllOnce().size)
    }

    /**
     * Pins the root cause: on a driver-built database the legacy Android `withTransaction` must
     * keep throwing. If this test starts passing, Room fixed the extension and the project
     * extension could be reconsidered — but until then nobody may wire it back in.
     */
    @Test
    fun legacySupportWithTransactionIsRejectedOnDriverDatabase() = runBlocking {
        try {
            db.withTransaction {
                db.profileDao().insert(ProfileEntity(name = "Legacy", avatarColor = 4))
            }
            fail("Expected the legacy Support-based withTransaction to be rejected")
        } catch (e: IllegalStateException) {
            assertTrue(
                "Unexpected message: ${e.message}",
                e.message?.contains("SupportSQLiteOpenHelper.Factory") == true,
            )
        }
        assertEquals(0, db.profileDao().getAllOnce().size)
    }
}
