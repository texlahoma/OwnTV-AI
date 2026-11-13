package tv.own.owntv.features.aisubtitles

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * Pins the one guarantee that makes a live caption watchable: a line appears when the audio it was
 * recognised from is heard — not before, and not so late that it describes a moment already gone.
 * The clock is injected, so every case here is a plain statement about cue times.
 */
class CaptionTimelineTest {

    @Test
    fun `a cue waits until its audio plays`() {
        val timeline = CaptionTimeline()
        timeline.offerSource(8_000, "Arsenal win a corner")

        assertNull(timeline.advance(7_999))
        assertEquals("Arsenal win a corner", timeline.advance(8_000)?.source)
    }

    @Test
    fun `the newest due cue wins and older ones never come back`() {
        val timeline = CaptionTimeline()
        timeline.offerSource(1_000, "first words")
        timeline.offerSource(2_000, "first words and more")
        timeline.offerSource(3_000, "first words and more still")

        assertEquals("first words and more still", timeline.advance(5_000)?.source)
        assertNull(timeline.advance(5_100))
    }

    @Test
    fun `a cue produced after its moment shows at once`() {
        // The recognizer fell behind: the audio is already playing when the line exists. It must
        // appear immediately — holding it would only make the lag worse.
        val timeline = CaptionTimeline()
        timeline.offerSource(5_000, "late line")

        assertEquals("late line", timeline.advance(9_000)?.source)
    }

    @Test
    fun `a cue from a discarded timeline is dropped rather than shown stale`() {
        val timeline = CaptionTimeline()
        timeline.offerSource(1_000, "before the seek")

        assertNull(timeline.advance(40_000))
    }

    @Test
    fun `an out-of-order older cue cannot replace a newer line`() {
        val timeline = CaptionTimeline()
        timeline.offerSource(10_000, "newer line")
        assertEquals("newer line", timeline.advance(10_000)?.source)

        timeline.offerSource(9_000, "older line")
        assertNull(timeline.advance(10_100))
        assertNull(timeline.advance(10_200))
    }

    @Test
    fun `clearing pending keeps the line already on screen`() {
        val timeline = CaptionTimeline()
        timeline.offerSource(1_000, "on screen")
        assertEquals("on screen", timeline.advance(1_000)?.source)

        timeline.offerSource(5_000, "never heard")
        timeline.clearPending()
        assertNull(timeline.advance(6_000))
    }

    @Test
    fun `after a discontinuity a cue from the restarted timeline still publishes`() {
        // A renderer flush drops the measured hold-back, so the new timeline's stamps start from a
        // FRESH, smaller anchor that sits below the last published cue's time. The anti-regression
        // guard must not read that as an out-of-order offer and swallow the new session's first cues.
        val timeline = CaptionTimeline()
        timeline.offerSource(10_000, "old timeline")
        assertEquals("old timeline", timeline.advance(10_000)?.source)

        timeline.clearPending()
        timeline.offerSource(2_000, "new timeline")
        assertEquals("new timeline", timeline.advance(2_000)?.source)
    }

    @Test
    fun `reset forgets the visible line too`() {
        val timeline = CaptionTimeline()
        timeline.offerSource(1_000, "on screen")
        timeline.advance(1_000)

        timeline.reset()
        assertNull(timeline.advance(2_000))
    }

    @Test
    fun `the translated pair advances on its own timeline`() {
        val timeline = CaptionTimeline()
        val pair = SubtitlePair("Arsenal win a corner", "阿森纳获得角球")
        timeline.offerSource(1_000, "Arsenal win a corner")
        timeline.offerPair(6_000, pair)

        val atOne = timeline.advance(1_000)
        assertEquals("Arsenal win a corner", atOne?.source)
        assertNull(atOne?.pair)

        val atSix = timeline.advance(6_000)
        assertEquals(pair, atSix?.pair)
        assertEquals("Arsenal win a corner", atSix?.source)
    }

    @Test
    fun `a pause-free caller sees cues in order`() {
        // The presentation clock is the caller's; this is the shape the manager uses — offers from
        // one thread, advance from another, each due cue surfacing once.
        val timeline = CaptionTimeline()
        timeline.offerSource(0, "kick off")
        timeline.offerSource(500, "kick off and Arsenal")
        timeline.offerSource(1_000, "kick off and Arsenal attack")
        val seen = mutableListOf<String>()
        for (now in 0..1_200 step 80) {
            timeline.advance(now.toLong())?.source?.let { if (seen.lastOrNull() != it) seen.add(it) }
        }
        assertEquals(listOf("kick off", "kick off and Arsenal", "kick off and Arsenal attack"), seen)
    }
}
