package tv.own.owntv.features.aisubtitles.whisper

import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.floor
import kotlin.math.roundToInt
import kotlin.math.sin

/**
 * Normalises decoded PCM to the 16 kHz the recognizers expect.
 *
 * [TapAudioSink] hands over the stream's native sample rate — TV audio is almost always
 * 44100 or 48000 Hz — but Sherpa-ONNX's feature extractor and Silero VAD are built around 16000.
 * Feeding a 48 kHz buffer labelled as such still makes every model parse it *as if it were* 16 kHz,
 * shifting the whole spectrum up by 3× and destroying accuracy. Every sample the AI pipeline feeds
 * to a recognizer must therefore be resampled to exactly [TARGET_RATE] first, and the recognizers
 * then always see 16 kHz.
 *
 * Two implementations live here:
 *
 *  - [StreamResampler16k] is the session-scoped streaming converter the live pipeline uses. PCM
 *    arrives in decoder-sized chunks whose lengths are not multiples of the decimation ratio, so it
 *    carries the sub-group tail between calls — a stateless per-chunk conversion silently drops up
 *    to ratio−1 samples on EVERY callback (about 0.5 % of all 48 kHz audio at 2048-frame chunks,
 *    hundreds of clipped frames per second), which the live queue then hands over as a subtly
 *    gapped stream.
 *  - [to16k] is the stateless one-shot helper (tests, one-off conversions).
 *
 * Decimation quality matters for ASR: anything above the 8 kHz the output can represent folds back
 * across 0–8 kHz as aliasing hiss, and a transducer then emits blank for nearly every frame. The
 * decimation stage alone is NOT an anti-alias filter — a boxcar mean is only −3.5 dB at 8 kHz and
 * still −9.5 dB at 12 kHz, and linear interpolation is worse — so a stadium's crowd noise, whistles
 * and sibilance between 8 and 16 kHz used to arrive inside the speech band at almost full strength,
 * where [AsrGain] then amplified it up to 6×. Both paths therefore run the same [FirLowPass] first,
 * with its stopband placed BELOW the output Nyquist ([LOWPASS_CUTOFF_HZ]), so the image band is
 * suppressed before it can fold; the boxcar/interpolation stage then only has to decimate what is
 * left. The filter is ~6 M multiply-accumulates per second at 48 kHz on a 127-tap kernel, which is
 * nothing beside the acoustic model it feeds.
 */
object PcmResampler {
    const val TARGET_RATE = 16_000

    /**
     * Design cutoff of the anti-alias stage, in Hz — chosen so its STOPBAND starts below the 8 kHz the
     * 16 kHz output can represent, since anything that folds is what the filter exists to remove. On a
     * 127-tap Hamming kernel the transition band is about 3.3/127 of the input rate (≈1.2 kHz at
     * 48 kHz), so the stopband begins ≈7.6 kHz and the passband ends ≈6.4 kHz: above the ~4 kHz speech
     * models use, below the fold-over point. The same cutoff suits 44.1 and 32 kHz input.
     */
    private const val LOWPASS_CUTOFF_HZ = 7_000.0

    /** Kernel length of the anti-alias filter; odd, so it has a whole-sample symmetric (linear-phase) centre. */
    private const val LOWPASS_TAPS = 127

    /** One-shot resample of mono 16-bit PCM from [inputRate] to 16 kHz; passthrough at target. */
    fun to16k(samples: ShortArray, inputRate: Int): ShortArray {
        if (inputRate == TARGET_RATE || samples.isEmpty()) return samples
        val ratio = inputRate.toDouble() / TARGET_RATE.toDouble()
        if (ratio <= 0.0) return samples
        // Anti-alias BEFORE decimating (see the class doc): decimation cannot tell signal from image.
        val band = FirLowPass(lowPassTaps(inputRate)).process(samples)
        return if (inputRate % TARGET_RATE == 0) {
            integerDecimate(band, inputRate / TARGET_RATE)
        } else {
            fractionalResample(band, ratio)
        }
    }

