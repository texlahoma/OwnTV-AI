package tv.own.owntv.features.aisubtitles

/**
 * Tracks the sentence the recognizer is currently speaking and hands the translator the exact text
 * the caption is showing.
 *
 * ## The unit of translation is a sentence, not a growing window
 *
 * A streaming ASR engine re-emits its whole hypothesis on every frame — "the game is…", "the game is
 * now…", "the game is now tied at…". Two naive readings of that stream both produced an unusable
 * Chinese caption:
 *
 *  - **Translate the whole hypothesis every time.** Round trips are issued faster than they complete,
 *    so the worker is permanently saturated and the line on screen always describes text from one full
 *    round trip ago — seconds behind the English line above it.
 *  - **Cut speech into tiny clauses and translate each.** A clause is only complete after several
 *    words, so nothing is sent for the first couple of seconds of an utterance, and a clause translated
 *    with no context reads as machine output once the fragments are joined.
 *
 * [currentSentenceText] is therefore the *whole* sentence being spoken, and it is the same text handed
 * to the translator — so the two caption lines always describe the same words. The sentence is closed
 * either at a boundary the recognizer actually confirms (terminal punctuation followed by a word, or an
 * endpoint) or, for the unpunctuated output a streaming transducer emits, once it reaches
 * [MAX_SENTENCE_WORDS]. Closing it rather than letting the window slide is what keeps the translation
 * readable: a sliding window translates words 13-30 of a sentence with no subject, which is where the
 * "the Chinese reads like machine output" report came from.
 *
 * ## Where the English line and the Chinese line meet
 *
 * [sentenceEpoch] moves when the sentence ends. The translator keys its accumulated Chinese line on
 * that epoch, so a sentence's translation is complete and self-contained, and a round trip still in
 * flight for the previous sentence is dropped rather than published over the new one. The Chinese
 * already on screen is deliberately left alone at that moment — the new sentence's translation
 * replaces it as soon as its first fragment streams, so the caption does not blank out for a whole
 * round trip at every sentence boundary.
 *
 * Threading: used exclusively from the ASR worker ([AiSubtitleManager.consumePcm]), so it needs no
 * synchronisation. Re-created per pipeline session, so a cancelled session's state cannot leak in.
 */
class CaptionSegmenter(private val maxSentenceWords: Int = MAX_SENTENCE_WORDS) {

    // Newest hypothesis of the current utterance — at an endpoint, the recognizer's final best decode.
    private var latestPartial: String? = null
    // Word index where the sentence the caption is showing began.
    private var sentenceStart = 0
    // Window text last handed to the translator, so an unchanged window is never re-offered.
    private var offeredText: String? = null
    // Set by [flush] / [reset]: the next partial starts a new sentence whatever it contains.
    private var awaitingNewSentence = false
    // The sentence that just ended by punctuation or the word cap, captured BEFORE [sentenceEpoch]
    // moved — see [takeClosedFinal] for why the epoch it carries matters.
    private var closed: ClosedSentence? = null

    private data class ClosedSentence(val epoch: Int, val text: String, val startIndex: Int)

    /**
     * Word index where the sentence being shown began (see [windowText]).
     *
     * The caller turns it into an audio instant: the sentence's FIRST word's onset, which is where a
     * movie-style line has to appear (the whole sentence at once, not the words spoken so far).
     */
    val sentenceStartIndex: Int
        get() = sentenceStart

    /**
     * Which sentence the caption is showing. Moves at every boundary that ends it (see the class doc).
     * The translator keys its accumulated Chinese line on it: a result carrying an older epoch belongs
     * to speech the viewer has moved past and is dropped.
     */
    var sentenceEpoch: Int = 0
        private set

