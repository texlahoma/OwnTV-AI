package tv.own.owntv.features.aisubtitles

import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Pins the two behaviours of [AsrGain] that the on-screen meter of a real broadcast proved wrong.
 *
 * The class moved from "gain from the frame in hand" to "gain from the median of ~2 s of frames"
 * because a stadium's cheer arrives in a single 160 ms frame and, being far louder than the
 * commentary, it pulled the estimate down and left the speech that followed it under-boosted
 * (`rms 489` held at `gain 2.1x`). These tests reproduce that exact capture — a quiet line with a
 * lone loud burst — and assert the median ignores the burst, plus the peak limit that is the one
 * place the class may attenuate.
 */
class AsrGainTest {

    /**
     * A frame whose RMS (and peak) is [amplitude]: a run of the same sample makes RMS == |sample|,
     * so the level the control sees is exactly [amplitude] and it is easy to reason about.
     */
    private fun frame(amplitude: Int): ShortArray =
        ShortArray(FRAME_SAMPLES) { amplitude.toShort() }

    /** RMS of a frame as [AsrGain] measures it, for asserting the boosted level. */
    private fun rms(frame: ShortArray): Double {
        var sumSq = 0.0
        for (v in frame) sumSq += v.toDouble() * v
        return kotlin.math.sqrt(sumSq / frame.size)
    }

    /** Push [n] frames of the same [amplitude] through [gain] and return the last boosted frame. */
    private fun feed(gain: AsrGain, n: Int, amplitude: Int): ShortArray {
        var last = frame(amplitude)
        repeat(n) { last = gain.apply(frame(amplitude)) }
        return last
    }

    @Test
    fun `a quiet capture is boosted to the recogniser's trained level`() {
        val gain = AsrGain()
        // The reported capture: rms 489, i.e. ~4x below the 2000 target the model was trained at.
        val out = feed(gain, 40, 489)
        assertTrue("gain should have climbed toward ~4x, was ${gain.current}", gain.current > 3.5)
        assertTrue("gain should not exceed the 6x cap, was ${gain.current}", gain.current <= 6.0)
        // The boosted frame should land near the target, not far below it as the 2.1x cap left it.
        assertTrue("boosted rms ${rms(out)} should approach 2000", rms(out) > 1700)
    }

    @Test
    fun `a lone loud frame cannot drag the boost down`() {
        val gain = AsrGain()
        // Settle on the quiet line first, so a real boost is in force before the cheer arrives.
        feed(gain, 40, 489)
        val settled = gain.current
        assertTrue("precondition: a boost should be in force, was $settled", settled > 3.5)

        // One stadium burst — 60x louder than the commentary — then straight back to speech.
        gain.apply(frame(30_000))
        feed(gain, 3, 489)
        assertTrue(
            "one loud frame must not pull the gain down (settled $settled, now ${gain.current})",
            gain.current > settled * 0.9,
        )
    }

    @Test
    fun `a sustained loud passage is followed down`() {
        val gain = AsrGain()
        feed(gain, 40, 489)
        val quiet = gain.current
        // Twelve frames is one full median window: once the loud level is the majority, the median
        // itself moves and the boost is released — the gain must not stay pinned to the quiet line.
        feed(gain, 12, 20_000)
        assertTrue(
            "a sustained loud passage should release the boost (was $quiet, now ${gain.current})",
            gain.current < quiet,
        )
    }

    @Test
    fun `frames below the gate hold the gain instead of tracking silence`() {
        val gain = AsrGain()
        feed(gain, 40, 489)
        val settled = gain.current
        // Room tone / silence carries no level to normalise: the estimate must be held, or every
        // word onset after a pause would be measured against the preceding silence.
        feed(gain, 6, 5)
        assertEquals("a pause must hold the gain, not move it", settled, gain.current, 1e-9)
    }

    @Test
    fun `the peak limit keeps a loud frame out of distortion`() {
        val gain = AsrGain()
        // Force a high running gain on a quiet line, then hand it a frame whose own peak, once
        // boosted, would breach the ceiling: the per-frame limit must scale it back below it.
        feed(gain, 40, 100)
        val out = gain.apply(frame(8_000))
        val maxSample = out.maxOf { kotlin.math.abs(it.toInt()) }
        assertTrue("peak $maxSample must stay under the 30000 ceiling", maxSample <= 30_000)
    }

    @Test
    fun `an already healthy frame is passed through untouched`() {
        val gain = AsrGain()
        val healthy = frame(2_000)
        val out = gain.apply(healthy)
        // At target level the control never amplifies, so the frame is returned as-is (no copy).
        assertSame("a frame at target level should not be copied", healthy, out)
        assertEquals(1.0, gain.current, 1e-9)
    }

    private companion object {
        /** 160 ms at 16 kHz — the frame size the pipeline slices to. */
        const val FRAME_SAMPLES = 2_560
    }
}