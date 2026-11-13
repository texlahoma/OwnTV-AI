package tv.own.owntv.features.aisubtitles

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Pins the incremental translation contract: what each round trip is asked to translate, that the
 * Chinese line only ever grows within a sentence, and that a result for a sentence the viewer has moved
 * past can never be published. The line the viewer reads is what this class returns, so the behaviours
 * behind "the Chinese retyped itself", "the Chinese froze" and "the first fragment of a sentence reads
 * as machine output" are all decided here.
 */
class LiveTranslationAccumulatorTest {

    private val accumulator = LiveTranslationAccumulator()

    @Test
    fun `the newest word is held back while the sentence may still be revised`() {
        val request = accumulator.nextRequest(
            window = "the game is now tied",
            epoch = 1,
            final_ = false,
            nowMs = 1,
            targetLanguage = "zh",
        )!!
        // Only the newest word is withheld, and nothing is on screen yet, so there is no context.
        assertEquals("the game is now", request.text)
        assertEquals("", request.context)
    }

    @Test
    fun `a single new word is not worth a round trip`() {
        val first = accumulator.nextRequest("the game is now", 1, final_ = false, nowMs = 1, targetLanguage = "zh")!!
        assertEquals("the game is", first.text)
        accumulator.accept(first, "比赛现在", commit = true)

        // "now tied" leaves one word after the cursor — too little to translate well.
        assertNull(accumulator.nextRequest("the game is now tied", 1, final_ = false, nowMs = 1_000, targetLanguage = "zh"))

        // The second new word makes the pair worth a round trip, and the already-translated words are
        // handed over as context rather than translated again.
        val next = accumulator.nextRequest("the game is now tied at", 1, final_ = false, nowMs = 2_000, targetLanguage = "zh")!!
        assertEquals("now tied", next.text)
        assertEquals("the game is", next.context)
    }

    @Test
    fun `an endpoint sends the held-back last word`() {
        val request = accumulator.nextRequest("the game is tied", 1, final_ = true, nowMs = 1, targetLanguage = "zh")!!
        assertEquals("the game is tied", request.text)
    }

    @Test
    fun `a delayed session waits for a longer stable clause`() {
        // With the live hold-back the answer is not needed for seconds, so the first round trip can
        // carry a whole clause instead of a two-word fragment.
        assertNull(
            accumulator.nextRequest(
                "the game is now", 1, final_ = false, nowMs = 1, targetLanguage = "zh", minTailWords = 4,
            ),
        )
        val request = accumulator.nextRequest(
            "the game is now tied at home", 1, final_ = false, nowMs = 2_000, targetLanguage = "zh", minTailWords = 4,
        )!!
        // The newest word is still withheld as unstable; the clause before it is large enough now.
        assertEquals("the game is now tied at", request.text)
    }

    @Test
    fun `a delayed session still sends a short sentence at its endpoint`() {
        val request = accumulator.nextRequest(
            "the game is now", 1, final_ = true, nowMs = 1, targetLanguage = "zh", minTailWords = 4,
        )!!
        assertEquals("the game is now", request.text)
    }

    @Test
    fun `the committed line is the base of the next round trip so it can never rewind`() {
        val first = accumulator.nextRequest("hello there mate", 1, final_ = false, nowMs = 1, targetLanguage = "zh")!!
        assertEquals("hello there", first.text)
        accumulator.accept(first, "你好，朋友", commit = true)

        val next = accumulator.nextRequest("hello there mate and welcome", 1, final_ = false, nowMs = 5_000, targetLanguage = "zh")!!
        // The model writes the new fragment alone; it is appended to the line already on screen.
        assertEquals("你好，朋友和", accumulator.accept(next, "和", commit = false))
        assertEquals("你好，朋友和欢迎", accumulator.accept(next, "和欢迎", commit = false))
    }

    @Test
    fun `a whole-window answer replaces the line instead of being appended`() {
        // A backend that cannot be given the context (the key-free Google endpoint, which takes no
        // prompt) is handed the whole source window and answers with the whole line. Joining that onto
        // the line on screen would show the sentence twice, so it must replace it.
        val first = accumulator.nextRequest("hello there mate", 1, final_ = false, nowMs = 1, targetLanguage = "zh")!!
        assertEquals("hello there", first.text)
        accumulator.accept(first, "你好，朋友", commit = true)

        val next = accumulator.nextRequest("hello there mate and welcome", 1, final_ = false, nowMs = 5_000, targetLanguage = "zh")!!
        assertEquals("hello there", next.context)
        assertEquals("你好，朋友", next.baseTarget)
        // Same request, whole-line answer: the line becomes the answer itself, not base + answer.
        assertEquals(
            "你好，朋友，还有欢迎",
            accumulator.accept(next, "你好，朋友，还有欢迎", commit = true, wholeLine = true),
        )

        // The cursor advanced over the whole window the answer covered, so the next round trip still
        // asks for the new words only — with the full prefix as its context.
        val third = accumulator.nextRequest("hello there mate and welcome aboard now", 1, final_ = false, nowMs = 9_000, targetLanguage = "zh")!!
        assertEquals("hello there mate and", third.context)
        assertEquals("welcome aboard", third.text)
    }

