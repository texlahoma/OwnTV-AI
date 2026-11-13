package tv.own.owntv.features.aisubtitles

/**
 * Bounded gain the pipeline applies to every 16 kHz frame on its way to the recognizer.
 *
 * ## Why this exists
 *
 * A live programme can reach the recognizer tens of dB below the level its feature extractor was
 * trained on. The on-screen meter showed exactly that on a 48 kHz/8ch broadcast: `rms 299` of 32767
 * (≈ −41 dBFS) — a level at which a streaming transducer emits blank for most frames and drops the
 * rest of the words, so what finally reached the translator was a fragment with holes in it. The
 * capture path is where the level goes: [tv.own.owntv.player.PlaybackAudioTap] folds 6/8-channel
 * layouts to mono by *averaging* every speaker that carries signal, which dilutes a dialogue-heavy
 * centre with quieter surrounds, and a quietly mixed programme is quiet to begin with.
 *
 * ## What it deliberately does not do
 *
 * The control is one-sided: the running gain it settles on only ever amplifies, and only up to
 * [MAX_GAIN], so a healthy-level capture is never pumped up. The one place a frame can come out
 * QUIETER than it went in is the peak limit below — a frame whose own peak would breach
 * [PEAK_CEILING] is scaled back down to it, which is why `applied` may legitimately be below unity
 * (a frame at peak 32000 gets ≈0.94) even though the control never is. Uniform gain cannot change
 * the signal-to-noise ratio of a frame, so the boost creates no hallucination risk of its own; the
 * ceiling, not the boost, is what keeps a loud frame out of distortion.
 *
 * ## The level is a median over time, not the frame in hand
 *
 * The gain used to be computed from the level of the frame being processed. On a live match that
 * fails systematically: a stadium's cheer arrives in a single 160 ms frame and is far louder than the
 * commentary, so every burst pulled the estimate down and the speech that followed it stayed
 * under-boosted. Measured on a real broadcast as `rms 489` with `gain 2.1x` — a 4×-quiet capture held
 * at 2.1×, i.e. the model fed ≈ −30 dBFS while it was trained at ≈ −23 dBFS, which is exactly where a
 * rare word like a player's name loses its acoustic evidence.
 *
 * The level is therefore the MEDIAN of the last [LEVEL_WINDOW_FRAMES] gated frames (~2 s). A cheer is
 * a minority of that window, so it barely moves the median, while a genuine change of programme level
 * still moves it within the window. Against the same broadcast this lifts the gain to the ~4× the
 * capture actually needs, i.e. the recognizer to its trained level; when the crowd genuinely carries
 * more than half the frames the median follows it down, which is right — there is no speech to lift.
 *
 * Both directions of the running gain are then smoothed ([FALL_SMOOTHING] faster than
 * [RISE_SMOOTHING]): the fall tracks the programme down within half a second, while a single quiet
 * word cannot pump the whole line up. Clipping is not what the estimate protects against in any case —
 * [PEAK_CEILING] already scales any single frame whose own peak would breach it, whatever the
 * estimate says. Uniform gain cannot change a frame's signal-to-noise ratio, so the deeper boost
 * creates no hallucination risk of its own; only the per-frame ceiling, the one place this class
 * attenuates, keeps a loud frame out of distortion.
 *
 * Frames below [GATE_RMS] carry no level to normalise (silence, room tone) and hold the current gain
 * instead of recomputing it: letting the gain fall during a pause would leave every word onset
 * under-boosted, because the onset's own frame is measured while the preceding silence is still the
 * estimate. For the same reason they do not enter [levels] either — a pause must hold the estimate,
 * not slide it toward silence. See the class doc.
 *
 * Applied on the ASR worker, not the audio thread — see [AiSubtitleManager.consumePcm].
 */
