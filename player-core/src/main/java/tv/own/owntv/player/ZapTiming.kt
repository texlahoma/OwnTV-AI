package tv.own.owntv.player

/**
 * Channel-change timing for the diagnostics log: how long from a CH+/− key press until the zapped
 * channel's picture, whichever engine plays it.
 *
 * The app marks the press ([markPress]). The engine **claims** it when the tune it caused starts
 * ([claim], from ExoPlayer's `play()` or mpv's `loadUrl`), keeps it with that tune, and times its own
 * first frame against it. Claiming at the start, not at the first frame, is what keeps a press on its own
 * tune: a channel that never shows a picture (a radio stream, a failed open) used to leave its press
 * lying around for the next unrelated tune to read. A press is claimed at most once, and only within
 * [claimWindowMs] of being made: the app's zap wait plus the lookup before `play()` is well under a second,
 * so anything older did not cause this tune. A deliberate pick (list, guide, Home) calls [clear], so it is
 * never charged to a CH+/− press still waiting out its delay.
 *
 * Measurement only: nothing here changes playback. Thread-safe: presses and claims arrive on the main
 * thread, but nothing else needs to know that.
 */
class ZapTiming(private val claimWindowMs: Long = CLAIM_WINDOW_MS) {

    private var pressAtMs: Long? = null

    /** A zap key moved the channel at [nowMs] (elapsedRealtime). A newer press replaces an unclaimed one. */
    @Synchronized
    fun markPress(nowMs: Long) {
        pressAtMs = nowMs
    }

    /** The unclaimed press for the tune starting at [nowMs], consuming it; null when there is none, or it
     *  is too old (or from a clock that went backwards) to have caused this tune. */
    @Synchronized
    fun claim(nowMs: Long): Long? {
        val at = pressAtMs ?: return null
        pressAtMs = null
        return at.takeIf { nowMs - it in 0..claimWindowMs }
    }

    /** Forget an unclaimed press: the next tune was not caused by it. */
    @Synchronized
    fun clear() {
        pressAtMs = null
    }

    companion object {
        /** Comfortably longer than the zap wait (500 ms) plus the channel lookup before the engine starts,
         *  including a Stalker portal minting the stream URL; far shorter than a user's next decision. */
        const val CLAIM_WINDOW_MS = 5_000L

        /** The one instance both engines and the app share. */
        val shared = ZapTiming()

        /** Milliseconds from a claimed press at [pressAtMs] to [nowMs]; null without a press. */
        fun sincePress(pressAtMs: Long?, nowMs: Long): Long? = pressAtMs?.let { (nowMs - it).coerceAtLeast(0L) }

        /** The suffix a first-frame line carries when a zap press is being measured, else "". */
        fun pressSuffix(sincePressMs: Long?): String =
            sincePressMs?.let { " (zap key press to first frame ${it}ms)" }.orEmpty()
    }
}
