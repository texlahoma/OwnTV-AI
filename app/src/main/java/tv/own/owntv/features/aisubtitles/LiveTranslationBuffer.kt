package tv.own.owntv.features.aisubtitles

import kotlinx.coroutines.channels.Channel

/**
 * One caption window waiting for translation.
 *
 * [text] is the exact source line the caption is showing (see [CaptionSegmenter.currentSentenceText])
 * and [epoch] is the sentence it belongs to. The display keys on the epoch: a result carrying the
 * epoch of a sentence that has since scrolled off is dropped, so a slow round trip can never revive a
 * line the viewer has moved past.
 *
 * [final] marks the recognizer's endpoint for this sentence: the last words must be sent even if there
 * are only one or two of them, and the unstable-word hold-back is dropped (see
 * [LiveTranslationAccumulator.nextRequest]). The unit still carries the whole window rather than a
 * pre-cut fragment — the accumulator, which alone knows what has already been translated, decides
 * which part of it still needs a round trip.
 *
 * [capturedAtMs] is the capture time of the newest audio frame behind [text] (see
 * [RealtimePcmQueue.PcmFrame.capturedAtMs]). It rides the unit to publication so the end-to-end
 * latency — audio arrived → Chinese on screen — can be measured directly at the publish point
 * instead of reconstructed from queue/model/round-trip estimates.
 */
internal data class TranslationUnit(
    val text: String,
    val epoch: Int,
    val final: Boolean = false,
    val capturedAtMs: Long = 0L,
    /**
     * Wall-clock the recognizer returned the hypothesis this window was cut from, i.e. the end of the
     * ASR stage. `readyAtMs - capturedAtMs` is capture-to-text, the half of the end-to-end latency the
     * decoder and its input queue own (see [PipelineStats.latencyAsrMaxMs]).
     */
    val readyAtMs: Long = 0L,
    /**
     * Wall-clock the ASR worker handed this unit to the translator. `offeredAtMs - readyAtMs` is the
     * correction/segmentation/offer cost, and `sentAtMs - offeredAtMs` the buffer wait plus the
     * accumulator's decision — the two stages between "text is ready" and "request is on the wire".
     */
    val offeredAtMs: Long = 0L,
    /**
     * Wall-clock [tv.own.owntv.features.aisubtitles.SubtitleGlossary.correctNames] spent resolving the
     * fixture's entities for this window, ms. A sub-meter of the ASR stage, carried so the entity
     * resolver can be priced separately from raw decoding (see [PipelineStats.latencyEntityMaxMs]).
     */
    val entityMs: Long = 0L,
    /**
     * Presentation-clock time at which the audio behind this window is heard, ms (see
     * [CaptionTimeline]). With live audio held back it is in the future when the unit is offered,
     * which is exactly the budget the round trip may spend; without the hold-back it is already
     * past and the line publishes as soon as it exists.
     */
    val presentAtMs: Long = 0L,
    /**
     * What the bilingual line's source half shows: [text] minus the newest, still-revisable word.
     *
     * It exists because [text] is what the translator is asked about (the unstable tail has to be in
     * the request for the accumulator to decide about it), while the LINE on screen must not show a
     * word whose audio has not played yet. [presentAtMs] is the moment this shorter text's last word
     * is heard, and [text]'s last word is heard later — anchoring the pair on [text] is what made the
     * Chinese appear one word late. Empty means [text].
     */
    val displayText: String = "",
    /**
     * Word index of this unit's first word inside the recognizer's current hypothesis, -1 when the
     * caller did not supply one. It is what lets the manager time a finished sentence from its FIRST
     * word's onset — the moment a movie-style line belongs on screen — instead of from the word that
     * happened to close it.
     */
    val sourceStartIndex: Int = -1,
)

/**
 * Hand-off between the ASR worker (producer) and the translation worker (consumer).
 *
 * The producer offers the caption window every time it changed enough to be worth a round trip (see
 * [CaptionSegmenter.liveUnitForTranslation]); the consumer takes one and runs it to completion before
 * looking again. The channel is **conflated**, which is the whole point: while a round trip is in
 * flight the window keeps growing, and only the newest version of it matters. A superseded offer is
 * overwritten in place rather than queued, so a slow gateway costs freshness — the caption is always
 * a translation of the most recent window when a round trip ends — instead of leaving the worker
 * grinding through text that is already off screen.
 *
 * Both sides are non-blocking: `offer` never suspends the ASR feed loop, and the consumer's loop is
 * fed by the channel's own suspension. Re-created per pipeline session; the worker compares the
 * instance it captured against [AiSubtitleManager.liveBuffer] to drop its work once a restart has
 * replaced the session.
 */
internal class LiveTranslationBuffer(queueDepth: Int = 0) {

    /**
     * Pending units. [queueDepth] 0 keeps the live behaviour — CONFLATED, where a newer window
     * REPLACES the outstanding one. A positive depth makes it a short FIFO instead, which whole-
     * sentence captions need: there, one unit is a complete sentence and overwriting it deletes that
     * sentence's Chinese outright, with no later offer to repair it (live windows are self-healing
     * because they keep growing; a sentence is offered once).
     */
    val units: Channel<TranslationUnit> =
        if (queueDepth > 0) Channel(queueDepth) else Channel(Channel.CONFLATED)

    /** Offer a unit without suspending; in FIFO mode a full queue drops its OLDEST entry. */
    fun offer(unit: TranslationUnit) {
        if (units.trySend(unit).isSuccess) return
        // Full: the translator is a whole sentence behind. Keep the newest `queueDepth` sentences —
        // the oldest one's moment has already passed, so translating it now cannot put it on screen
        // in time, while dropping the NEWEST would lose the line that is about to start.
        units.tryReceive()
        units.trySend(unit)
    }
}