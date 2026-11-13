package tv.own.owntv.core.sports

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import tv.own.owntv.core.sports.model.Competitor
import tv.own.owntv.core.sports.model.Match
import tv.own.owntv.core.sports.model.MatchState
import tv.own.owntv.core.sports.model.Sport
import tv.own.owntv.core.sports.model.Team

class SportsHomeRowTest {

    private val now = 1_760_000_000_000L
    private val hour = 3_600_000L

    private fun m(id: String, feed: String, state: MatchState, start: Long, home: String = "h$id") = Match(
        id, Sport.SOCCER, feed, feed.substringAfter('/'), "", start, state, "", "",
        competitors = listOf(Competitor(Team(home, home), true, null, null), Competitor(Team("a$id", "a$id"), false, null, null)),
    )

    private val config = SportsConfig(teams = listOf(FollowedTeam(Sport.SOCCER, "364", "Liverpool")))
    private val followed = setOf("soccer/ger.1")

    private fun board(vararg today: Match) = SportsBoard(today = mapOf("x" to today.toList()))

    @Test fun `off when nothing followed is live or starting within the hour`() {
        val later = m("1", "soccer/ger.1", MatchState.PRE, now + 3 * hour)
        val unfollowedLive = m("2", "soccer/esp.1", MatchState.IN, now - hour)
        assertTrue(SportsHomeRow.items(board(later, unfollowedLive), config, followed, now).isEmpty())
    }

    @Test fun `on while a followed match is live, then lists what follows`() {
        val live = m("1", "soccer/ger.1", MatchState.IN, now - hour)
        val saturday = m("2", "soccer/eng.1", MatchState.PRE, now + 48 * hour, home = "364")
        val done = m("3", "soccer/ger.1", MatchState.POST, now - 3 * hour)
        assertEquals(listOf("1", "2"), SportsHomeRow.items(board(saturday, live, done), config, followed, now).map { it.id })
    }

    @Test fun `on within the hour before kick-off, my teams ahead of their league`() {
        val soonLeague = m("1", "soccer/ger.1", MatchState.PRE, now + 20 * 60_000)
        val soonMine = m("2", "soccer/eng.1", MatchState.PRE, now + 40 * 60_000, home = "364")
        assertEquals(listOf("2", "1"), SportsHomeRow.items(board(soonLeague, soonMine), config, followed, now).map { it.id })
    }

    @Test fun `a passed kick-off ESPN has not called live yet keeps the row on, for three hours`() {
        val late = m("1", "soccer/ger.1", MatchState.PRE, now - 5 * 60_000)
        assertEquals(listOf("1"), SportsHomeRow.items(board(late), config, followed, now).map { it.id })
        val abandoned = m("2", "soccer/ger.1", MatchState.PRE, now - 4 * hour)
        assertTrue(SportsHomeRow.items(board(abandoned), config, followed, now).isEmpty())
    }
}
