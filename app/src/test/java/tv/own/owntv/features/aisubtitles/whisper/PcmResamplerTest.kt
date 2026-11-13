package tv.own.owntv.features.aisubtitles.whisper

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.PI
import kotlin.math.sqrt

/**
 * Covers the live-pipeline resampling invariants behind the real-time subtitle feed:
 *  - 48 kHz→16 kHz is a 3:1 integrate-and-dump (mean), not sample dropping — dropping folds the
 *    8–16 kHz image band over speech as aliasing hiss, which a transducer answers with blanks;
 *  - decoder chunks are not multiples of 3, so the streaming converter must carry the tail across
 *    calls (the old stateless per-chunk call clipped up to 2 samples per callback);
 *  - 44.1 kHz fractional conversion stays phase-continuous across chunk boundaries.
 */
class PcmResamplerTest {

    private fun rms(s: ShortArray): Double {
        if (s.isEmpty()) return 0.0
        var sum = 0.0
        for (v in s) { val d = v.toDouble(); sum += d * d }
        return sqrt(sum / s.size)
    }

    /** Naive "keep every 3rd sample" 48k→16k — the aliasing behaviour we must NOT have. */
    private fun naiveDrop3(s: ShortArray): ShortArray {
        val out = ShortArray(s.size / 3)
        for (i in out.indices) out[i] = s[i * 3]
        return out
    }

    @Test
    fun oneShotIntegerDecimationPreservesConstantLevel() {
        val input = ShortArray(48_000) { 12_000 }
        val out = PcmResampler.to16k(input, 48_000)
        assertEquals(16_000, out.size)
        // The anti-alias FIR starts from zeroed history, so its first samples are a partial sum of
        // the kernel; the full 127-sample window is only covered once the input index reaches
        // taps-1, i.e. after (taps-1)/3 decimated outputs. Past that a constant level passes through
        // unchanged. Allow 1 LSB for the float tap normalisation.
        val transientOutputs = (PcmResampler.lowPassTaps(48_000).size - 1) / 3 + 2
        assertTrue(
            out.drop(transientOutputs).all { kotlin.math.abs(it.toInt() - 12_000) <= 1 },
        )
    }

    @Test
    fun oneShotSixteenKilohertzPassesThroughUnchanged() {
        val input = shortArrayOf(1, 2, 3, 4)
        assertTrue(input === PcmResampler.to16k(input, 16_000))
    }

    @Test
    fun meanDecimationAttenuatesTheImageBandWhereNaiveDroppingDoesNot() {
        // A 12 kHz tone sampled at 48 kHz: n*pi/2 cycle per sample (0,+A,0,-A,…).
        // Naive 3:1 decimation reproduces it at full amplitude at the 4 kHz image frequency;
        // integrate-and-dump (mean of each group of 3) reduces it to one third.
        val n = 48_000
        val input = ShortArray(n) { (10_000.0 * kotlin.math.sin(PI * 0.5 * it)).toInt().toShort() }

        val naive = naiveDrop3(input)
        val mean = PcmResampler.to16k(input, 48_000)

        assertEquals(16_000, mean.size)
        // ~9.5 dB suppression vs the aliased dropper; allow margin for rounding.
        assertTrue("naive rms=${rms(naive)} mean rms=${rms(mean)}", rms(mean) < 0.5 * rms(naive))
    }

    @Test
    fun streamingConverterLosesNoSamplesAcrossChunkBoundariesAt48k() {
        // MediaCodec hands over ~2048-frame chunks, never multiples of the 3:1 group: a stateless
        // per-chunk conversion dropped 0..2 samples each callback. One second of 48 kHz audio in
        // 2048-frame chunks must still yield exactly 16000 16 kHz samples.
        val rs = StreamResampler16k()
        val total = ShortArray(48_000) { (it % 101 - 50).toShort() }
        var produced = 0
        var pos = 0
        while (pos < total.size) {
            val end = minOf(pos + 2_048, total.size)
            produced += rs.push(total.copyOfRange(pos, end), 48_000).size
            pos = end
        }
        assertEquals(16_000, produced)
    }

