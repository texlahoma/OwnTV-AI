package tv.own.owntv.core.content

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import tv.own.owntv.core.database.entity.EpisodeEntity

/** Regular seasons first, Specials after the last of them — for what opens and what plays next. */
class EpisodeOrderTest {

    private fun ep(season: Int, number: Int) =
        EpisodeEntity(seriesId = 1, seasonNumber = season, episodeNumber = number, name = "", streamUrl = "u$season-$number")

    @Test
    fun specialsComeAfterTheLastRegularSeason() {
        val sorted = listOf(ep(0, 1), ep(2, 1), ep(1, 2), ep(0, 2), ep(1, 1)).sortedWith(EpisodeOrder.episodes)
        assertEquals(listOf(1 to 1, 1 to 2, 2 to 1, 0 to 1, 0 to 2), sorted.map { it.seasonNumber to it.episodeNumber })
    }

    @Test
    fun aShowOpensOnItsFirstRegularSeason() {
        assertEquals(1, EpisodeOrder.openingSeason(listOf(0, 1, 2)))
        assertEquals(3, EpisodeOrder.openingSeason(listOf(0, 3, 4)))
        assertEquals(0, EpisodeOrder.openingSeason(listOf(0))) // nothing but Specials
        assertNull(EpisodeOrder.openingSeason(emptyList()))
    }

    @Test
    fun theNextSeasonSkipsGapsThenGoesToTheSpecials() {
        val seasons = listOf(0, 1, 2, 4)
        assertEquals(2, EpisodeOrder.seasonAfter(seasons, 1))
        assertEquals(4, EpisodeOrder.seasonAfter(seasons, 2)) // season 3 is missing
        assertEquals(0, EpisodeOrder.seasonAfter(seasons, 4)) // the Specials follow the finale
        assertNull(EpisodeOrder.seasonAfter(seasons, 0)) // nothing after the Specials
        assertNull(EpisodeOrder.seasonAfter(listOf(1, 2), 2))
    }
}
