package tv.own.owntv.player

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import tv.own.owntv.core.player.PlayerBudget

/** Films' buffer, timeout and reconnects; every Auto must be what the engines did before. */
class FilmNetworkTest {

    private val lowSpec = PlayerBudget("48MiB", "16MiB", readaheadSecs = "10", cacheSecs = "30", lowSpec = true)

    @Test
    fun `Auto keeps the device tier and mpv's own timeout`() {
        assertEquals(30, FilmNetwork.bufferSecs(0, lowSpec))
        assertEquals("10", FilmNetwork.readaheadSecs(0, lowSpec))
        assertEquals(60, FilmNetwork.mpvTimeoutSecs(0))
    }

    @Test
    fun `a choice replaces the seconds`() {
        assertEquals(300, FilmNetwork.bufferSecs(300, lowSpec))
        assertEquals("300", FilmNetwork.readaheadSecs(300, lowSpec))
        assertEquals(10, FilmNetwork.mpvTimeoutSecs(10))
    }

    @Test
    fun `the default budget is the single reload films always had`() {
        assertEquals(1, FilmNetwork.nextReconnect(used = 0, lastAtPosMs = 0, posMs = 5_000, budget = 1))
        assertNull(FilmNetwork.nextReconnect(used = 1, lastAtPosMs = 5_000, posMs = 20_000, budget = 1))
    }

    @Test
    fun `a minute of playback since the last reopen earns the budget back`() {
        assertEquals(1, FilmNetwork.nextReconnect(used = 1, lastAtPosMs = 5_000, posMs = 5_000 + FilmNetwork.HEALTHY_MS, budget = 1))
    }

    @Test
    fun `quick drops use the budget up, then stop`() {
        var used = 0
        var at = 0L
        var pos = 10_000L
        repeat(3) {
            used = FilmNetwork.nextReconnect(used, at, pos, budget = 3) ?: error("budget ran out early")
            at = pos
            pos += 5_000
        }
        assertNull(FilmNetwork.nextReconnect(used, at, pos, budget = 3))
    }

    @Test
    fun `Auto keeps mpv's 1 s resume wait, a chosen buffer waits longer`() {
        assertEquals(1, FilmNetwork.mpvResumeWaitSecs(0))
        assertEquals(SmartBuffer.FILM_RESUME_WAIT_SECS, FilmNetwork.mpvResumeWaitSecs(120))
        assertEquals(3, SmartBuffer.FILM_RESUME_WAIT_SECS)
    }

    @Test
    fun `Auto seconds still feed both engines exactly as before`() {
        // The byte side of Auto lives in SmartBufferTest; the seconds side must not move either.
        val mid = PlayerBudget("128MiB", "32MiB", readaheadSecs = "30", cacheSecs = "60", lowSpec = false)
        assertEquals(60, FilmNetwork.bufferSecs(0, mid))
        assertEquals("30", FilmNetwork.readaheadSecs(0, mid))
    }

    @Test
    fun `the new film ceiling defaults to no raise`() {
        assertEquals(0L, lowSpec.filmCeilingBytes)
    }
}
