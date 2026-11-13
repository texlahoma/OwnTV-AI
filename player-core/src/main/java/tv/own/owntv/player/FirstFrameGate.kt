package tv.own.owntv.player

import java.util.concurrent.atomic.AtomicLong
import java.util.concurrent.atomic.AtomicReference

/**
 * Decides which rendered frame is the first frame of a tune, for the "first frame after Nms" timing.
 *
 * The live engine reuses one ExoPlayer and one surface across channels. `play()` starts the new tune on
 * the main thread at once, but the previous channel keeps rendering on the playback thread until that
 * thread gets to the new media source: tens of milliseconds, longer on a slow TV. A frame from the old
 * channel in that window used to be timed as the new channel's first frame ("first frame after 11ms",
 * seconds before the new stream had even answered).
 *
 * So a tune is counted in three steps:
 * 1. [arm] on the main thread when the tune starts, with its start time and claimed zap press. Each call
 *    replaces the previous tune outright, opened or not.
 * 2. [open] on the playback thread, from a message sent after the new source was handed to the player.
 *    The playback thread handles commands in order, so everything it renders after that message comes
 *    from the new source. An [open] for a superseded tune is ignored.
 * 3. [take] from the frame callback: the tune, for the first frame after [open]; null for every other
 *    frame, however many retries re-open the same tune.
 *
 * The tune's numbers travel inside the gate, swapped atomically, so a frame can never pair one tune's
 * gate with another tune's start time. Measurement only: nothing about playback reads this.
 */
internal class FirstFrameGate {

    /** One tune as the timing line needs it: [startedMs] and [pressAtMs] are elapsedRealtime. */
    data class Tune(val generation: Long, val startedMs: Long, val pressAtMs: Long?)

    private class Slot(val tune: Tune, val open: Boolean)

    private val generation = AtomicLong(0L)
    // null = no tune waiting for its first frame (none yet, or already taken).
    private val slot = AtomicReference<Slot?>(null)

    /** The generation of the newest [arm]: what that tune's [open] must pass. */
    val current: Long get() = generation.get()

    /** A new tune starts: frames from here on are not counted until [open]. Returns its generation. */
    fun arm(startedMs: Long, pressAtMs: Long?): Long {
        val gen = generation.incrementAndGet()
        slot.set(Slot(Tune(gen, startedMs, pressAtMs), open = false))
        return gen
    }

    /** The player has applied the source of tune [gen]: its frames count from now. */
    fun open(gen: Long) {
        while (true) {
            val s = slot.get() ?: return
            if (s.tune.generation != gen || s.open) return
            if (slot.compareAndSet(s, Slot(s.tune, open = true))) return
        }
    }

    /** The current tune, for its first frame after [open]; null for every other frame. */
    fun take(): Tune? {
        while (true) {
            val s = slot.get() ?: return null
            if (!s.open) return null
            if (slot.compareAndSet(s, null)) return s.tune
        }
    }
}
