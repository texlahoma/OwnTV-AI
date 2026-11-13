package tv.own.owntv.player

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/** The CH+/− press → first frame measurement: claimed by the tune it caused, never by a later one. */
class ZapTimingTest {

    @Test
    fun `the tune a press caused claims it`() {
        val t = ZapTiming()
        t.markPress(1_000)
        assertEquals(1_000L, t.claim(1_550))
    }

    @Test
    fun `a press is claimed only once`() {
        val t = ZapTiming()
        t.markPress(1_000)
        t.claim(1_500)
        // A channel without a picture, then an unrelated tune: the second tune is not charged to the press.
        assertNull(t.claim(2_000))
    }

    @Test
    fun `no press means no measurement`() {
        assertNull(ZapTiming().claim(5_000))
    }

    @Test
    fun `the newest press wins`() {
        val t = ZapTiming()
        t.markPress(1_000)
        t.markPress(1_170) // key-repeat
        assertEquals(1_170L, t.claim(1_700))
    }

    @Test
    fun `a press older than the claim window did not cause this tune`() {
        val t = ZapTiming(claimWindowMs = 5_000)
        t.markPress(0)
        assertNull(t.claim(5_001))
        t.markPress(0)
        assertEquals(0L, t.claim(5_000))
    }

    @Test
    fun `a clock that went backwards is not a measurement`() {
        val t = ZapTiming()
        t.markPress(5_000)
        assertNull(t.claim(4_000))
    }

    @Test
    fun `a deliberate pick clears a press still waiting`() {
        val t = ZapTiming()
        t.markPress(1_000)
        t.clear()
        assertNull(t.claim(1_200))
    }

    @Test
    fun `time since a claimed press`() {
        assertEquals(650L, ZapTiming.sincePress(1_000, 1_650))
        assertNull(ZapTiming.sincePress(null, 1_650))
    }

    @Test
    fun `the log suffix is empty without a press`() {
        assertEquals("", ZapTiming.pressSuffix(null))
        assertEquals(" (zap key press to first frame 812ms)", ZapTiming.pressSuffix(812))
    }
}
