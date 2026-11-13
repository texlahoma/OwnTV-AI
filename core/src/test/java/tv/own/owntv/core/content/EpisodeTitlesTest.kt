package tv.own.owntv.core.content

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class EpisodeTitlesTest {

    @Test
    fun showNameAndCodeAreStripped() =
        assertEquals("It's Like a Game", EpisodeTitles.clean("Solo Leveling - S01E03 - It's Like a Game", "Solo Leveling"))

    @Test
    fun showNameMatchIgnoresCase() =
        assertEquals("It's Like a Game", EpisodeTitles.clean("SOLO LEVELING S01E03 It's Like a Game", "Solo Leveling"))

    @Test
    fun spacedCodeIsStripped() =
        assertEquals("It's Like a Game", EpisodeTitles.clean("S1 E3 – It's Like a Game", "Solo Leveling"))

    @Test
    fun crossCodeIsStripped() = assertEquals("Pilot", EpisodeTitles.clean("1x01 Pilot", "Lost"))

    @Test
    fun plainTitleIsKept() = assertEquals("It's Like a Game", EpisodeTitles.clean("It's Like a Game", "Solo Leveling"))

    @Test
    fun aTitleThatOnlyStartsLikeTheShowIsKept() =
        assertEquals("Lost Souls", EpisodeTitles.clean("Lost Souls", "Lost"))

    @Test
    fun nothingLeftIsNull() = assertNull(EpisodeTitles.clean("Solo Leveling - S01E03", "Solo Leveling"))

    @Test
    fun aBareNumberIsNull() = assertNull(EpisodeTitles.clean("Solo Leveling - 3", "Solo Leveling"))

    @Test
    fun blankIsNull() = assertNull(EpisodeTitles.clean("  ", "Solo Leveling"))
}
