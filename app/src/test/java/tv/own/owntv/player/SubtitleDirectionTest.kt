package tv.own.owntv.player

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The direction the app-drawn subtitle overlay lays a cue out in. One direction for the whole
 * cue, so a Hebrew line that starts with a dash, a number or a Latin word still reads right to left.
 */
class SubtitleDirectionTest {

    @Test
    fun `hebrew and arabic cues are right to left on every line`() {
        assertTrue(isRtlSubtitle("-שלום, מה שלומך?\n-אני בסדר, תודה."))
        assertTrue(isRtlSubtitle("יש לי בבית\n3 ילדים קטנים."))
        assertTrue(isRtlSubtitle("זה היה מקלע\nM60 של הצבא."))
        assertTrue(isRtlSubtitle("Netflix מציגה\nסרט חדש היום."))
        assertTrue(isRtlSubtitle("إذا السيّد قال هذا،\nفهذا صحيح."))
    }

    @Test
    fun `latin cues and cues with no letters stay left to right`() {
        assertFalse(isRtlSubtitle("Only you can decide\nwhat's best for you, Jesse."))
        assertFalse(isRtlSubtitle("I saw it on שלום TV yesterday."))
        assertFalse(isRtlSubtitle("♪ ... ♪"))
    }
}
