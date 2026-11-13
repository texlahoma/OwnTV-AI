package tv.own.owntv.features.aisubtitles

/**
 * Why [LiveTranslationAccumulator.nextRequest] produced no request — read by the manager from
 * [LiveTranslationAccumulator.lastSkip] to break the on-screen `skip N` down into causes.
 *
 * A single skip count cannot say what was dropped: a stale sentence, a window whose every word is
 * already translated, and a sentence whose only new word is still being revised all read the same,
 * and only the last is a fault worth chasing. These five are the distinct outcomes the accumulator
 * can reach, and they are mutually exclusive by first match.
 */
internal enum class SkipReason {
    /** A request was built; nothing was skipped. */
    NONE,

    /** The window held no tokenizable word at all (an empty or punctuation-only window). */
    SHORT,

    /** Every stable word of the window is already translated — there is nothing new to ask for. */
    DUPLICATE,

    /**
     * The only new word is the newest, still-revisable one the live hold-back withholds
     * ([UNSTABLE_TAIL_WORDS]); asking now would spend a round trip on a word likely to change.
     */
    UNSTABLE,

    /**
     * Too little new text to trust a round trip: one new word while the sentence runs, or a window
     * that has not moved enough since the last request ([RETRY_MIN_MS]) to be worth another.
     */
    LOW_CONFIDENCE,

    /** The request was for a sentence the caption has already moved past ([epoch]). */
    STALE,
}

/**
 * Builds the Chinese caption for the sentence currently being spoken, **incrementally**.
 *
 * ## Why not translate the sentence again every time it grows
 *
 * The obvious way to caption a growing sentence is to re-translate it whenever it changes. That is
 * what put the Chinese seconds behind the English: a full round trip for a whole clause takes long
 * enough that the *next* version of the sentence is already being spoken when the previous
 * translation lands, so the line on screen always describes speech from one whole round trip ago —
 * and, because the answer is re-rendered from the first word, the visible line rewound and retyped
 * itself on every round trip.
 *
 * This class instead treats the sentence as a line that only ever **grows**. Each round trip is asked
 * to translate *only the words that have no Chinese yet*, given the already-translated words as
 * context, and its answer is appended. The consequences are exactly the three things a live caption
 * needs:
 *
 *  - **Latency is set by the new words, not the sentence.** Translating three or four new words is a
 *    fraction of the generation a whole clause costs, so the Chinese lands within a fraction of a
 *    round trip of the English line instead of seconds behind it.
 *  - **The line never rewinds or gets stuck.** Appending is monotonic, so there is no churn to guard
 *    against and no full-sentence re-rendering to reject: the Chinese only ever continues.
 *  - **Quality holds up, because the context is supplied.** A three-word fragment translated alone
 *    reads as machine output; the same fragment translated as the continuation of the sentence so far
 *    keeps its subject, register and terminology. Both the source words already covered
 *    ([TranslationRequest.context]) and the Chinese line they produced ([TranslationRequest.baseTarget])
 *    are handed to the backend, so a name or a term is carried across the fragments of a sentence
 *    instead of being re-chosen each time.
 *
 * ## Revisions, boundaries and failures
 *
 * A streaming recognizer revises its hypothesis as it decodes, so the sentence text can change
 * *behind* the point already translated. The already-translated words are therefore re-diffed against
 * the new text on every request (see [sameWord], which ignores the punctuation and capitalisation a
 * revision usually only adds). When the revision reaches *into* the translated part, the line is
 * rewound to the longest answer that still covers the unchanged prefix ([history]) and re-translated
 * from there — appending a re-translation of already-covered words instead would leave both versions
 * of the same speech in the caption, which is what a mid-sentence correction used to look like. The
 * newest word of a live hypothesis is the one most likely to be replaced, so it is held back until
 * the sentence ends ([UNSTABLE_TAIL_WORDS]).
 *
 * The sentence [epoch] is the only reset signal: a new epoch means a new line, so the accumulated
 * Chinese and the translated-word cursor start over. Until the new line's first translation streams,
 * the caption keeps showing the previous one (the manager simply does not publish a blank line).
 *
 * A round trip that fails leaves the cursor untouched, so the same words are simply requested again
 * on the next offer — a dropped request costs a delay, never a hole in the line.
 *
 * Threading: owned by the translation worker alone; the ASR worker never touches it.
 */
