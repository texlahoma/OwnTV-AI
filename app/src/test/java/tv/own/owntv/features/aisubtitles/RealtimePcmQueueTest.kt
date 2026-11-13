package tv.own.owntv.features.aisubtitles

import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class RealtimePcmQueueTest {

    private fun frame(n: Int) = shortArrayOf(n.toShort())

    // These tests exercise the queue's overflow/jump bookkeeping, not the capture stamp the pipeline
    // carries for end-to-end timing, so a fixed dummy stamp is enough — offer() itself always takes one.
    private fun RealtimePcmQueue.offer(frame: ShortArray) = offer(frame, capturedAtMs = 0L)

    private fun drain(channel: Channel<PcmFrame>): List<PcmFrame> = runBlocking {
        val out = ArrayList<PcmFrame>()
        while (true) {
            val r = channel.tryReceive()
            if (r.isSuccess) out.add(r.getOrThrow()) else break
        }
        out
    }

    @Test
    fun `within capacity nothing is dropped and every frame stays generation zero`() {
        val q = RealtimePcmQueue(4)
        repeat(4) { q.offer(frame(it + 1)) }

        val frames = drain(q.channel)
        assertEquals(4, frames.size)
        assertTrue(frames.all { it.generation == 0L })
        assertArrayEquals(shortArrayOf(1, 2, 3, 4), frames.map { it.samples[0] }.toShortArray())
        assertEquals(0L, q.droppedFrames.get())
    }

    @Test
    fun `first overflow discards the whole backlog and keeps only the newest frame at generation one`() {
        val q = RealtimePcmQueue(4)
        repeat(4) { q.offer(frame(it + 1)) } // fills: [1,2,3,4], gen 0
        q.offer(frame(5))                    // overflow: evict 1..4, keep 5, gen 1

        val frames = drain(q.channel)
        assertEquals(1, frames.size)
        assertEquals(1L, frames.single().generation)
        assertEquals(5, frames.single().samples[0].toInt())
        assertEquals(4L, q.droppedFrames.get())
    }

    @Test
    fun `no further generation bump while offers fit after a jump`() {
        val q = RealtimePcmQueue(2)
        q.offer(frame(1)); q.offer(frame(2)) // [1,2] gen 0
        q.offer(frame(3))                    // jump: drop 1,2; [3] gen 1
        q.offer(frame(4))                    // fits: [3,4] gen 1

        val frames = drain(q.channel)
        assertEquals(listOf(3L, 4L), frames.map { it.samples[0].toLong() })
        assertTrue(frames.all { it.generation == 1L })
        assertEquals(2L, q.droppedFrames.get())
    }

    @Test
    fun `a second overflow bumps generation again and accumulates the drop counter`() {
        val q = RealtimePcmQueue(2)
        q.offer(frame(1)); q.offer(frame(2)) // [1,2] gen 0
        q.offer(frame(3))                    // jump 1: [3] gen 1, dropped = 2
        q.offer(frame(4))                    // [3,4]
        q.offer(frame(5))                    // jump 2: evict 3,4; [5] gen 2, dropped = 4

        val frames = drain(q.channel)
        assertEquals(1, frames.size)
        assertEquals(2L, frames.single().generation)
        assertEquals(5, frames.single().samples[0].toInt())
        assertEquals(4L, q.droppedFrames.get())
    }

    @Test
    fun `frames between two jumps are delivered contiguous and in order`() {
        // This is the property the recognizer relies on: inside one generation the consumer hears
        // unbroken FIFO audio; only the boundary frame carries a new generation.
        val q = RealtimePcmQueue(3)
        q.offer(frame(10)); q.offer(frame(11)); q.offer(frame(12)) // gen 0
        q.offer(frame(13))                                         // jump 1: [13]
        q.offer(frame(14)); q.offer(frame(15))                     // contiguous gen 1

        val frames = drain(q.channel)
        assertEquals(listOf(13L, 14L, 15L), frames.map { it.samples[0].toLong() })
        assertEquals(listOf(1L, 1L, 1L), frames.map { it.generation })
    }

    @Test
    fun `newest frame payload is never lost even under repeated overflow`() {
        val q = RealtimePcmQueue(1)
        // Capacity 1: every offer after the first overflows; the newest frame must always survive.
        q.offer(frame(1))
        q.offer(frame(2))
        q.offer(frame(3))

        val frames = drain(q.channel)
        assertEquals(1, frames.size)
        assertEquals(3, frames.single().samples[0].toInt())
        // Each overflow evicts exactly the one stale frame and advances one generation.
        assertEquals(2L, q.droppedFrames.get())
        assertEquals(2L, frames.single().generation)
    }

    @Test
    fun `external jump clears the backlog and the next frame carries the new generation`() {
        // Renderer flush (seek / snap-to-live): timeline break without backlog. The queued frames
        // are old media time and must never reach the recognizer; the consumer resets on the gen bump.
        val q = RealtimePcmQueue(4)
        repeat(3) { q.offer(frame(it + 1)) } // [1,2,3] gen 0
        q.jump()                              // renderer flushed: discard all, gen 1, no surviving frame
        q.offer(frame(4))                     // new timeline: gen 1

        val frames = drain(q.channel)
        assertEquals(1, frames.size)
        assertEquals(4, frames.single().samples[0].toInt())
        assertEquals(1L, frames.single().generation)
        assertEquals(1L, q.generationJumps)
        assertEquals(3L, q.droppedFrames.get())
    }

    @Test
    fun `jumps are logged with their time and cause`() {
        // The counters answer "how many"; the log answers "when and why" — an opening warm-up burst
        // and a late stall both read `jmp N`, and only the stamps separate them.
        val q = RealtimePcmQueue(2)
        q.offer(frame(1)); q.offer(frame(2))
        q.offer(frame(3), capturedAtMs = 4_000L) // overflow, stamped with the new frame's capture time
        q.jump(atMs = 9_500L)                    // renderer flush

        assertEquals(
            listOf(JumpEvent(4_000L, JumpCause.OVERFLOW), JumpEvent(9_500L, JumpCause.FLUSH)),
            q.recentJumps,
        )
    }

    @Test
    fun `the jump log keeps only the most recent events`() {
        val q = RealtimePcmQueue(1)
        repeat(40) { q.jump(atMs = it.toLong()) }

        val log = q.recentJumps
        assertEquals(32, log.size)
        assertEquals(JumpEvent(8L, JumpCause.FLUSH), log.first())
        assertEquals(JumpEvent(39L, JumpCause.FLUSH), log.last())
    }

    @Test
    fun `jump while empty still bumps generation so the next stream restarts`() {
        val q = RealtimePcmQueue(2)
        q.jump()
        q.offer(frame(7))

        val frames = drain(q.channel)
        assertEquals(1L, frames.single().generation)
        assertEquals(0L, q.droppedFrames.get())
    }

    @Test
    fun `high water mark tracks the deepest backlog and consumed frames lower depth`() {
        val q = RealtimePcmQueue(8)
        q.offer(frame(1)); q.offer(frame(2)); q.offer(frame(3))
        assertEquals(3, q.highWaterFrames)

        q.channel.tryReceive().getOrThrow()
        q.markConsumed()
        q.offer(frame(4))
        // Depth returned to 3 after one consume + one offer; the maximum (3) is sticky.
        assertEquals(3, q.highWaterFrames)

        repeat(3) { q.offer(frame(10 + it)) } // depth climbs 4,5,6
        assertEquals(6, q.highWaterFrames)

        // An overflow records how deep the backlog was at the jump — which is the queue's capacity
        // and no more, since a jump can only happen once every slot is taken. The mark is capped to
        // say so: an uncapped depth counter drifts above the ceiling (a producer-side jump drains
        // frames the consumer never marks consumed), and the status line then reads the impossible
        // `qmax 9/8`, which makes a saturated queue look like a counter bug.
        val q2 = RealtimePcmQueue(2)
        q2.offer(frame(1)); q2.offer(frame(2)); q2.offer(frame(3))
        assertEquals(2, q2.highWaterFrames)
        assertEquals(1L, q2.overflowJumpCount)

        // Same at a larger ceiling: three queued, the fourth offer overflows and records 3.
        val q3 = RealtimePcmQueue(3)
        repeat(4) { q3.offer(frame(it)) }
        assertEquals(3, q3.highWaterFrames)
        assertEquals(1L, q3.overflowJumpCount)
    }

    @Test
    fun `average depth rises with the backlog and decays back to zero when it drains`() {
        val q = RealtimePcmQueue(8)

        // Park a standing backlog and sample it there: this is the "does a capacity bump help?"
        // signal, so it has to read clearly above zero while frames are actually waiting. The
        // consumer takes one frame and the producer adds one straight back, so the depth holds at 4
        // and the channel never fills — `markConsumed` alone only lowers the diagnostic depth, it
        // does not take the frame, so the receive has to happen for the depth to be a real one.
        repeat(4) { q.offer(frame(it + 1)) }
        repeat(200) { i ->
            q.channel.tryReceive()
            q.markConsumed()
            q.offer(frame(100 + i))
        }
        assertTrue("a parked backlog must read as one, was ${q.recentAvgDepthFrames}",
            q.recentAvgDepthFrames > 1.0)

        // Drain it and let the mean follow: a queue that empties must stop reporting a backlog, which
        // a cumulative session mean could not do — one bad minute would have poisoned it for good.
        while (q.channel.tryReceive().isSuccess) q.markConsumed()
        repeat(700) { q.markConsumed() }
        assertTrue("a drained queue must not hold a standing backlog, was ${q.recentAvgDepthFrames}",
            q.recentAvgDepthFrames < 0.1)
    }
}