    @Test
    fun `a whole-line answer that is the source window verbatim is dropped`() {
        val first = accumulator.nextRequest("hello there mate", 1, final_ = false, nowMs = 1, targetLanguage = "zh")!!
        accumulator.accept(first, "你好，朋友", commit = true)

        val next = accumulator.nextRequest("hello there mate and welcome", 1, final_ = false, nowMs = 5_000, targetLanguage = "zh")!!
        // The window it was handed — echoing it is not a translation, and publishing it would put the
        // English source on the Chinese line.
        assertNull(accumulator.accept(next, "hello there mate and", commit = true, wholeLine = true))
    }

    @Test
    fun `a result for a sentence the viewer has moved past is ignored`() {
        val first = accumulator.nextRequest("the game is now tied", 1, final_ = false, nowMs = 1, targetLanguage = "zh")!!
        accumulator.accept(first, "比赛现在打成平手", commit = true)

        // The next sentence starts its own line: nothing carried over, so the request has no context.
        val second = accumulator.nextRequest("welcome back to the show", 2, final_ = false, nowMs = 5_000, targetLanguage = "zh")!!
        assertEquals("", second.context)

        // A late answer for the previous sentence must not be published over the new line.
        assertNull(accumulator.accept(first, "迟到的翻译", commit = true))
        assertTrue(accumulator.accept(second, "欢迎回到节目", commit = false)!!.startsWith("欢迎"))
    }

    // --- the skip verdict -----------------------------------------------------------------------
    // `skip N` on the status line used to be one opaque number; the accumulator now says WHY it
    // refused, so a dropped name can be told from a harmless re-offer. Every test below pins one
    // branch, because the split is only readable if the causes never blur into each other.

    @Test
    fun `a built request reports no skip`() {
        assertNull(accumulator.lastSkip.takeIf { it != SkipReason.NONE })
        accumulator.nextRequest("the game is now tied", 1, final_ = false, nowMs = 1, targetLanguage = "zh")
        assertEquals(SkipReason.NONE, accumulator.lastSkip)
    }

    @Test
    fun `an empty window is named short`() {
        assertNull(accumulator.nextRequest("   ", 1, final_ = false, nowMs = 1, targetLanguage = "zh"))
        assertEquals(SkipReason.SHORT, accumulator.lastSkip)
    }

    @Test
    fun `a sentence the caption moved past is named stale`() {
        accumulator.nextRequest("the game is now tied", 2, final_ = false, nowMs = 1, targetLanguage = "zh")
        assertNull(accumulator.nextRequest("the game is now tied", 1, final_ = false, nowMs = 2_000, targetLanguage = "zh"))
        assertEquals(SkipReason.STALE, accumulator.lastSkip)
    }

    @Test
    fun `a window whose every word already has Chinese is named duplicate`() {
        // Terminal punctuation suspends the live hold-back, so the whole window is committed and a
        // repeat of it genuinely has nothing new — unlike a mid-sentence window, whose last word is
        // withheld and therefore reads as `unstable` rather than `duplicate`.
        val first = accumulator.nextRequest("the game is now tied.", 1, final_ = false, nowMs = 1, targetLanguage = "zh")!!
        accumulator.accept(first, "比赛现在打成平手", commit = true)

        assertNull(accumulator.nextRequest("the game is now tied.", 1, final_ = false, nowMs = 2_000, targetLanguage = "zh"))
        assertEquals(SkipReason.DUPLICATE, accumulator.lastSkip)
    }

    @Test
    fun `only the held-back word is named unstable`() {
        // The whole first window is committed, then the sentence grows by exactly the word the live
        // hold-back withholds: the only untranslated word is the one still being revised.
        val first = accumulator.nextRequest("the game is tied", 1, final_ = true, nowMs = 1, targetLanguage = "zh")!!
        accumulator.accept(first, "比赛打成平手", commit = true)

        assertNull(accumulator.nextRequest("the game is tied now", 1, final_ = false, nowMs = 2_000, targetLanguage = "zh"))
        assertEquals(SkipReason.UNSTABLE, accumulator.lastSkip)
    }

    @Test
    fun `a lone new word while the sentence runs is named low confidence`() {
        val first = accumulator.nextRequest("the game is now", 1, final_ = false, nowMs = 1, targetLanguage = "zh")!!
        accumulator.accept(first, "比赛现在", commit = true)

        assertNull(accumulator.nextRequest("the game is now tied", 1, final_ = false, nowMs = 2_000, targetLanguage = "zh"))
        assertEquals(SkipReason.LOW_CONFIDENCE, accumulator.lastSkip)
    }

    @Test
    fun `a request inside the retry floor is named low confidence`() {
        accumulator.nextRequest("the game is now", 1, final_ = false, nowMs = 1_000, targetLanguage = "zh")
        assertNull(accumulator.nextRequest("the game is now tied at", 1, final_ = false, nowMs = 1_100, targetLanguage = "zh"))
        assertEquals(SkipReason.LOW_CONFIDENCE, accumulator.lastSkip)
    }
}