internal class LiveTranslationAccumulator {

    /** Sentence the accumulated line belongs to; -1 until the first request. */
    private var epoch = -1

    /** Words of the current sentence whose Chinese is already covered by [history]. */
    private var coveredWords: List<String> = emptyList()

    /** What the caption should currently show — the committed line, or committed + a streamed partial. */
    private var shown: String = ""

    /** Wall-clock of the last request issued, for [RETRY_MIN_MS]. */
    private var lastAttemptMs = 0L

    /**
     * Why the most recent [nextRequest] produced no request, or [SkipReason.NONE] when it built one.
     * The manager reads it immediately after a null return to attribute the on-screen `skip N` to a
     * cause (see [SkipReason]); it is set on every path, so it always describes the last call.
     */
    var lastSkip: SkipReason = SkipReason.NONE
        private set

    /**
     * Every accepted answer of the current sentence — `(words covered, the whole line up to them)` —
     * in ascending word order. It is what makes a revision *behind* the translated point survivable
     * without either duplicating text or throwing the whole line away: the rewind target for a given
     * unchanged prefix is the longest commit that ends inside it. Starts with the empty line, so a
     * sentence that has nothing in common with the line on screen simply restarts from nothing.
     */
    private val history = mutableListOf(Commit(0, ""))

    private data class Commit(val words: Int, val text: String)

    /** The Chinese line to display for the sentence currently being translated. */
    val display: String get() = shown

    /**
     * One round trip's work: translate [text] (the words with no Chinese yet) continuing from
     * [context] (the source words of the same sentence that are already translated, which must not be
     * translated again) and from [baseTarget] (the target line those words already produced).
     *
     * Both halves go to the backend: the source words give the model the unfinished sentence's
     * subject, and the produced line lets it continue that line's names, terminology and register
     * instead of wording the fragment afresh.
     *
     * [covered] is the word cursor a successful answer commits, captured at issue time so a concurrent
     * revision cannot move it under the round trip.
     */
    data class TranslationRequest(
        val epoch: Int,
        val context: String,
        val text: String,
        val covered: List<String>,
        val baseTarget: String,
        val window: String,
        val spaced: Boolean,
    )

    /**
     * The next round trip worth running for [window], or null when the caption already covers it (or
     * the sentence has nothing new worth a request yet).
     *
     * @param final_ the recognizer reached an endpoint: the last words of the sentence must go out even
     *   if there are only one or two of them, and the unstable-word hold-back is dropped.
     */
    fun nextRequest(
        window: String?,
        epoch: Int,
        final_: Boolean,
        nowMs: Long,
        targetLanguage: String,
        /**
         * New words the first request of a window must contain, or 0 to use [TAIL_MIN_WORDS]. The
         * live hold-back passes a larger number: with seconds of budget, translating a longer,
         * stabler clause reads better than a two-word fragment and saves the re-translations that
         * follow. An endpoint always sends its tail regardless (a sentence's last words must not be
         * withheld waiting for a count it will never reach).
         */
        minTailWords: Int = 0,
    ): TranslationRequest? {
        if (window.isNullOrBlank()) return skip(SkipReason.SHORT)
        // A request for a sentence the caption has already moved past is worthless; a request for a
        // NEWER sentence starts a new line.
        if (epoch < this.epoch) return skip(SkipReason.STALE)
        if (epoch != this.epoch) startLine(epoch)
        val words = CaptionSegmenter.tokenize(window)
        if (words.isEmpty()) return skip(SkipReason.SHORT)
        // Hold the newest word back while the sentence is still being spoken: a streaming transducer
        // revises its last token far more often than its earlier ones, and re-sending it costs a whole
        // round trip for a word that may already be gone. A word the recognizer has *closed* with
        // terminal punctuation is excluded from that hold-back — it is as stable as the words before
        // it, and withholding it dropped exactly the last word of every punctuated sentence (see
        // [holdBackWords]).
        val stable = if (final_) words else words.subList(0, maxOf(0, words.size - holdBackWords(words)))
        val common = commonPrefixWords(coveredWords, stable)
        // The longest answer that still ends inside the unchanged prefix: everything it covers is
        // still correct, so the new fragment continues from it instead of re-translating it.
        val base = history.lastOrNull { it.words <= common } ?: history.first()
        val start = minOf(base.words, stable.size)
        val tail = stable.subList(start, stable.size)
        val minTail = if (final_) 1 else if (minTailWords > 0) minTailWords else TAIL_MIN_WORDS
        if (tail.size < minTail) {
            // Three distinct causes hide behind "too little to ask for", and `fresh` tells them
            // apart: it counts the words with no Chinese yet INCLUDING the held-back tail, so a
            // genuinely finished line (nothing new) is not conflated with a line whose only new word
            // is the one the hold-back is deliberately withholding.
            val fresh = words.size - commonPrefixWords(coveredWords, words)
            return skip(
                when {
                    fresh == 0 -> SkipReason.DUPLICATE
                    tail.isEmpty() -> SkipReason.UNSTABLE
                    else -> SkipReason.LOW_CONFIDENCE
                },
            )
        }
        // The retry floor keeps a burst of revised partials from spinning the worker, but it must not
        // delay the FIRST fragment of a line: 0 means no request has been issued for this line yet.
        if (!final_ && lastAttemptMs != 0L && nowMs - lastAttemptMs < RETRY_MIN_MS) {
            return skip(SkipReason.LOW_CONFIDENCE)
        }
        lastAttemptMs = nowMs
        lastSkip = SkipReason.NONE
        return TranslationRequest(
            epoch = epoch,
            context = stable.subList(0, start).joinToString(" "),
            text = tail.joinToString(" "),
            covered = stable,
            baseTarget = base.text,
            window = window,
            spaced = !isCjk(targetLanguage),
        )
    }

