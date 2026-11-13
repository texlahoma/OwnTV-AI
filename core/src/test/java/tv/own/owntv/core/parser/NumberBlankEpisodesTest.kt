package tv.own.owntv.core.parser

import org.junit.Assert.assertEquals
import org.junit.Test

/** A blank `episode_num` takes its place in the season; a real one is never touched. */
class NumberBlankEpisodesTest {

    private fun ep(id: String, season: Int, number: Int) = XtEpisode(id, season, number, "", "mkv")

    @Test
    fun blankNumbersTakeTheirPositionWithinTheirSeason() {
        val out = numberBlankEpisodes(listOf(ep("a", 1, 0), ep("b", 1, 0), ep("c", 2, 0), ep("d", 1, 0)))
        assertEquals(listOf(1, 2, 1, 3), out.map { it.episodeNumber })
    }

    @Test
    fun realNumbersStayAsSentAndAGapIsFilledByPosition() {
        val out = numberBlankEpisodes(listOf(ep("a", 1, 1), ep("b", 1, 2), ep("c", 1, 0), ep("d", 1, 4)))
        assertEquals(listOf(1, 2, 3, 4), out.map { it.episodeNumber })
    }

    @Test
    fun specialsAreNumberedOnTheirOwn() {
        val out = numberBlankEpisodes(listOf(ep("s", 0, 0), ep("a", 1, 5), ep("t", 0, 0)))
        assertEquals(listOf(1, 5, 2), out.map { it.episodeNumber })
    }
}
