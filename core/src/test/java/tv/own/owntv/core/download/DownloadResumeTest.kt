package tv.own.owntv.core.download

import org.junit.Assert.assertEquals
import org.junit.Test
import tv.own.owntv.core.storage.MediaTarget
import java.io.File

/**
 * The resume offset and the paused byte count must come from the file on disk, never
 * from the in-memory progress counter, so that a pause-resume cycle reassembles the file exactly.
 */
class DownloadResumeTest {

    @Test
    fun `resume offset is the file length, not the recorded counter`() {
        val file = tempFile(ByteArray(1_500))
        assertEquals(1_500L, DownloadResume.resumeOffset(MediaTarget.Path(file)))
    }

    @Test
    fun `resume offset of a file that does not exist is zero`() {
        val file = File(tempDir(), "absent.mp4")
        assertEquals(0L, DownloadResume.resumeOffset(MediaTarget.Path(file)))
    }

    @Test
    fun `paused bytes ignore a stale in-memory counter`() {
        // The writer had appended 1500 bytes when the counter last flushed at 1000.
        val file = tempFile(ByteArray(1_500))
        assertEquals(1_500L, DownloadResume.bytesOnDisk(MediaTarget.Path(file), recorded = 1_000))
    }

    @Test
    fun `paused bytes fall back to the counter when the file is gone`() {
        val file = File(tempDir(), "unmounted.mp4")
        assertEquals(1_000L, DownloadResume.bytesOnDisk(MediaTarget.Path(file), recorded = 1_000))
        assertEquals(0L, DownloadResume.bytesOnDisk(null, recorded = -5))
    }

    /**
     * A pause-resume cycle over a 4000-byte resource: the second request asks for `bytes=1500-`, the
     * server replies 206 with the remaining 2500, and the reassembled file is exactly Content-Length
     * of the whole resource — no truncation, no duplicated prefix.
     */
    @Test
    fun `pause then resume reassembles a file of exactly the full content length`() {
        val fullLength = 4_000L
        val body = ByteArray(fullLength.toInt()) { (it % 251).toByte() }

        // First attempt: fresh start, interrupted after 1500 bytes.
        // The temp dir survives between runs; start from "nothing downloaded yet" every time.
        val file = File(tempDir(), "movie.mp4").apply { delete(); deleteOnExit() }
        assertEquals(0L, DownloadResume.resumeOffset(MediaTarget.Path(file)))
        file.writeBytes(body.copyOfRange(0, 1_500))
        val pausedAt = DownloadResume.bytesOnDisk(MediaTarget.Path(file), recorded = 900) // counter deliberately stale

        // Second attempt: Range starts where the file really ends.
        assertEquals(1_500L, pausedAt)
        val offset = DownloadResume.resumeOffset(MediaTarget.Path(file))
        assertEquals(pausedAt, offset)
        val remainder = body.copyOfRange(offset.toInt(), body.size)
        assertEquals(
            fullLength,
            DownloadResume.expectedTotal(append = true, existing = offset, bodyLength = remainder.size.toLong()),
        )
        file.appendBytes(remainder)

        assertEquals(fullLength, file.length())
        assertEquals(body.toList(), file.readBytes().toList())
    }

    @Test
    fun `a server that ignores Range restarts the total from zero`() {
        assertEquals(4_000L, DownloadResume.expectedTotal(append = false, existing = 1_500, bodyLength = 4_000))
        // An unknown body length (-1) must not make the total negative.
        assertEquals(1_500L, DownloadResume.expectedTotal(append = true, existing = 1_500, bodyLength = -1))
    }

    private fun tempDir(): File = File(System.getProperty("java.io.tmpdir"), "owntv-dl-test").apply {
        mkdirs()
        deleteOnExit()
    }

    private fun tempFile(bytes: ByteArray): File =
        File.createTempFile("download", ".part", tempDir()).apply {
            writeBytes(bytes)
            deleteOnExit()
        }
}