    /** Record [reason] as the outcome and return null, so every refusal names its cause. */
    private fun skip(reason: SkipReason): TranslationRequest? {
        lastSkip = reason
        return null
    }

    /**
     * Apply [translation] for [request] and return the line to show, or null when it must be ignored
     * (the sentence moved on, or the backend echoed the source).
     *
     * @param commit true for the finished line: it advances the translated-word cursor so the next
     *   request starts after these words. A streamed partial is displayed without committing, so a
     *   round trip that dies mid-line leaves the cursor where it was and its words are requested again.
     * @param wholeLine true when [translation] is the translation of the WHOLE source window rather
     *   than of the fragment that was asked for. Only a backend that cannot be given the context
     *   answers that way (see [tv.own.owntv.features.aisubtitles.translate.TranslationResult]); its
     *   answer already contains everything [TranslationRequest.baseTarget] holds, so it replaces the
     *   line instead of being appended to it. The word cursor it commits is the same one — the answer
     *   covers exactly [TranslationRequest.covered].
     */
    fun accept(
        request: TranslationRequest,
        translation: String,
        commit: Boolean,
        wholeLine: Boolean = false,
    ): String? {
        if (request.epoch != epoch) return null
        val line = normalize(translation)
        if (line.isEmpty()) return null
        // The source the answer must not merely echo back: the fragment for an append, the whole
        // window (context + fragment) for a replacement, since that is what it was handed. Both the
        // form as spoken and the disambiguated one are checked — the translator is given the latter
        // (see [SubtitleGlossary.disambiguate]), so an echoed source comes back in that form. The
        // anchored window is the one the translator actually read the gate from: a GATED expansion
        // can be decided by an anchor in CONTEXT alone, so reconstructing the echo from the fragment
        // by itself would miss a channel that echoed the expanded text.
        val source = if (!wholeLine) request.text
        else SubtitleGlossary.window(request.context, request.text)
        val anchored = SubtitleGlossary.window(request.context, request.text)
        if (commit && (line == source || line == SubtitleGlossary.disambiguate(source, anchored))) return null
        val joined = if (wholeLine) line else join(request.baseTarget, line, request.spaced)
        shown = joined
        if (commit) {
            coveredWords = request.covered
            // Answers that described more words than this one belong to a longer hypothesis this
            // answer has just superseded — a later rewind must not find them and re-append them.
            while (history.size > 1 && history.last().words >= request.covered.size) {
                history.removeAt(history.size - 1)
            }
            history.add(Commit(request.covered.size, joined))
        }
        return joined
    }

