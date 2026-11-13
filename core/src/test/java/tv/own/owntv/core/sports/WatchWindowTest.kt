package tv.own.owntv.core.sports

import org.junit.Assert.assertEquals
import org.junit.Test
import tv.own.owntv.core.sports.model.Match
import tv.own.owntv.core.sports.model.MatchState
import tv.own.owntv.core.sports.model.Sport

/** Find channels' window: 30 min before kick-off until 30 min after the real end. */
class WatchWindowTest {

    private val min = 60_000L
    private val kickoff = 1_000_000 * min

    private fun match(state: MatchState) = Match("1", Sport.SOCCER, "soccer/ger.1", "ger.1", "Bundesliga", kickoff, state, "", "A v B")

    @Test fun `before kick-off - closed until 30 minutes before, then open`() {
        assertEquals(WatchWindow.TooEarly(kickoff - 30 * min), WatchWindow.of(match(MatchState.PRE), null, kickoff - 31 * min))
        assertEquals(WatchWindow.Open, WatchWindow.of(match(MatchState.PRE), null, kickoff - 30 * min))
        assertEquals("still listed as not started after kick-off", WatchWindow.Open, WatchWindow.of(match(MatchState.PRE), null, kickoff + 10 * min))
    }

    @Test fun `live is open however long it runs - extra time, penalties`() {
        assertEquals(WatchWindow.Open, WatchWindow.of(match(MatchState.IN), null, kickoff + 170 * min))
    }

    @Test fun `finished - open until 30 minutes after the seen end, else 30 after the assumed end`() {
        val end = kickoff + 140 * min // went to extra time
        assertEquals(WatchWindow.Open, WatchWindow.of(match(MatchState.POST), end, end + 29 * min))
        assertEquals(WatchWindow.Ended, WatchWindow.of(match(MatchState.POST), end, end + 30 * min))
        assertEquals(WatchWindow.Open, WatchWindow.of(match(MatchState.POST), null, kickoff + 149 * min))
        assertEquals(WatchWindow.Ended, WatchWindow.of(match(MatchState.POST), null, kickoff + 150 * min))
    }

    @Test fun `postponed and cancelled never open`() {
        assertEquals(WatchWindow.Ended, WatchWindow.of(match(MatchState.POSTPONED), null, kickoff))
        assertEquals(WatchWindow.Ended, WatchWindow.of(match(MatchState.CANCELLED), null, kickoff))
    }

    @Test fun `the end is when a match seen under way is first seen finished`() {
        val live = SportsBoard(today = mapOf("soccer/ger.1" to listOf(match(MatchState.IN))))
        val ended = live.withEnds(listOf(match(MatchState.POST)), kickoff + 125 * min)
        assertEquals(mapOf(match(MatchState.IN).key to kickoff + 125 * min), ended.endedAt)
        assertEquals("not moved by later copies", ended.endedAt, ended.withEnds(listOf(match(MatchState.POST)), kickoff + 200 * min).endedAt)
        assertEquals("never seen live: no end recorded", emptyMap<String, Long>(), SportsBoard().withEnds(listOf(match(MatchState.POST)), kickoff).endedAt)
    }
}