    @Test
    fun streamingConverterEmitsIdenticalStreamToWholeBufferDecimation() {
        // Every emitted sample must equal the whole-buffer mean decimation at the same index:
        // tail carry changes chunk partition, never output values.
        val rs = StreamResampler16k()
        val total = ShortArray(48_000) { ((it * 37) % 31_000 - 15_500).toShort() }
        val expected = PcmResampler.to16k(total, 48_000)
        val chunks = ArrayList<ShortArray>()
        var pos = 0
        while (pos < total.size) {
            val end = minOf(pos + 2_047, total.size) // deliberately not a multiple of 3
            chunks.add(rs.push(total.copyOfRange(pos, end), 48_000))
            pos = end
        }
        val joined = ShortArray(chunks.sumOf { it.size })
        var w = 0
        for (c in chunks) { c.copyInto(joined, w); w += c.size }
        assertEquals(expected.size, joined.size)
        for (i in expected.indices) assertEquals("index $i", expected[i], joined[i])
    }

    @Test
    fun fractionalRatioStaysPhaseContinuousAcrossChunks() {
        // 44.1 kHz→16 kHz (ratio 2.75625): a second of chunked audio converts to ~16000 samples.
        // Phase continuity means chunking is INVISIBLE — the anti-alias FIR's history and the
        // fractional walk's phase are both carried across the boundary, so the chunked stream must be
        // sample-for-sample identical to converting the whole buffer in one call.
        val total = ShortArray(44_100) { ((it * 29) % 20_000 - 10_000).toShort() }
        val whole = StreamResampler16k().push(total, 44_100)

        val rs = StreamResampler16k()
        val chunks = ArrayList<ShortArray>()
        var pos = 0
        while (pos < total.size) {
            val end = minOf(pos + 1_000, total.size)
            chunks.add(rs.push(total.copyOfRange(pos, end), 44_100))
            pos = end
        }
        val produced = chunks.sumOf { it.size }
        assertTrue("produced=$produced", produced in 15_998..16_001)
        val joined = ShortArray(produced)
        run { var w = 0; for (c in chunks) { c.copyInto(joined, w); w += c.size } }
        assertEquals(whole.size, joined.size)
        // 1 LSB: the fractional walk's phase is carried as a double and re-based at each chunk
        // boundary (`p - floor(p)`), so its FP rounding differs in the last place from the
        // whole-buffer walk and a sample on an interpolation ramp can truncate to the neighbour.
        for (m in whole.indices) {
            assertTrue(
                "index $m whole=${whole[m]} chunked=${joined[m]}",
                kotlin.math.abs(whole[m].toInt() - joined[m].toInt()) <= 1,
            )
        }

        // Constant level survives the filter and interpolation regardless of phase.
        val flat = StreamResampler16k()
        var flatOut = 0
        repeat(45) { flatOut += flat.push(ShortArray(980) { 8_000 }, 44_100).size }
        assertTrue(flatOut in 15_990..16_010)
    }

    @Test
    fun inputRateChangeRestartsCarryWithoutDroppingNewStream() {
        val rs = StreamResampler16k()
        rs.push(ShortArray(2_048) { 100 }, 48_000)
        // Mid-stream switch to 16 kHz: passthrough, no stale 48 kHz tail attached.
        val out = rs.push(shortArrayOf(1, 2, 3, 4), 16_000)
        assertArrayEqualsContent(shortArrayOf(1, 2, 3, 4), out)
    }

    private fun assertArrayEqualsContent(expected: ShortArray, actual: ShortArray) {
        assertEquals(expected.size, actual.size)
        for (i in expected.indices) assertEquals(expected[i], actual[i])
    }
}