    /**
     * Windowed-sinc low-pass coefficients for input at [sampleRate]: a sinc at [LOWPASS_CUTOFF_HZ]
     * truncated to [LOWPASS_TAPS] taps and shaped by a Hamming window to control the stopband ripple,
     * normalised to unity gain at DC so the filter changes the spectrum and not the level.
     */
    internal fun lowPassTaps(sampleRate: Int): FloatArray {
        val n = LOWPASS_TAPS
        val taps = FloatArray(n)
        val fc = LOWPASS_CUTOFF_HZ / sampleRate // cycles per sample, 0..0.5
        val centre = (n - 1) / 2
        var sum = 0.0
        for (i in 0 until n) {
            val x = i - centre
            val sinc = if (x == 0) 2.0 * fc else sin(2.0 * PI * fc * x) / (PI * x)
            val window = 0.54 - 0.46 * cos(2.0 * PI * i / (n - 1)) // Hamming
            val tap = sinc * window
            taps[i] = tap.toFloat()
            sum += tap
        }
        for (i in 0 until n) taps[i] = (taps[i] / sum).toFloat()
        return taps
    }

    /**
     * Integrate-and-dump decimation by integer factor [r]: output[i] = mean(input[i*r … i*r+r−1]).
     * The trailing group shorter than r is dropped (the streaming [StreamResampler16k] carries it
     * into the next call instead). The mean is only a decimator: the anti-aliasing is done by the
     * [FirLowPass] stage that runs first, because a boxcar alone leaves the image band almost intact
     * (−3.5 dB at 8 kHz).
     */
    internal fun integerDecimate(samples: ShortArray, r: Int): ShortArray {
        if (r <= 1) return samples
        val outLen = samples.size / r
        if (outLen == 0) return ShortArray(0)
        val out = ShortArray(outLen)
        var src = 0
        for (i in 0 until outLen) {
            var sum = 0
            repeat(r) { sum += samples[src++].toInt() }
            out[i] = (sum / r).toShort()
        }
        return out
    }

    /** Linear-interpolation resample for fractional ratios; see [to16k]. */
    internal fun fractionalResample(samples: ShortArray, ratio: Double): ShortArray {
        val outLen = (samples.size / ratio).toInt().coerceAtLeast(1)
        val out = ShortArray(outLen)
        val last = samples.lastIndex
        var src = 0.0
        for (i in 0 until outLen) {
            val idx = src.toInt()
            val frac = src - idx
            val i0 = idx.coerceIn(0, last)
            val i1 = (idx + 1).coerceIn(0, last)
            out[i] = (samples[i0] + ((samples[i1] - samples[i0]) * frac))
                .toInt().coerceIn(-32768, 32767).toShort()
            src += ratio
        }
        return out
    }
}

/**
 * Linear-phase FIR low-pass, run at the input rate ahead of the decimator (see [PcmResampler]).
 *
 * One instance per pipeline session: it carries the last `taps-1` input samples, so a chunk boundary
 * is not a filter boundary and the result is bit-identical to filtering the whole stream at once. The
 * history is a ring buffer indexed by the newest sample, so each output costs exactly `taps`
 * multiply-accumulates with no per-sample allocation.
 */
class FirLowPass(private val taps: FloatArray) {
    private val n = taps.size
    private val history = FloatArray(n)
    private var newest = 0

    /** Filter [input]; returns one output sample per input sample, delayed by `(taps-1)/2`. */
    fun process(input: ShortArray): ShortArray {
        if (input.isEmpty()) return input
        val out = ShortArray(input.size)
        for (i in input.indices) {
            history[newest] = input[i].toFloat()
            var acc = 0f
            var idx = newest
            for (k in 0 until n) {
                acc += taps[k] * history[idx]
                if (--idx < 0) idx = n - 1
            }
            if (++newest == n) newest = 0
            out[i] = acc.roundToInt().coerceIn(-32768, 32767).toShort()
        }
        return out
    }
}

/**
 * Streaming, chunk-boundary-safe 16 kHz converter. Create ONE per pipeline session (like the frame
 * slicer) and push every decoded chunk in order. Input-rate changes (an HLS variant switching
 * 48 kHz↔44.1 kHz) restart the internal phase, since the old tail cannot join the new rate.
 */
