package tv.own.owntv.player

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test

/** Only a frame from the new tune's own source counts as its first frame. */
class FirstFrameGateTest {

    @Test
    fun `a frame before the new source is applied is not counted`() {
        val gate = FirstFrameGate()
        val gen = gate.arm(startedMs = 1_000, pressAtMs = null)
        // The previous channel is still rendering: the playback thread has not reached the new source.
        assertNull(gate.take())
        gate.open(gen)
        assertEquals(1_000L, gate.take()?.startedMs)
    }

    @Test
    fun `the first frame is counted once per tune`() {
        val gate = FirstFrameGate()
        gate.open(gate.arm(1_000, null))
        assertNotNull(gate.take())
        assertNull(gate.take())
    }

    @Test
    fun `a retry that re-opens the same tune does not count it again`() {
        val gate = FirstFrameGate()
        val gen = gate.arm(1_000, null)
        gate.open(gen)
        assertNotNull(gate.take())
        gate.open(gen) // a reconnect re-prepares within the same tune
        assertNull(gate.take())
    }

    @Test
    fun `an open for a superseded tune is ignored`() {
        val gate = FirstFrameGate()
        val first = gate.arm(1_000, null)
        gate.arm(2_000, null) // a quicker second tune before the first source was applied
        gate.open(first) // the first tune's message arrives late
        assertNull(gate.take())
    }

    @Test
    fun `a new tune replaces an opened one that never rendered`() {
        val gate = FirstFrameGate()
        gate.open(gate.arm(1_000, null)) // opened, but no frame yet (still buffering)
        gate.arm(2_000, 1_900) // the user zaps on
        // A late frame of the old source must not be timed against either tune.
        assertNull(gate.take())
    }

    @Test
    fun `the timing numbers belong to the tune that rendered`() {
        val gate = FirstFrameGate()
        val gen = gate.arm(startedMs = 2_000, pressAtMs = 1_450)
        gate.open(gen)
        val tune = gate.take()
        assertEquals(FirstFrameGate.Tune(gen, 2_000, 1_450), tune)
    }

    @Test
    fun `nothing is counted before any tune`() {
        assertNull(FirstFrameGate().take())
    }
}