    /**
     * Record a fresh partial. Returns true when this partial started a new sentence, i.e. the English
     * line and its Chinese no longer describe the same speech.
     */
    fun onPartial(partial: String): Boolean {
        val words = tokenize(partial)
        if (words.isEmpty()) return false
        // A shorter hypothesis is NOT by itself a new sentence. A streaming transducer re-emits and
        // *revises* its hypothesis constantly — a token is replaced or dropped, taking the word and
        // whatever punctuation followed with it — and reading every one of those revisions as "the
        // engine restarted" bumped the epoch on a noisy broadcast stream until most translations were
        // discarded and the Chinese line sat still while the English kept moving: the "Chinese is
        // stuck / seconds behind" report. Real restarts are always announced: an endpoint flushes the
        // recognizer ([flush] is called for it) and a timeline jump calls [reset]. What is left is a
        // revision, which the translator absorbs by re-translating from the words that changed (see
        // [LiveTranslationAccumulator]).
        //
        // The one case that must still be caught here is a hypothesis that no longer even covers the
        // sentence on screen (the recognizer dropped everything it had told us about it).
        //
        // Strictly fewer words than the sentence start — `>=` was wrong, because it ALSO matched the
        // *expected* state right after the block cap closed one ([startNewSentence] sets the start to
        // `words.size`, i.e. the next word opens the new block). A streaming transducer re-emits its
        // hypothesis several times per second and the word count frequently does not change between
        // two consecutive emissions, so on any unpunctuated stretch past the cap every such revision
        // re-based the window to the WHOLE utterance and bumped [sentenceEpoch] again. Each bump
        // invalidated the round trip in flight (the Chinese line froze on the previous sentence) and
        // handed the translator a 20-word no-context fragment — the "Chinese is stuck / seconds
        // behind / reads like machine output" report. The cap branch below is the close itself; this
        // branch is only for a real loss of the sentence on screen.
        if (awaitingNewSentence || words.size < sentenceStart) {
            latestPartial = partial
            startNewSentence(0)
            return true
        }
        latestPartial = partial
        // Terminal punctuation is only a boundary once a word FOLLOWS it: until then the punctuation
        // may still be the last word of a sentence in flight, and cutting there would blank the line
        // for the fraction of a second before the recognizer extends it.
        val boundary = confirmedBoundary(words)
        if (boundary > sentenceStart) {
            // Stop the captured text exactly at the boundary: [confirmedBoundary] only confirms a
            // terminal word because a word FOLLOWS it, so slicing to the end of the hypothesis would
            // put that next sentence's first word(s) on this sentence's English line and translate
            // them here — then translate them again once the new sentence runs.
            captureClosed(boundary)
            startNewSentence(boundary)
            return true
        }
        // A streaming transducer emits no punctuation at all, so without this the sentence would run
        // until the recognizer's own endpoint rule (up to 20 s of speech) and the caption block — with
        // its accumulated Chinese — would grow past anything a viewer can read.
        //
        // The full block is closed and the next sentence starts at the newest word, rather than the
        // window sliding forward one word at a time. The slide looked harmless — it keeps the block
        // the same length — but it moved the sentence start on EVERY partial once the block was full,
        // so [sentenceEpoch] advanced with the speech and every round trip was invalidated before it
        // could land: the Chinese line sat frozen (or blank) for as long as the recognizer went
        // without punctuation or an endpoint, which is the "Chinese is stuck" report. Closing the
        // block moves the epoch once per blockful of speech, a rate a round trip can keep up with.
        if (words.size - sentenceStart > maxSentenceWords) {
            // The new block begins at the newest word, which is `words.size - 1` and NOT `words.size`.
            // The recognizer re-emits its whole hypothesis, so an index past the end points at the word
            // that has not arrived yet: the new block would start one word late, and the word the cut
            // was displayed on would drop out of the line on the very next partial — before it had ever
            // been handed to the translator, so it was lost from the Chinese line as well.
            //
            // The word the cut is displayed on is exactly the one the new block starts at, so the
            // captured block must stop one short of it (`words.size - 1`, matching [startNewSentence]);
            // ending at `words.size` would carry that word on BOTH blocks.
            captureClosed(words.size - 1)
            startNewSentence(words.size - 1)
            return true
        }
        return false
    }

