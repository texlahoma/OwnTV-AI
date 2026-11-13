package tv.own.owntv.features.aisubtitles

import kotlinx.coroutines.channels.Channel
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong

/**
 * One fixed-size 16 kHz mono PCM frame tagged with the queue [generation] it was offered in.
 * Frames with the same generation are contiguous audio; a generation change means frames in
 * between were discarded and the audio timeline jumped (see [RealtimePcmQueue.offer]).
 *
 * [capturedAtMs] is [android.os.SystemClock.elapsedRealtime] when the audio was sliced off the
 * renderer, carried through the whole pipeline so the moment the Chinese reaches the screen can be
 * measured against the moment its audio arrived — the end-to-end latency, rather than the sum of the
 * queue/model/round-trip estimates it used to be (see [PipelineStats.endToEndMs]).
 *
 * [presentAtMs] is the presentation-clock time at which this frame's audio will actually be heard.
 * With live audio held back in the sink it lies seconds in the future; it is what the caption
 * scheduler keys on, so a line is shown with its sound rather than as soon as it is produced (see
 * [CaptionTimeline]).
 */
internal data class PcmFrame(
    val samples: ShortArray,
    val generation: Long,
    val capturedAtMs: Long,
    val presentAtMs: Long = capturedAtMs,
)

/** Why the audio timeline jumped — the mirror of [RealtimePcmQueue.droppedOnOverflow]/`droppedOnFlush`. */
internal enum class JumpCause {
    /** The recognizer fell behind live audio and the queue jumped to the newest position. */
    OVERFLOW,

    /** The renderer flushed (seek, live-edge reposition, stream restart). */
    FLUSH,
}

/**
 * One timeline discontinuity, stamped with the [android.os.SystemClock.elapsedRealtime] it happened
 * at and its [cause].
 *
 * The counters alone answer "how many jumps", never "WHEN". A session that reported `jmp 7/1` with
 * the counts unchanged across two samples 121 s apart had all seven jumps inside the opening minutes
 * — but that could only be inferred from the samples, not read from the queue. Keeping the events
 * lets the same line be answered directly: the opening burst is the encoder/chunk warm-up, and a
 * later jump is a real stall worth chasing.
 */
internal data class JumpEvent(val atMs: Long, val cause: JumpCause)

/**
 * Bounded, non-blocking queue of live 16 kHz PCM frames between the ExoPlayer audio thread
 * (producer) and the ASR worker (consumer), tuned for the hard rule that **live captions must
 * follow the newest audio — stale captions are worse than a gap**.
 *
 * Overflow policy is deliberately NOT single-frame eviction. The previous implementation
 * dropped the single oldest frame per offer when full: on a recognizer that decodes even
 * marginally slower than real time (a 160 ms frame taking, say, 200 ms), the 3.8 s buffer
 * fills once and the queue then stays in a permanent steady state where every OTHER frame is
 * evicted. The recognizer still receives a frame every 40–200 ms, but each frame is taken from
 * a point 2–5× further apart on the real audio timeline — i.e. it hears speech played back at
 * 2–5× speed with a waveform discontinuity at every frame boundary. A streaming Zipformer
 * transducer decodes that as near-total silence with an occasional lucky word, which is exactly
 * the "chunk N advancing, blank ≫ words, partials frozen for minutes" failure.
 *
 * Instead, when the queue is full we discard the ENTIRE stale backlog in one jump, enqueue only
 * the newest frame, and bump [generation]. The consumer observes the generation change and
 * resets its recognizer stream at the discontinuity:
 *
 *  - frames inside one generation are always contiguous PCM (no time compression, no clicks);
 *  - a jump costs at most one encoder-chunk of context warm-up on the recognizer;
 *  - captions cover the most recent stretch of speech instead of recognising garbage forever.
 *
 * Threading: [offer] runs solely on the single audio-tap thread, [channel] is consumed by the
 * single ASR worker; the [Channel] itself bridges the two. [droppedFrames]/[generation] are
 * atomics only because the diagnostics status line reads them from a third thread.
 */
internal class RealtimePcmQueue(capacity: Int) {

    val channel: Channel<PcmFrame> = Channel(capacity)

    /** The frame ceiling; capacity × 160 ms is the worst caption lag this queue can ever hold. */
    private val capacity = capacity

    /** Total frames discarded by whole-backlog jumps for the lifetime of this queue/session. */
    val droppedFrames = AtomicLong(0)

