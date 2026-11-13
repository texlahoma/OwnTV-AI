package tv.own.owntv.features.aisubtitles

/**
 * The bridge between "a caption was produced" and "the audio it describes is being heard".
 *
 * A live recognition pipeline always finishes a line *after* the words have been spoken, so
 * publishing on arrival guarantees the caption trails the sound by the decode plus the round trip.
 * The only way to be in sync is to hold the audio back as well (see `LiveAudioDelay`): the tap then
 * hears the words early, and every produced caption is stamped with the moment its audio will reach
 * the speaker. This class keeps those cues until their moment and publishes the newest one that is
 * due — so a line that is ready early waits, a line that is ready late appears at once, and neither
 * can jump the queue and overwrite a caption whose audio has not played yet.
 *
 * Two independent timelines are kept: the recognised source line (which moves with every partial)
 * and the translated pair (which moves with every round trip). They are published separately but
 * atomically per [advance] call, so a viewer never sees two lines from different cues.
 *
 * The clock is whatever the caller passes to [advance]. It must be the *presentation* clock — one
 * that stops while the player is paused — not wall-clock time; that is what makes a cue wait through
 * a pause instead of expiring unseen. Threading: offers arrive on the ASR and translation workers,
 * [advance] runs on the UI ticker; all state is guarded by one lock, and the work under it is a few
 * deque operations.
 */
internal class CaptionTimeline(private val maxPending: Int = 128, private val maxCueAgeMs: Long = 30_000L) {

    /** What [advance] produced: the lines that should be on screen now. */
    data class Update(
        val source: String?,
        val pair: SubtitlePair?,
        /**
         * How late the newest published cue was when it finally went up, ms: 0 when the cue was
         * waiting for its audio (the normal case), positive when its audio had ALREADY played and the
         * line could only be shown after the fact. That number is the real subtitle-sync error — a
         * caption whose cue arrives late cannot be fixed by any anchor, only by more budget — so it is
         * surfaced on the diagnostics line instead of being silently absorbed.
         */
        val lateMs: Long = 0L,
    )

    private data class Cue<T>(val atMs: Long, val value: T)

    private val lock = Any()
    private val sources = ArrayDeque<Cue<String>>()
    private val pairs = ArrayDeque<Cue<SubtitlePair>>()
    private var publishedSource: String? = null
    private var publishedPair: SubtitlePair? = null
    private var sourceAtMs = Long.MIN_VALUE
    private var pairAtMs = Long.MIN_VALUE

    /** Queue [text] to be shown when the audio it was recognised from plays at [atMs]. */
    fun offerSource(atMs: Long, text: String) {
        if (text.isBlank()) return
        synchronized(lock) { add(sources, Cue(atMs, text)) }
    }

    /** Queue [pair] to be shown when the audio its window covers plays at [atMs]. */
    fun offerPair(atMs: Long, pair: SubtitlePair) {
        synchronized(lock) { add(pairs, Cue(atMs, pair)) }
    }

    /**
     * Publish every cue whose audio has reached [nowMs], keeping only the newest of each line, and
     * return the resulting screen state — or null when nothing changed. Called on a short ticker,
     * not per offer, so the overlay recomposes at a bounded rate.
     */
    fun advance(nowMs: Long): Update? {
        synchronized(lock) {
            // Cues far older than the clock are from before a seek or a long stall; showing them now
            // would put stale speech on screen, so they are dropped rather than published late.
            dropStale(sources, nowMs)
            dropStale(pairs, nowMs)
            var changed = false
            var lateMs = 0L
            // The NEWEST due cue wins wherever it sits in the deque. Offers are in production order,
            // but a recognizer revision can hand a line a time EARLIER than the cue before it, and
            // popping from the head only made that revision — and every cue behind it — wait for the
            // head's time, up to a full hold-back. On screen that is the line freezing and then
            // jumping, which is indistinguishable from "the subtitles are late".
            // Ties go to the cue offered LAST, not the first. One line can be re-offered many times at
            // the SAME instant — a sentence's streamed translation arrives as successive fragments, all
            // anchored on that sentence's first word — and picking the first of them published the
            // shortest fragment and dropped the rest, which showed up as "the Chinese line has only the
            // first word of every sentence". Production order is growth order, so the last tie is the
            // most complete text.
            val dueSource = newestDue(sources, nowMs) { it.atMs }
            if (dueSource != null) {
                sources.removeAll { it.atMs <= nowMs }
                if (dueSource.atMs >= sourceAtMs) {
                    publishedSource = dueSource.value
                    sourceAtMs = dueSource.atMs
                    changed = true
                    lateMs = nowMs - dueSource.atMs
                }
            }
            val duePair = newestDue(pairs, nowMs) { it.atMs }
            if (duePair != null) {
                pairs.removeAll { it.atMs <= nowMs }
                if (duePair.atMs >= pairAtMs) {
                    publishedPair = duePair.value
                    pairAtMs = duePair.atMs
                    changed = true
                    lateMs = maxOf(lateMs, nowMs - duePair.atMs)
                }
            }
            return if (changed) Update(publishedSource, publishedPair, lateMs) else null
        }
    }


    /**
     * The cue to publish among those already due: the one with the largest time, and among equal
     * times the LAST offered (see the tie-break note in [advance]). Iterates instead of allocating a
     * filtered list on every tick.
     */
    private fun <T> newestDue(queue: ArrayDeque<Cue<T>>, nowMs: Long, at: (Cue<T>) -> Long): Cue<T>? {
        var best: Cue<T>? = null
        for (cue in queue) {
            if (at(cue) > nowMs) continue
            if (best == null || at(cue) >= at(best)) best = cue
        }
        return best
    }

    /**
     * Forget every cue that has not played yet, keeping what is already on screen. Used when the
     * audio timeline jumps (seek, live reposition, channel restart): those pending lines describe
     * speech that will never be heard. The visible line is deliberately left alone so the caption
     * does not blink out mid-sentence.
     */
    fun clearPending() {
        synchronized(lock) {
            sources.clear()
            pairs.clear()
            // The anti-regression anchors reset with the queue: after a discontinuity the new
            // timeline's stamps start from a freshly measured hold-back (see LiveAudioDelay), which
            // can sit BELOW the last published cue's time — without this the guard in [advance]
            // would silently swallow the new session's first cues until its clock climbed back past
            // the old anchor. The lines already on screen are untouched either way.
            sourceAtMs = Long.MIN_VALUE
            pairAtMs = Long.MIN_VALUE
        }
    }

    /** Forget cues AND the lines on screen — a new pipeline session starts with nothing to say. */
    fun reset() {
        synchronized(lock) {
            sources.clear()
            pairs.clear()
            publishedSource = null
            publishedPair = null
            sourceAtMs = Long.MIN_VALUE
            pairAtMs = Long.MIN_VALUE
        }
    }

    private fun <T> add(queue: ArrayDeque<Cue<T>>, cue: Cue<T>) {
        queue.addLast(cue)
        // Bounded so a device that decodes minutes behind cannot pin the caption text in memory;
        // the oldest cues are the ones a late pipeline would never show anyway.
        while (queue.size > maxPending) queue.removeFirst()
    }

    private fun <T> dropStale(queue: ArrayDeque<Cue<T>>, nowMs: Long) {
        while (queue.isNotEmpty() && queue.first().atMs < nowMs - maxCueAgeMs) queue.removeFirst()
    }
}