internal class AsrGain(
    private val targetRms: Double = TARGET_RMS,
    private val maxGain: Double = MAX_GAIN,
) {

    /** Gain applied to the frame most recently returned; drives the on-screen `gain` meter. */
    var current: Double = 1.0
        private set

    // Ring of the most recent GATED frame levels (see the class doc). Only read/written here on the
    // ASR worker thread, so it needs no synchronisation.
    private val levels = DoubleArray(LEVEL_WINDOW_FRAMES)
    // Reused staging area for the median, so sorting one frame's estimate allocates nothing: this
    // runs on the same worker that has to keep draining the live PCM queue (see [AsrGain] doc and
    // [tv.own.owntv.features.aisubtitles.AiSubtitleManager.consumePcm]).
    private val levelScratch = DoubleArray(LEVEL_WINDOW_FRAMES)
    private var levelNext = 0
    private var levelCount = 0

    /**
     * Record [frameRms] as this frame's level and return the median of the frames recorded so far.
     * The window fills over its first [LEVEL_WINDOW_FRAMES] gated frames and then slides, so the
     * estimate is the middle of the recent past rather than the frame in hand — which is what makes
     * a lone loud frame (a stadium cheer) unable to drag the gain down (see the class doc).
     */
    private fun medianLevel(frameRms: Double): Double {
        levels[levelNext] = frameRms
        levelNext = (levelNext + 1) % LEVEL_WINDOW_FRAMES
        if (levelCount < LEVEL_WINDOW_FRAMES) levelCount++
        System.arraycopy(levels, 0, levelScratch, 0, levelCount)
        java.util.Arrays.sort(levelScratch, 0, levelCount)
        return levelScratch[levelCount / 2]
    }

    /** Return [frame], scaled towards [targetRms] when it arrived below speech level. */
    fun apply(frame: ShortArray): ShortArray {
        if (frame.isEmpty()) return frame
        var sumSq = 0.0
        var peak = 0
        for (v in frame) {
            val magnitude = if (v < 0) -v.toInt() else v.toInt()
            if (magnitude > peak) peak = magnitude
            sumSq += v.toDouble() * v
        }
        val rms = kotlin.math.sqrt(sumSq / frame.size)
        if (rms >= GATE_RMS) {
            // The MEDIAN of the window, not this frame, is the level the gain is set from (see the
            // class doc): a stadium cheer is a single frame among many, so it cannot drag the
            // estimate down and leave the commentary that follows it under-boosted.
            val desired = (targetRms / medianLevel(rms)).coerceIn(1.0, maxGain)
            // Both directions are a first-order lag; the fall is the faster of the two. One frame is
            // 160 ms, six arrive per second, so the rise settles in ~1 s: long enough that a single
            // quiet word cannot pump the line, short enough to follow a speaker into a quieter
            // passage.
            current = if (desired < current)
                current + (desired - current) * FALL_SMOOTHING
            else
                current + (desired - current) * RISE_SMOOTHING
        }
        // Peak limit, per frame and independent of the running estimate: whatever the control has
        // settled on, no sample is pushed past [PEAK_CEILING] (which is why the applied gain may be
        // below unity here even though the control never is).
        val applied = if (peak > 0) minOf(current, PEAK_CEILING / peak.toDouble()) else current
        if (applied in 1.0..GAIN_EPSILON) return frame
        val out = ShortArray(frame.size)
        for (i in frame.indices) {
            out[i] = (frame[i] * applied).toInt().coerceIn(-32768, 32767).toShort()
        }
        return out
    }

    private companion object {
        /** ≈ −23 dBFS — the level speech recognizers are trained on. */
        const val TARGET_RMS = 2000.0

        /** ≤ +15.6 dB. A correction for a quiet capture, not a compressor. */
        const val MAX_GAIN = 6.0

        /** Frames the level median is taken over: 12 × 160 ms ≈ 2 s (see the class doc). */
        const val LEVEL_WINDOW_FRAMES = 12

        /** ≈ −55 dBFS: below this a frame is silence or room tone, not quiet speech. */
        const val GATE_RMS = 60.0

        /**
         * Weight of one frame's measurement when the gain is RISING. The falling direction uses the
         * faster [FALL_SMOOTHING] instead (see [apply] and the class doc).
         */
        const val RISE_SMOOTHING = 0.15

        /**
         * Weight of one frame's measurement when the gain is FALLING. Higher than [RISE_SMOOTHING] so
         * the estimate still follows the programme down within half a second, but far from the
         * instantaneous snap that let a single crowd burst hold the whole line quiet (see [apply]).
         */
        const val FALL_SMOOTHING = 0.5

        /**
         * Highest sample magnitude the gain may produce, ≈ −0.8 dBFS: a peak limiter, so a frame is
         * never scaled past this even when the running gain would (see [apply]). Headroom below the
         * 32767 clamp, so the model sees no flat-topped waveform.
         */
        const val PEAK_CEILING = 30_000

        /** Gain in [1, this] is unity, so an untouched frame is returned without a copy. */
        const val GAIN_EPSILON = 1.001
    }
}