class StreamResampler16k {
    private var carry = ShortArray(0)
    private var inputRate = 0
    // Anti-alias stage for the current input rate, rebuilt when the rate changes (its kernel depends on
    // the rate) and dropped by [reset]; null until the first chunk arrives.
    private var lowPass: FirLowPass? = null
    // Fractional position of the next output sample relative to the start of the next
    // carry+input combination; only used on the fractional-ratio (44.1 kHz) path.
    private var phase = 0.0

    /** Drop the carried tail and restart phase — used when the audio timeline jumps (renderer flush). */
    fun reset() {
        carry = ShortArray(0)
        phase = 0.0
        inputRate = 0
        lowPass = null
    }

    fun push(samples: ShortArray, sampleRate: Int): ShortArray {
        if (samples.isEmpty()) return ShortArray(0)
        if (sampleRate == PcmResampler.TARGET_RATE) {
            // Passthrough must not strand an earlier rate's tail; a rate change discards it.
            if (inputRate != sampleRate) {
                carry = ShortArray(0); phase = 0.0; inputRate = sampleRate; lowPass = null
            }
            return samples
        }
        if (sampleRate <= 0) return samples
        if (inputRate != sampleRate) {
            carry = ShortArray(0)
            phase = 0.0
            inputRate = sampleRate
            // The kernel depends on the input rate, so a rate change (and a first chunk) rebuilds it.
            lowPass = FirLowPass(PcmResampler.lowPassTaps(sampleRate))
        }
        // Anti-alias FIRST (see [PcmResampler]): everything below works on the band-limited signal, and
        // the filter's own history carries the chunk boundary exactly as [carry] does for decimation.
        val band = lowPass?.process(samples) ?: samples
        return if (sampleRate % PcmResampler.TARGET_RATE == 0) {
            pushInteger(band, sampleRate / PcmResampler.TARGET_RATE)
        } else {
            pushFractional(band, sampleRate.toDouble() / PcmResampler.TARGET_RATE)
        }
    }

    private fun pushInteger(samples: ShortArray, r: Int): ShortArray {
        if (r <= 1) return samples
        val combined = if (carry.isEmpty()) samples else carry + samples
        val outLen = combined.size / r
        val out = ShortArray(outLen)
        var src = 0
        for (i in 0 until outLen) {
            var sum = 0
            repeat(r) { sum += combined[src++].toInt() }
            out[i] = (sum / r).toShort()
        }
        // Carry the incomplete final group verbatim; it joins the next chunk's head so group phase
        // never restarts at a chunk boundary (no clipped samples).
        carry = combined.copyOfRange(outLen * r, combined.size)
        return out
    }

    private fun pushFractional(samples: ShortArray, ratio: Double): ShortArray {
        val combined = if (carry.isEmpty()) samples else carry + samples
        val out = ArrayList<Short>((combined.size / ratio).toInt() + 1)
        var p = phase
        // Emit only while BOTH interpolation neighbours are real samples (idx+1 inside the buffer).
        // A position landing on the final sample is deferred into the next call via the carry below
        // instead of being interpolated against a clamped copy of itself (one edge tick per chunk).
        while (p < combined.size - 1) {
            val idx = floor(p).toInt()
            val frac = p - idx
            out.add(
                (combined[idx] + ((combined[idx + 1] - combined[idx]) * frac))
                    .toInt().coerceIn(-32768, 32767).toShort(),
            )
            p += ratio
        }
        // Keep every input sample the NEXT output still needs as its i0 (floor of the first
        // un-emitted position). Phase is stored relative to where the NEXT combined buffer starts:
        // the surviving carry's first sample when something remains, or — when the stride skipped
        // past the buffer end — the first sample of the next chunk (phase may then be > 1).
        val keepFrom = floor(p).toInt()
        if (keepFrom < combined.size) {
            phase = p - keepFrom
            carry = combined.copyOfRange(keepFrom, combined.size)
        } else {
            phase = p - combined.size
            carry = ShortArray(0)
        }
        return ShortArray(out.size) { out[it] }
    }
}