    /**
     * Frames discarded, split by cause — the two sum to [droppedFrames], and telling them apart is
     * what makes a line like `drop 408 · jmp 53` answerable:
     *
     *  - [droppedOnOverflow] — the recognizer fell behind live audio and the queue jumped to the
     *    newest position. This is OUR throughput/latency fault, the one worth chasing.
     *  - [droppedOnFlush] — the renderer flushed (seek, live-edge reposition, stream restart) and the
     *    queued frames belong to a media position that no longer exists. Discarding them is correct
     *    and unavoidable; counting them as loss would inflate the backlog problem with live-stream
     *    repositioning that has nothing to do with the recognizer keeping up.
     */
    val droppedOnOverflow = AtomicLong(0)
    val droppedOnFlush = AtomicLong(0)

    /** Bumped on every whole-backlog jump; the value tagged onto the frames of the new stretch. */
    private val generation = AtomicLong(0)

    /** Jump counts by cause — the mirror of [droppedOnOverflow]/[droppedOnFlush]. */
    private val overflowJumps = AtomicLong(0)
    private val flushJumps = AtomicLong(0)

    // The last [JUMP_LOG_SIZE] jumps, newest last, with the time and cause the counts cannot carry
    // (see [JumpEvent]). Jumps are rare — a handful a session — so a synchronized deque costs nothing
    // on the audio thread and is the simplest thing that is correct across the three reading threads.
    private val jumpLog = ArrayDeque<JumpEvent>()

    // Approximate queued-frame depth, maintained by [offer] (producer) and [markConsumed]
    // (consumer). Diagnostic only — races of a frame or two are harmless — but the high-water
    // mark answers a key real-time question: how far behind live the recognizer ever got
    // (depth × 160 ms) on a device where overflow never quite tripped (drop 0 yet captions lag).
    private val depth = AtomicInteger(0)
    private val highWater = AtomicInteger(0)

    // Recent average depth, exponentially weighted, in frames. Deliberately not a session-cumulative
    // mean: the question it exists to answer is "does the backlog drain between stalls, or does it
    // park near the ceiling?" — and one bad opening minute poisons a cumulative mean for the rest of
    // the session, which is exactly how a 48-frame queue once looked healthy while captions sat 7 s
    // behind. The weight is set so the window is roughly the last [AVG_DEPTH_WINDOW] markConsumed
    // calls (~20 s at the consumer's 160 ms frame cadence).
    //
    // Floating point, not a fixed-point AtomicInteger: with integer arithmetic the update
    // `prev + (now - prev) / WINDOW` truncates to zero whenever the gap is under WINDOW, so a mean
    // that once reached 2 would sit at 2 for the rest of the session and read as a permanent backlog
    // that is not there. A plain @Volatile double is enough — [markConsumed] is the only writer (the
    // single ASR worker), and the status line only reads it.
    @Volatile private var avgDepthFrames = 0.0

    /**
     * Most frames ever queued at once this session; × 160 ms = the worst caption lag window. Capped
     * at [capacity] because that is the most the channel can physically hold: [depth] is an
     * unsynchronised diagnostic, and a producer-side overflow jump drains frames the consumer never
     * [markConsumed]s, so the raw counter can drift a frame or two above the ceiling. Without the cap
     * the status line reads the impossible `qmax 9/8`, which makes a saturated queue look like a
     * counter bug instead of the real signal it is.
     */
    val highWaterFrames: Int get() = minOf(highWater.get(), capacity)

    /**
     * Recent mean backlog depth, in frames — `2.4` means 2.4 frames (≈380 ms) behind live on
     * average over roughly the last 20 s of consumed audio.
     *
     * This is the meter that decides whether a larger [capacity] is safe. A capacity bump only buys
     * continuity if the queue actually drains between stalls: at a steady-state real-time factor
     * below 1 the mean must fall back toward 0, while a recognizer whose *effective* throughput
     * (native decode plus gain, segmentation and publishing — none of which `rtf` counts) sits at
     * or above 1 parks near the ceiling instead, and then every overflow jump discards the full
     * buffer rather than a sliver. `qmax` cannot tell those two apart; this can.
     */
    val recentAvgDepthFrames: Double get() = avgDepthFrames

    /** Total timeline discontinuities (overflow jumps + external [jump] calls) this session. */
    val generationJumps: Long get() = generation.get()

    /** Backlog jumps caused by overflow — the recognizer fell behind live audio. */
    val overflowJumpCount: Long get() = overflowJumps.get()

