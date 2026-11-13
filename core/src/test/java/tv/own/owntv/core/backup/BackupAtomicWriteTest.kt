package tv.own.owntv.core.backup

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

/**
 * A backup export must never destroy the previous backup. The old code streamed straight over
 * the backup file, so a crash, a full disk or a pulled USB stick mid-write left the user with a
 * truncated file and no earlier copy.
 *
 * Exercised through the String overload; the container path ([BackupContainer]) writes bytes through
 * the very same function, and its round-trip is covered on-device by `BackupContainerTest` — org.json
 * and android.util.Base64 are both stubs in plain JVM unit tests.
 */
class BackupAtomicWriteTest {

    @get:Rule
    val folder = TemporaryFolder()

    private fun target() = File(folder.root, BackupManager.BACKUP_FILENAME)

    @Test
    fun `first export writes the file and leaves no tmp behind`() {
        val path = BackupManager.writeAtomically(target(), "{\"v\":1}")

        assertEquals(target().absolutePath, path)
        assertEquals("{\"v\":1}", target().readText())
        assertFalse(File(folder.root, BackupManager.BACKUP_FILENAME + BackupManager.TMP_SUFFIX).exists())
        assertFalse(File(folder.root, BackupManager.BACKUP_FILENAME + BackupManager.BAK_SUFFIX).exists())
    }

    @Test
    fun `a second export rotates the previous file to bak`() {
        BackupManager.writeAtomically(target(), "{\"v\":1}")
        BackupManager.writeAtomically(target(), "{\"v\":2}")

        assertEquals("{\"v\":2}", target().readText())
        val bak = File(folder.root, BackupManager.BACKUP_FILENAME + BackupManager.BAK_SUFFIX)
        assertTrue(bak.exists())
        assertEquals("{\"v\":1}", bak.readText())
    }

    @Test
    fun `a failed write leaves the previous backup intact`() {
        BackupManager.writeAtomically(target(), "{\"v\":1}")

        // A directory where the tmp file needs to go makes the write fail the way a full disk would.
        val tmp = File(folder.root, BackupManager.BACKUP_FILENAME + BackupManager.TMP_SUFFIX)
        assertTrue(tmp.mkdir())

        runCatching { BackupManager.writeAtomically(target(), "{\"v\":2}") }.let {
            assertTrue("expected the write to fail", it.isFailure)
        }
        assertEquals("{\"v\":1}", target().readText())
    }
}
