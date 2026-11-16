package tv.own.owntv.player

import androidx.media3.common.C
import androidx.media3.common.audio.AudioProcessor
import java.nio.ByteBuffer
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test

/**
 * The hold-back queue's depth transitions, driven as real byte counts. These broke silently once:
 * a request that SHRANK mid-play used to fall into the steady branch and keep the old, deeper
 * backlog forever — the UI showed the smaller delay while the audio stayed that much further
 * behind live, and every caption stamped against [LiveAudioDelay.measuredMs] drifted with it.
 */
@androidx.media3.common.util.UnstableApi
class HoldBackAudioProcessorTest {

    /** 48 kHz stereo 16-bit PCM = 192 000 bytes per second. Feeds are whole 1 s buffers. */
    private val bps = 192_000
    private val oneSec = 192_000

    private fun processor(requestedMs: () -> Int) = HoldBackAudioProcessor(requestedMs)

    private fun feed(p: HoldBackAudioProcessor): Int {
        val drained = ByteBuffer.allocate(oneSec)
        p.queueInput(drained)
        val out = p.output
        val n = out.remaining()
        out.position(out.limit())
        return n
    }

    private fun secs(bytes: Int) = bytes / bps

    @Before
    fun resetSharedState() {
        LiveAudioDelay.resetLead()
        LiveAudioDelay.filling = false
    }

    @Test
    fun `a shallower request releases the excess and holds the queue at the new depth`() {
        var requestedMs = 16_000
        val p = processor { requestedMs }
        p.configure(AudioProcessor.AudioFormat(48_000, 2, C.ENCODING_PCM_16BIT))

        // Fill to the 16 s depth: 16 buffers prime it, the next four pass through.
        var drained = 0
        repeat(20) { drained += feed(p) }
        assertEquals("the queue withholds exactly the requested depth", 4, secs(drained))

        // Lower the request mid-play: the excess beyond the new 8 s depth leaves in order.
        requestedMs = 8_000
        val shrunk = feed(p)
        assertEquals("the excess backlog above the new depth is handed back at once", 9, secs(shrunk))

        // Steady again at the new depth: one second in, one second out, nothing accumulates.
        drained = 0
        repeat(3) { drained += feed(p) }
        assertEquals("the queue holds the new, shallower depth", 3, secs(drained))
        assertEquals(
            "the published queue depth follows the new request",
            8_000,
            LiveAudioDelay.queuedMs,
        )
    }

    @Test
    fun `a deeper request re-fills before it releases again`() {
        var requestedMs = 8_000
        val p = processor { requestedMs }
        p.configure(AudioProcessor.AudioFormat(48_000, 2, C.ENCODING_PCM_16BIT))

        // Prime to 8 s, play through two buffers, then ask for 16 s: the queue must go silent
        // (withhold everything) until the new depth exists, exactly like the first fill.
        var drained = 0
        repeat(10) { drained += feed(p) }
        assertEquals(2, secs(drained))
        requestedMs = 16_000
        drained = 0
        repeat(8) { drained += feed(p) }
        assertEquals("the re-fill withholds until the deeper queue exists", 0, secs(drained))
        assertEquals(16_000, LiveAudioDelay.queuedMs)
    }

    @Test
    fun `zeroing the request drains the whole backlog`() {
        var requestedMs = 8_000
        val p = processor { requestedMs }
        p.configure(AudioProcessor.AudioFormat(48_000, 2, C.ENCODING_PCM_16BIT))

        repeat(9) { feed(p) } // prime to 8 s, one buffer through
        requestedMs = 0
        val drained = feed(p)
        // The incoming buffer joins the queue before the zero request sees it, so the drain
        // carries the backlog PLUS this buffer: 9 s.
        assertEquals("captions off: the backlog leaves, nothing withheld", 9, secs(drained))
        repeat(3) { assertEquals(1, secs(feed(p))) }
    }
}
