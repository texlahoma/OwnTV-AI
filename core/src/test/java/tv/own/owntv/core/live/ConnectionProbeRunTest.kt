package tv.own.owntv.core.live

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * The probe's decisions, with no network in sight.
 *
 * Every case here came from a real question asked while designing it: a provider that never says how
 * many streams it allows, channels that are simply broken, and the difference between the two —
 * which is the whole difficulty, because from one stream's point of view they look identical.
 */
class ConnectionProbeRunTest {

    /** Drive a run to completion by answering each step with the next outcome in [outcomes]. */
    private fun run(vararg outcomes: StreamOutcome, maxStreams: Int = MAX_PROBE_STREAMS): ConnectionProbeResult {
        val run = ConnectionProbeRun(maxStreams)
        outcomes.forEach { outcome ->
            require(run.next() is ProbeStep.OpenStream) { "run finished before the outcomes did" }
            run.record(outcome)
        }
        return (run.next() as ProbeStep.Finished).result
    }

    @Test
    fun `a provider that allows one stream kills the first when the second opens`() {
        // A real Stalker portal, measured: stream 2 opens fine and stream 1 dies seconds later.
        val result = run(StreamOutcome.FLOWING, StreamOutcome.EARLIER_STREAM_DIED, StreamOutcome.EARLIER_STREAM_DIED)
        assertEquals(ConnectionProbeResult.Limit(1, atLeast = false), result)
    }

    @Test
    fun `a provider that refuses the second stream outright reads the same`() {
        val result = run(StreamOutcome.FLOWING, StreamOutcome.NEVER_STARTED, StreamOutcome.NEVER_STARTED)
        assertEquals(ConnectionProbeResult.Limit(1, atLeast = false), result)
    }

    @Test
    fun `one broken channel never decides anything on its own`() {
        // Slot 2's first channel is dead; its retry works. The limit is not 1, and the run goes on.
        val run = ConnectionProbeRun()
        run.record(StreamOutcome.FLOWING)          // slot 1
        run.record(StreamOutcome.NEVER_STARTED)    // slot 2, broken channel
        run.record(StreamOutcome.FLOWING)          // slot 2, another channel — fine
        assertEquals(ProbeStep.OpenStream(slot = 3, attempt = 1), run.next())
    }

    @Test
    fun `two different channels failing at the same slot is the limit`() {
        val result = run(
            StreamOutcome.FLOWING,                 // 1 ok
            StreamOutcome.FLOWING,                 // 2 ok
            StreamOutcome.NEVER_STARTED,           // 3 fails
            StreamOutcome.NEVER_STARTED,           // 3 fails again, on another channel
        )
        assertEquals(ConnectionProbeResult.Limit(2, atLeast = false), result)
    }

    @Test
    fun `reaching the cap reports at-least rather than an exact number`() {
        val result = run(
            StreamOutcome.FLOWING, StreamOutcome.FLOWING, StreamOutcome.FLOWING, StreamOutcome.FLOWING,
        )
        assertEquals(ConnectionProbeResult.Limit(MAX_PROBE_STREAMS, atLeast = true), result)
    }

    @Test
    fun `nothing is concluded when even the first stream will not play`() {
        // Two dead channels before anything flowed says nothing about the provider's limit — it says
        // the channels, the network or the subscription are the problem. Storing 0 here would refuse
        // tiles the user is entitled to, which is worse than not knowing.
        val result = run(StreamOutcome.NEVER_STARTED, StreamOutcome.NEVER_STARTED)
        assertEquals(ConnectionProbeResult.Unknown, result)
    }

    @Test
    fun `the first stream is retried once before giving up`() {
        val result = run(StreamOutcome.NEVER_STARTED, StreamOutcome.FLOWING, StreamOutcome.NEVER_STARTED, StreamOutcome.NEVER_STARTED)
        assertEquals(ConnectionProbeResult.Limit(1, atLeast = false), result)
    }

    @Test
    fun `a run asks for one stream at a time, in order, retrying within the slot`() {
        val run = ConnectionProbeRun()
        assertEquals(ProbeStep.OpenStream(slot = 1, attempt = 1), run.next())
        run.record(StreamOutcome.FLOWING)
        assertEquals(ProbeStep.OpenStream(slot = 2, attempt = 1), run.next())
        run.record(StreamOutcome.NEVER_STARTED)
        assertEquals(ProbeStep.OpenStream(slot = 2, attempt = 2), run.next())
    }

    @Test
    fun `a cap of one asks for a single stream and concludes nothing beyond it`() {
        // Guards the degenerate configuration rather than leaving it to behave by accident.
        val result = run(StreamOutcome.FLOWING, maxStreams = 1)
        assertEquals(ConnectionProbeResult.Limit(1, atLeast = true), result)
    }
}
