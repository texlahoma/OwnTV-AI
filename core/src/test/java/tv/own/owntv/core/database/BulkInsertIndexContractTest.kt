package tv.own.owntv.core.database

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * [BulkInsertHelper] drops a table's non-unique indexes for a bulk insert and restores the
 * *canonical* set from [OwnTVDatabase.EXPECTED_NON_UNIQUE_INDEXES]. A table it is willing to drop
 * but which has no canonical entry would therefore lose its indexes permanently — silently, until a
 * later migration's schema validation crash-loops the app on upgrade. These tests pin the contract
 * that makes that combination unrepresentable.
 */
class BulkInsertIndexContractTest {

    @Test
    fun `bulk-droppable tables are exactly the tables with a canonical index set`() {
        assertEquals(
            OwnTVDatabase.EXPECTED_NON_UNIQUE_INDEXES.keys,
            BulkInsertHelper.KNOWN_TABLES,
        )
    }

    @Test
    fun `every canonical entry can actually restore something`() {
        OwnTVDatabase.EXPECTED_NON_UNIQUE_INDEXES.forEach { (table, statements) ->
            assertTrue("$table has no canonical indexes to restore", statements.isNotEmpty())
            statements.forEach { sql ->
                assertTrue("not an idempotent CREATE INDEX: $sql", sql.startsWith("CREATE INDEX IF NOT EXISTS "))
                assertTrue("$sql does not target $table", sql.contains("ON `$table`"))
            }
        }
    }
}
