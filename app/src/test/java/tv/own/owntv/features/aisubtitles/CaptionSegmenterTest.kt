package tv.own.owntv.features.aisubtitles

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Pins the contract between the recognizer's ever-growing hypothesis and the two caption lines: which
 * window the English line shows, when that window is worth a translation round trip, and when the
 * Chinese line has to be dropped because the sentence it describes is gone.
 */
class CaptionSegmenterTest {

    private fun words(n: Int, from: Int = 1): String =
        (from until from + n).joinToString(" ") { "w$it" }

    // --- The window the caption shows --------------------------------------------------------------

    @Test
    fun `nothing is shown before the first partial`() {
        val s = CaptionSegmenter()
        assertNull(s.currentSentenceText())
        assertNull(s.liveUnitForTranslation())
    }

    @Test
    fun `the window is the sentence as the recognizer grows it`() {
        val s = CaptionSegmenter()
        s.onPartial("the game is")
        assertEquals("the game is", s.currentSentenceText())
        s.onPartial("the game is now tied")
        assertEquals("the game is now tied", s.currentSentenceText())
    }

    @Test
    fun `blank partials change nothing`() {
        val s = CaptionSegmenter()
        assertFalse(s.onPartial(""))
        assertFalse(s.onPartial("   "))
        assertNull(s.currentSentenceText())
    }

    // --- Closing a sentence at the block cap -------------------------------------------------------

    @Test
    fun `the block cap closes the sentence and the next one starts after the words shown`() {
        // An unpunctuated sentence runs until the recognizer's own endpoint rule, which can be 20 s of
        // speech. The cap closes the block instead, so the line — and the round trip that translates
        // it — stays bounded.
        val s = CaptionSegmenter()
        val before = s.sentenceEpoch
        assertTrue(s.onPartial(words(21)))
        assertEquals(before + 1, s.sentenceEpoch)
        // The next block begins at the newest word, and grows from there — the word the cut was shown
        // on stays in the line instead of dropping out on the next partial.
        assertEquals("w21", s.currentSentenceText())
        assertFalse(s.onPartial(words(22)))
        assertEquals("w21 w22", s.currentSentenceText())
        assertFalse(s.onPartial(words(23)))
        assertEquals("w21 w22 w23", s.currentSentenceText())
    }

    @Test
    fun `a revision that adds no word after the cap does not close another sentence`() {
        // Regression: the "hypothesis no longer covers the sentence" test also matched the state the
        // cap close leaves behind (start == word count, the next word opens the new block). A streaming
        // transducer re-emits its hypothesis several times per second and the word count frequently does
        // not change between two emissions, so every such revision re-based the window to the WHOLE
        // utterance and bumped the epoch again: the round trip in flight was invalidated (the Chinese
        // line froze) and the translator was handed the whole sentence as a context-less fragment.
        val s = CaptionSegmenter()
        s.onPartial(words(21))
        val afterCap = s.sentenceEpoch
        assertFalse(s.onPartial(words(21)))
        assertFalse(s.onPartial(words(21)))
        assertEquals(afterCap, s.sentenceEpoch)
        assertEquals("w21", s.currentSentenceText())
    }

    @Test
    fun `a hypothesis that lost the sentence on screen starts a new one`() {
        val s = CaptionSegmenter()
        s.onPartial(words(21))
        val afterCap = s.sentenceEpoch
        // The recognizer restarted and holds fewer words than where the sentence on screen began: those
        // words are gone, so everything anchored to them is dropped.
        assertTrue(s.onPartial(words(10)))
        assertEquals(afterCap + 1, s.sentenceEpoch)
        assertEquals(words(10), s.currentSentenceText())
    }

    // --- Terminal punctuation ----------------------------------------------------------------------

    @Test
    fun `punctuation only ends the sentence once a word follows it`() {
        val s = CaptionSegmenter()
        val before = s.sentenceEpoch
        assertFalse(s.onPartial("hello there."))
        // The full stop is the newest word: the recognizer may still be extending that sentence, so
        // cutting here would blank the line for a frame.
        assertEquals(before, s.sentenceEpoch)
        assertEquals("hello there.", s.currentSentenceText())

        assertTrue(s.onPartial("hello there. mate"))
        assertEquals(before + 1, s.sentenceEpoch)
        // The new sentence starts after the punctuation: the English line no longer repeats it.
        assertEquals("mate", s.currentSentenceText())
    }

    @Test
    fun `flush leaves the epoch alone so the finished sentence keeps its line`() {
        val s = CaptionSegmenter()
        s.onPartial("okay thanks")
        val before = s.sentenceEpoch
        s.flush()
        // The endpoint must not invalidate the line on screen: the translation still in flight for it
        // is the right thing to show through the pause.
        assertEquals(before, s.sentenceEpoch)
        // The next partial, though, opens a new sentence.
        assertTrue(s.onPartial("welcome back"))
        assertEquals(before + 1, s.sentenceEpoch)
    }

    @Test
    fun `reset invalidates the line immediately because the audio timeline jumped`() {
        val s = CaptionSegmenter()
        s.onPartial("the game is now tied")
        val before = s.sentenceEpoch
        s.reset()
        assertEquals(before + 1, s.sentenceEpoch)
        assertNull(s.currentSentenceText())
    }

    // --- When the window is worth a round trip ------------------------------------------------------

    @Test
    fun `a changed window is offered and an unchanged one is not`() {
        val s = CaptionSegmenter()
        s.onPartial("hello")
        assertEquals("hello", s.liveUnitForTranslation())
        assertNull(s.liveUnitForTranslation())
        s.onPartial("hello there")
        assertEquals("hello there", s.liveUnitForTranslation())
    }

    @Test
    fun `a re-decode that rewrites the window without growing it is re-offered`() {
        val s = CaptionSegmenter()
        s.onPartial("the referee signalled a foul")
        assertEquals("the referee signalled a foul", s.liveUnitForTranslation())
        // Same length, different words: the text the Chinese was built from is gone, so waiting for two
        // more words would leave it describing words the viewer can see are no longer there.
        s.onPartial("the referee signaled a foul")
        assertEquals("the referee signaled a foul", s.liveUnitForTranslation())
    }

    @Test
    fun `an endpoint forces out a window that was already offered`() {
        val s = CaptionSegmenter()
        s.onPartial(words(5))
        assertEquals(words(5), s.liveUnitForTranslation())
        assertNull(s.liveUnitForTranslation())
        assertEquals(words(5), s.liveUnitForTranslation(force = true))
    }

    @Test
    fun `the window after a punctuation boundary starts at the new sentence`() {
        val s = CaptionSegmenter()
        s.onPartial("hello there. mate and welcome")
        // "hello there." ended the previous sentence, so the unit must not re-send it.
        assertEquals("mate and welcome", s.liveUnitForTranslation())
    }
}