    /** Discontinuity jumps caused by the renderer flushing — seek / live-edge reposition. */
    val flushJumpCount: Long get() = flushJumps.get()

    /** The most recent jumps (up to [JUMP_LOG_SIZE]), newest last: when each happened and why. */
    val recentJumps: List<JumpEvent> get() = synchronized(jumpLog) { jumpLog.toList() }

    private fun recordJump(atMs: Long, cause: JumpCause) {
        synchronized(jumpLog) {
            jumpLog.addLast(JumpEvent(atMs, cause))
            while (jumpLog.size > JUMP_LOG_SIZE) jumpLog.removeFirst()
        }
    }

    /**
     * Enqueue [frame] without blocking. Fast path: buffered while the consumer keeps up. On
     * overflow, drain every queued (now stale) frame, bump the generation and keep only [frame].
     */
    fun offer(frame: ShortArray, capturedAtMs: Long, presentAtMs: Long = capturedAtMs) {
        val current = generation.get()
        if (channel.trySend(PcmFrame(frame, current, capturedAtMs, presentAtMs)).isSuccess) {
            val d = depth.incrementAndGet()
            highWater.accumulateAndGet(d) { a, b -> if (b > a) b else a }
            return
        }
        // The recognizer has fallen behind by the whole buffer window. Evict the backlog in one
        // jump instead of one frame per offer (see class doc): count the evictions for the
        // on-screen diagnostics, tag the surviving frame with the new generation so the consumer
        // resets its recognizer at the timeline discontinuity.
        var cleared = 0
        while (channel.tryReceive().isSuccess) cleared++
        droppedFrames.addAndGet(cleared.toLong())
        droppedOnOverflow.addAndGet(cleared.toLong())
        overflowJumps.incrementAndGet()
        recordJump(capturedAtMs, JumpCause.OVERFLOW)
        val next = generation.incrementAndGet()
        depth.set(1)
        // True depth: the backlog just cleared IS how deep the queue got (a concurrent consumer can
        // only have left fewer), capped at capacity because the channel cannot hold more. The old
        // `cleared + 1` counted the frame we keep on top of a full queue, which is why a completely
        // full queue read as the impossible `qmax 9` of 8.
        val newHigh = highWater.get().coerceAtLeast(cleared.coerceAtMost(capacity))
        highWater.set(newHigh)
        // A concurrent consumer can only have made MORE room, so this offer succeeds; ignore the
        // theoretical failure (the frame is simply skipped and the next offer retries).
        channel.trySend(PcmFrame(frame, next, capturedAtMs, presentAtMs))
    }

    /**
     * Consumer hook: one frame left the queue, so lower the diagnostic depth. Also the sampling
     * point for [recentAvgDepthFrames] — it runs once per consumed frame, which is the cadence that
     * average is defined over, and sampling only here keeps the EWMA off the producer thread.
     */
    fun markConsumed() {
        val now = depth.updateAndGet { if (it > 0) it - 1 else 0 }
        avgDepthFrames += (now - avgDepthFrames) / AVG_DEPTH_WINDOW
    }

    /**
     * Producer-side timeline break NOT caused by backlog: the audio renderer flushed (seek, live
     * edge reposition, stream restart). Everything queued is now on the old timeline — discard it
     * exactly like an overflow jump and bump the generation so the consumer resets the recognizer
     * before the next frame. Unlike overflow this keeps NO frame: no audio has arrived at the new
     * position yet. Non-blocking; safe to call from the audio thread.
     */
    fun jump(atMs: Long = 0L) {
        var cleared = 0
        while (channel.tryReceive().isSuccess) cleared++
        droppedFrames.addAndGet(cleared.toLong())
        droppedOnFlush.addAndGet(cleared.toLong())
        flushJumps.incrementAndGet()
        recordJump(atMs, JumpCause.FLUSH)
        depth.set(0)
        generation.incrementAndGet()
    }

    private companion object {
        /**
         * EWMA weight for [avgDepthFrames]. The divisor is the window length in samples:
         * 128 consumed frames is ~20 s of audio at the 160 ms frame the consumer is fed, which is
         * long enough to smooth one stall out and short enough for "is it draining?" to answer
         * within a viewing minute.
         */
        const val AVG_DEPTH_WINDOW = 128

        /** How many recent [JumpEvent]s [recentJumps] keeps; a session sees a handful at most. */
        const val JUMP_LOG_SIZE = 32
    }
}