    /**
     * Drop a streamed partial that will never be committed (the round trip failed), so the caption
     * falls back to the last complete line instead of keeping half a sentence on screen forever.
     */
    fun abandon(request: TranslationRequest) {
        if (request.epoch != epoch) return
        shown = history.last().text
    }

    /** Begin the line for a new sentence: nothing translated, nothing on screen, no history. */
    private fun startLine(epoch: Int) {
        this.epoch = epoch
        coveredWords = emptyList()
        shown = ""
        lastAttemptMs = 0L
        history.clear()
        history.add(Commit(0, ""))
    }

    private fun commonPrefixWords(a: List<String>, b: List<String>): Int {
        var i = 0
        while (i < a.size && i < b.size && sameWord(a[i], b[i])) i++
        return i
    }

    /**
     * Words withheld from the end of a live hypothesis because the recognizer may still revise them.
     *
     * Normally the newest word ([UNSTABLE_TAIL_WORDS]). A word the recognizer has already closed with
     * terminal punctuation is not one of those: the punctuation is the recognizer telling us the
     * sentence ended there, and holding that word back meant every sentence that ends with `.!?` lost
     * its final word to the hold-back — the viewer saw a Chinese line missing the last one or two
     * words, which in commentary is regularly the whole point of the sentence ("…and that is the
     * equaliser." did not translate "equaliser"). With the punctuation present the word is as settled
     * as the ones before it, so nothing is withheld.
     */
    private fun holdBackWords(words: List<String>): Int =
        if (words.lastOrNull()?.let { endsWithTerminal(it) } == true) 0 else UNSTABLE_TAIL_WORDS

    /** Whether [word] ends with a character a sentence can end on (see [holdBackWords]). */
    private fun endsWithTerminal(word: String): Boolean = when (word.lastOrNull()) {
        '.', '!', '?', '。', '！', '？' -> true
        else -> false
    }

    /**
     * Whether two hypothesis words are the same word. A streaming transducer revises a token by
     * *adding* to it far more often than by replacing it — a period appears, a capital is inserted,
     * a comma lands — and none of those change the translation of the word. Comparing strictly meant
     * every one of them read as "the text behind the cursor changed", which rewound the line to the
     * last commit on punctuation the recognizer had merely finished emitting.
     */
    private fun sameWord(a: String, b: String): Boolean {
        if (a == b) return true
        val left = a.trimEnd(*EDGE_PUNCTUATION)
        val right = b.trimEnd(*EDGE_PUNCTUATION)
        return left.isNotEmpty() && left.equals(right, ignoreCase = true)
    }

    /** One line, no stray newlines or runs of spaces, as a subtitle line must be. */
    private fun normalize(text: String): String =
        text.trim().split(WHITESPACE).filter { it.isNotEmpty() }.joinToString(" ")

    /** Append [line] to [base], separating with a space only for languages that use one. */
    private fun join(base: String, line: String, spaced: Boolean): String = when {
        base.isEmpty() -> line
        spaced -> "$base $line"
        else -> base + line
    }

    private companion object {
        /** Whitespace run for [normalize]; compiled once rather than per call (see the ASR-worker note). */
        private val WHITESPACE = Regex("\\s+")

        /**
         * New words needed before a round trip is worth it. One word would put a request in flight for
         * a fragment too small to translate well and to gain much latency from; the words held in the
         * meantime are not lost, they are simply sent with the next batch.
         */
        const val TAIL_MIN_WORDS = 2

        /** Newest sentence words held back from translation while the recognizer may still revise them. */
        const val UNSTABLE_TAIL_WORDS = 1

        /** Minimum spacing between two requests, so a burst of partials cannot spin the worker. */
        const val RETRY_MIN_MS = 250L

        /**
         * Punctuation a revision adds to a word without changing it: the token is the same word with
         * a full stop, a comma or a closing quote now attached (see [sameWord]).
         */
        private val EDGE_PUNCTUATION =
            charArrayOf('.', ',', '!', '?', ';', ':', '。', '，', '！', '？', '、', '；', '：', '…', '"', '”', ')')

        /** Languages written without spaces between words. */
        fun isCjk(language: String): Boolean =
            language.startsWith("zh") || language.startsWith("ja") || language.startsWith("ko")
    }
}
