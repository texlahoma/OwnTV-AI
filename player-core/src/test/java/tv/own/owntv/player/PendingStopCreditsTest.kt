package tv.own.owntv.player

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Telling our own cleanup `END_FILE` apart from a stream that actually died. Getting this wrong
 * either retries healthy playback for no reason, or swallows a real failure.
 */
class PendingStopCreditsTest {

    private fun credits() = PendingStopCredits(max = 4)

    @Test
    fun `an app-issued stop classifies exactly one end-file`() {
        val c = credits()
        c.credit()
        assertTrue("the cleanup END_FILE is ours", c.consume())
        assertFalse("the next one is a real failure", c.consume())
    }

    @Test
    fun `a real end-file with nothing outstanding is never claimed`() {
        assertFalse(credits().consume())
    }

    @Test
    fun `a late cleanup end-file is still classified after the next file loads`() {
        // The regression: FILE_LOADED used to call getAndSet(0). When mpv delivered the outgoing
        // file's END_FILE *after* the new file's FILE_LOADED, the credit was already gone and the
        // event was misread as a playback failure mid-playback.
        val c = credits()
        c.credit() // loadfile replaces the current item
        // ... FILE_LOADED for the new item arrives first; it must not clear anything ...
        assertEquals(1, c.peek())
        assertTrue("the late cleanup END_FILE is still ours", c.consume())
    }

    @Test
    fun `interleaved commands each consume one event`() {
        val c = credits()
        c.credit(); c.credit() // e.g. a stop then a loadfile during an engine handoff
        assertTrue(c.consume())
        assertTrue(c.consume())
        assertFalse("a third END_FILE is a genuine one", c.consume())
    }

    @Test
    fun `credits are capped so an unmatched command cannot accumulate`() {
        val c = credits()
        repeat(50) { c.credit() }
        assertEquals(4, c.peek())
        repeat(4) { assertTrue(c.consume()) }
        assertFalse(c.consume())
    }

    @Test
    fun `a rolled-back command owes nothing`() {
        val c = credits()
        c.credit()
        c.rollback() // the command threw before mpv ever saw it
        assertFalse(c.consume())
    }

    @Test
    fun `a fresh core inherits no credits`() {
        val c = credits()
        c.credit(); c.credit()
        c.reset()
        assertEquals(0, c.peek())
        assertFalse(c.consume())
    }
}
