package tv.own.owntv.player

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * "Did this item finish?" decides whether a resume position is kept and whether a queue
 * auto-advances. The old flat 8 s tolerance said yes at position 0 for anything shorter than 8 s.
 */
class VodEndDetectionTest {

    @Test
    fun `a normal episode is finished only near its end`() {
        val dur = 45 * 60_000L // 45 minutes
        assertFalse(OwnTVPlayer.reachedEnd(dur, 0))
        assertFalse(OwnTVPlayer.reachedEnd(dur, dur / 2))
        assertFalse(OwnTVPlayer.reachedEnd(dur, dur - 8_001))
        assertTrue(OwnTVPlayer.reachedEnd(dur, dur - 8_000))
        assertTrue(OwnTVPlayer.reachedEnd(dur, dur))
    }

    @Test
    fun `a short clip is not finished the moment it opens`() {
        // The regression: a 5 s clip satisfied `pos >= dur - 8000` at position 0, so it "completed"
        // instantly — no resume, and in a queue it auto-advanced straight past itself.
        val dur = 5_000L
        assertFalse(OwnTVPlayer.reachedEnd(dur, 0))
        assertFalse(OwnTVPlayer.reachedEnd(dur, 1_000))
        assertTrue(OwnTVPlayer.reachedEnd(dur, 3_750)) // within a quarter of the end
        assertTrue(OwnTVPlayer.reachedEnd(dur, dur))
    }

    @Test
    fun `the quarter-length cap only affects items under 32 seconds`() {
        // At exactly 32 s, duration/4 == the flat 8 s tolerance, so behaviour is unchanged above it.
        assertTrue(OwnTVPlayer.reachedEnd(32_000L, 24_000L))
        assertFalse(OwnTVPlayer.reachedEnd(32_000L, 23_999L))
        assertTrue(OwnTVPlayer.reachedEnd(60_000L, 52_000L))
        assertFalse(OwnTVPlayer.reachedEnd(60_000L, 51_999L))
    }

    @Test
    fun `an unknown duration is never treated as finished`() {
        // A live stream or a still-probing file reports 0 — guessing "finished" there would wipe a
        // resume position or auto-advance a queue for no reason.
        assertFalse(OwnTVPlayer.reachedEnd(0L, 0L))
        assertFalse(OwnTVPlayer.reachedEnd(0L, 500_000L))
        assertFalse(OwnTVPlayer.reachedEnd(-1L, 0L))
    }
}