    /**
     * The sentence that just ended by punctuation or the word cap, as a `final` unit carrying the epoch
     * that sentence had — or null when nothing closed since the last call. Consumes it, so the caller
     * hands it over exactly once.
     *
     * The epoch is the one *before* the switch, which is the whole point: the live round trips withheld
     * the sentence's last words (the most-revised ones, see
     * [LiveTranslationAccumulator.holdBackWords]) and only a `final` request releases them, so those
     * words can only be translated under the sentence's OWN epoch — once the new sentence's epoch is
     * live, a request for the old one is discarded as stale and the words are lost for good. The empty
     * result is returned after [flush], where the endpoint path already hands the window over itself.
     */
    internal fun takeClosedFinal(): TranslationUnit? {
        val captured = closed ?: return null
        closed = null
        return TranslationUnit(
            captured.text,
            captured.epoch,
            final = true,
            sourceStartIndex = captured.startIndex,
        )
    }

    /**
     * Remember the sentence that is ending, before [startNewSentence] moves the epoch on.
     *
     * [endExclusive] is the word the NEW sentence will begin at (the confirmed boundary, or the newest
     * word for the cap close), so the captured text stops exactly where [sentenceStart] is about to
     * move. Slicing to the end of the hypothesis instead put the next sentence's first word(s) on this
     * sentence's line — and, because that line is also what the translator is handed, translated them
     * here and then again once the new sentence was translated.
     */
    private fun captureClosed(endExclusive: Int) {
        val words = latestPartial?.let { tokenize(it) } ?: return
        if (words.isEmpty()) return
        val start = sentenceStart.coerceIn(0, words.size - 1)
        val end = endExclusive.coerceIn(start, words.size)
        if (end <= start) return
        val text = words.subList(start, end).joinToString(" ")
        if (text.isNotBlank()) closed = ClosedSentence(sentenceEpoch, text, start)
    }

    /**
     * The sentence window the caption shows — from the start of the current sentence to the newest
     * recognised word. This is both the English line and the text handed to the translator.
     */
    fun currentSentenceText(): String? = latestPartial?.let { windowText(tokenize(it)) }

    /**
     * The next window to hand the translator, or null when the caption already covers it.
     *
     * @param force hand the window over even when it is unchanged. The caller sets this at a recognizer
     *   endpoint so the utterance's last words — which arrive after the previous hand-over — still get
     *   their Chinese instead of being dropped.
     */
    fun liveUnitForTranslation(force: Boolean = false): String? {
        val text = currentSentenceText() ?: return null
        if (!force && text == offeredText) return null
        offeredText = text
        return text
    }

    /**
     * The recognizer signalled an endpoint: the next partial opens a new sentence. The line on screen
     * and the round trip still in flight for it are deliberately left alone — the epoch moves when the
     * new sentence's first words actually arrive, so the finished sentence keeps its Chinese through
     * the pause instead of blanking out the moment it stops being spoken.
     */
    fun flush() {
        latestPartial = null
        sentenceStart = 0
        offeredText = null
        awaitingNewSentence = true
        // Nothing to flush: the endpoint path hands the finished window over itself, still under this
        // sentence's epoch (see [onPartial]'s caller).
        closed = null
    }

    /**
     * Drop all buffered state and invalidate the line on screen: the audio timeline jumped (a queue
     * overflow or a renderer flush), so the sentence being assembled describes speech that is no longer
     * playing. The epoch moves immediately so any round trip in flight for it is discarded rather than
     * republished over the new audio.
     */
    fun reset() {
        flush()
        sentenceEpoch++
    }

    /** Begin a new sentence at word index [start], making the caption on screen stale. */
    private fun startNewSentence(start: Int) {
        sentenceStart = start
        offeredText = null
        awaitingNewSentence = false
        sentenceEpoch++
    }

