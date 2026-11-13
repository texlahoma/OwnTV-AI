package tv.own.owntv.core.download

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import tv.own.owntv.core.model.DownloadReason

/** When to add a connection, and what a failed download says. */
class DownloadTransferTest {

    @Test
    fun ramp_addsWhileEachConnectionHelps_thenStops() {
        val ramp = ConnectionRamp()
        assertTrue(ramp.shouldAdd(1, cap = 8, bytesPerSecond = 1_000_000))
        assertTrue(ramp.shouldAdd(2, cap = 8, bytesPerSecond = 1_900_000))
        // A third connection barely helped: the line is full.
        assertFalse(ramp.shouldAdd(3, cap = 8, bytesPerSecond = 1_950_000))
        assertFalse(ramp.shouldAdd(3, cap = 8, bytesPerSecond = 9_000_000))
    }

    @Test
    fun ramp_neverPassesTheAccountsFreeStreams() {
        assertFalse(ConnectionRamp().shouldAdd(1, cap = 1, bytesPerSecond = 1_000_000))
        val ramp = ConnectionRamp()
        ramp.stop()
        assertFalse(ramp.shouldAdd(1, cap = 8, bytesPerSecond = 1_000_000))
    }

    @Test
    fun reasons_mapTheProvidersAnswers() {
        assertEquals(DownloadReason.ACCOUNT_BUSY, DownloadReasons.ofHttp(458))
        assertEquals(DownloadReason.ACCOUNT_BUSY, DownloadReasons.ofHttp(509))
        assertEquals(DownloadReason.REFUSED, DownloadReasons.ofHttp(403))
        assertEquals(DownloadReason.NOT_FOUND, DownloadReasons.ofHttp(404))
        assertEquals(DownloadReason.SERVER_ERROR, DownloadReasons.ofHttp(503))
        assertEquals(DownloadReason.HTTP_ERROR, DownloadReasons.ofHttp(302))
        assertEquals(DownloadReason.NO_SPACE, DownloadReasons.ofError(java.io.IOException("write failed: ENOSPC (No space left on device)")))
        assertEquals(DownloadReason.FILE_TOO_LARGE, DownloadReasons.ofError(java.io.IOException("write failed: EFBIG (File too large)")))
        assertEquals(DownloadReason.CONNECTION_LOST, DownloadReasons.ofError(java.net.SocketTimeoutException("timeout")))
    }
}
