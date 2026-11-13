package tv.own.owntv.core.backup

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import tv.own.owntv.core.model.SourceType

/**
 * A backup written by a newer build can name a source type this build has never heard of.
 * Restore used to coerce that to M3U, producing a broken "playlist" pointing at a portal URL that
 * the user couldn't tell apart from a real one. It must be skipped and counted instead.
 */
class BackupSourceTypeTest {

    @Test
    fun `every known type round-trips through its name`() {
        SourceType.entries.forEach { type ->
            assertEquals(type, BackupManager.parseSourceType(type.name))
        }
    }

    @Test
    fun `an unknown type is rejected rather than coerced to M3U`() {
        assertNull(BackupManager.parseSourceType("SOME_FUTURE_PORTAL"))
    }

    @Test
    fun `missing blank and wrong-case types are rejected`() {
        assertNull(BackupManager.parseSourceType(null))
        assertNull(BackupManager.parseSourceType(""))
        assertNull(BackupManager.parseSourceType("   "))
        // Enum names are the wire format; a lowercased one is not a value this build wrote.
        assertNull(BackupManager.parseSourceType(SourceType.entries.first().name.lowercase()))
    }

    @Test
    fun `the restore summary reports skipped sources as semantic data`() {
        assertEquals(0, BackupManager.ImportSummary(items = 12).skippedSources)
        assertEquals(1, BackupManager.ImportSummary(items = 12, skippedSources = 1).skippedSources)
        assertEquals(3, BackupManager.ImportSummary(items = 12, skippedSources = 3).skippedSources)
    }
}