    /** The window of [words] the caption shows: the current sentence. */
    private fun windowText(words: List<String>): String? {
        if (words.isEmpty()) return null
        val start = sentenceStart.coerceIn(0, words.size - 1)
        if (start >= words.size) return null
        return words.subList(start, words.size).joinToString(" ")
    }

    /**
     * Word index just past the last confirmed sentence end, or -1 when the sentence is still running.
     * Only a terminal-punctuation word with at least one word after it counts (see [onPartial]).
     */
    private fun confirmedBoundary(words: List<String>): Int {
        var boundary = -1
        for (i in sentenceStart until words.size - 1) {
            if (endsWithTerminal(words[i])) boundary = i + 1
        }
        return boundary
    }

    private fun endsWithTerminal(word: String): Boolean {
        val last = word.lastOrNull() ?: return false
        if (last == '!' || last == '?' || last == '！' || last == '？') return true
        if (last != '.' && last != '。') return false
        // A period closes a sentence only when it is not an abbreviation's. "Mr. Smith said…" and
        // "the U.S. side" would otherwise be cut after the title or the initialism — the word that
        // follows is exactly what [confirmedBoundary] needs to confirm a boundary, so one abbreviated
        // word was enough to split a sentence in two and hang the stub on the English line.
        return !isAbbreviation(word.dropLast(1))
    }

    /**
     * Whether a word's text before its trailing period marks an abbreviation rather than a sentence end
     * (see [endsWithTerminal]). Anything with a further dot in it is initials ("U.S", "e.g", "i.e"), and
     * a single letter is the same case as the model splits it ("U. S."); the rest is a short list of the
     * titles, months and ranks a broadcast's prose actually uses.
     */
    private fun isAbbreviation(stem: String): Boolean {
        if (stem.isEmpty()) return false
        if (stem.contains('.') || stem.length == 1) return true
        return stem.lowercase() in ABBREVIATIONS
    }

    companion object {
        /**
         * Sentence words a caption block may hold before it is closed and a new line begins. A
         * streaming transducer that emits no punctuation would otherwise grow one caption block until
         * the recognizer's own endpoint rule (up to 20 s of speech), past anything a viewer can read —
         * and its accumulated Chinese line would grow with it.
         */
        const val MAX_SENTENCE_WORDS = 20

        /**
         * Whitespace run, compiled ONCE for the process.
         *
         * [tokenize] runs several times per recognised partial — the segmenter's own [onPartial], the
         * live-window builder and the accumulator's diffing all re-split the SAME hypothesis — and a
         * fresh `Regex("\\s+")` per call re-compiled an unchanging pattern on every one of them:
         * ~35 `Pattern.compile` calls a second on the ASR worker, the one thread that must keep
         * draining the live PCM queue (see the `gc` pair on the diagnostics line).
         */
        private val WHITESPACE = Regex("\\s+")

        /** Split [text] into words; shared with the translation accumulator's diffing. */
        internal fun tokenize(text: String): List<String> =
            text.trim().split(WHITESPACE).filter { it.isNotEmpty() }

        /**
         * Abbreviations whose trailing period does not end a sentence (see [isAbbreviation]). Deliberately
         * short and unambiguous: a word that is also a sentence on its own ("No.") is not listed, because
         * treating it as an abbreviation would swallow a real sentence end.
         */
        private val ABBREVIATIONS = setOf(
            "mr", "mrs", "ms", "dr", "prof", "st", "jr", "sr", "vs", "etc", "co", "ltd", "inc",
            "dept", "gov", "sen", "rep", "capt", "gen", "lt", "sgt", "approx",
            "jan", "feb", "mar", "apr", "jun", "jul", "aug", "sep", "sept", "oct", "nov", "dec",
            "mon", "tue", "wed", "thu", "fri", "sat", "sun",
        )
    }
}
