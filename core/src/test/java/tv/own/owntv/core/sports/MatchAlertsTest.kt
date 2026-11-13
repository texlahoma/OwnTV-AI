package tv.own.owntv.core.sports

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import tv.own.owntv.core.sports.model.Competitor
import tv.own.owntv.core.sports.model.Match
import tv.own.owntv.core.sports.model.MatchClock
import tv.own.owntv.core.sports.model.MatchEvent
import tv.own.owntv.core.sports.model.MatchState
import tv.own.owntv.core.sports.model.Score
import tv.own.owntv.core.sports.model.Sport
import tv.own.owntv.core.sports.model.Team

class MatchAlertsTest {

    private val liverpool = Team("364", "Liverpool", "Liverpool", "LIV")
    private val brentford = Team("337", "Brentford", "Brentford", "BRE")
    private val all = AlertKind.entries.toSet()

    private fun m(
        state: MatchState = MatchState.IN, home: Int = 0, away: Int = 0, ht: Boolean = false,
        events: List<MatchEvent> = emptyList(), sport: Sport = Sport.SOCCER, homeText: String = "$home", awayText: String = "$away",
        start: Long = 1_000_000,
    ) = Match(
        "401", sport, "soccer/eng.1", "eng.1", "English Premier League", start, state, "", "Brentford at Liverpool",
        clock = MatchClock("38'", 1, halfTime = ht),
        competitors = listOf(
            Competitor(liverpool, true, null, Score(homeText, home)),
            Competitor(brentford, false, null, Score(awayText, away)),
        ),
        events = events,
    )

    private fun kinds(before: Match?, now: Match, chosen: Set<AlertKind> = all) =
        MatchAlertDiff.diff(before?.let(AlertSnapshot::of), now, chosen).map { it.kind }

    @Test fun `goal, with the scorer from the match's details`() {
        val now = m(home = 1, events = listOf(MatchEvent(MatchEvent.Kind.GOAL, "38'", "364", listOf("Salah"))))
        val alerts = MatchAlertDiff.diff(AlertSnapshot.of(m()), now, all)
        assertEquals(listOf(MatchAlert.Kind.GOAL), alerts.map { it.kind })
        assertEquals("364", alerts.single().teamId)
        assertEquals("Salah", alerts.single().player)
    }

    @Test fun `wicket in cricket, never a goal for runs`() {
        val before = m(sport = Sport.CRICKET, home = 86, homeText = "86/4")
        val now = m(sport = Sport.CRICKET, home = 92, homeText = "92/5")
        assertEquals(listOf(MatchAlert.Kind.WICKET), kinds(before, now))
        assertEquals(emptyList<MatchAlert.Kind>(), kinds(now, m(sport = Sport.CRICKET, home = 98, homeText = "98/5")))
        // A Test match's second innings: the wickets of the current innings count.
        assertEquals(4, AlertSnapshot.wickets("312/7d & 86/4"))
    }

    @Test fun `red card`() {
        val red = MatchEvent(MatchEvent.Kind.RED_CARD, "61'", "337", listOf("Pinnock"))
        val alert = MatchAlertDiff.diff(AlertSnapshot.of(m()), m(events = listOf(red)), all).single()
        assertEquals(MatchAlert.Kind.RED_CARD, alert.kind)
        assertEquals("Pinnock", alert.player)
    }

    @Test fun `half time once, full time once`() {
        assertEquals(listOf(MatchAlert.Kind.HALF_TIME), kinds(m(), m(ht = true)))
        assertEquals(emptyList<MatchAlert.Kind>(), kinds(m(ht = true), m(ht = true)))
        assertEquals(listOf(MatchAlert.Kind.FULL_TIME), kinds(m(home = 2), m(state = MatchState.POST, home = 2)))
        assertTrue(MatchAlertDiff.finished(m(state = MatchState.POST)))
    }

    @Test fun `kick-off, and postponed or cancelled`() {
        assertEquals(listOf(MatchAlert.Kind.KICKOFF), kinds(m(state = MatchState.PRE), m()))
        assertEquals(listOf(MatchAlert.Kind.POSTPONED), kinds(m(state = MatchState.PRE), m(state = MatchState.POSTPONED)))
        assertEquals(listOf(MatchAlert.Kind.POSTPONED), kinds(m(), m(state = MatchState.CANCELLED)))
        assertTrue(MatchAlertDiff.finished(m(state = MatchState.POSTPONED)))
    }

    @Test fun `a score corrected downwards is a correction, never a goal`() {
        assertEquals(listOf(MatchAlert.Kind.CORRECTED), kinds(m(home = 2), m(home = 1)))
    }

    @Test fun `only the chosen kinds, and the first check says nothing but a kick-off`() {
        val now = m(home = 1, ht = true)
        assertEquals(listOf(MatchAlert.Kind.GOAL), kinds(m(), now, setOf(AlertKind.GOALS)))
        assertEquals(listOf(MatchAlert.Kind.HALF_TIME), kinds(m(), now, setOf(AlertKind.HALF_TIME)))
        // After a reboot mid-match there is no last state: no burst of old goals.
        assertEquals(listOf(MatchAlert.Kind.KICKOFF), kinds(null, m(home = 3)))
        assertEquals(emptyList<MatchAlert.Kind>(), kinds(null, m(state = MatchState.PRE)))
    }

    @Test fun `basketball scores do not alert`() {
        assertEquals(emptyList<MatchAlert.Kind>(), kinds(m(sport = Sport.BASKETBALL, home = 88), m(sport = Sport.BASKETBALL, home = 90)))
    }

    // --- who is alerted, and what is scheduled ---

    private val mine = SportsConfig(
        teams = listOf(FollowedTeam(Sport.SOCCER, "364", "Liverpool")),
        alerts = GoalAlerts(enabled = true, whenClosed = true, myTeams = true, followedLeagues = false),
    )

    @Test fun `my teams, or followed leagues when chosen`() {
        assertTrue(MatchAlertDiff.wants(mine, emptySet(), m()))
        val other = m().copy(competitors = listOf(Competitor(Team("1", "Arsenal"), true, null, null), Competitor(brentford, false, null, null)))
        assertFalse(MatchAlertDiff.wants(mine, setOf("soccer/eng.1"), other))
        assertTrue(MatchAlertDiff.wants(mine.copy(alerts = mine.alerts.copy(followedLeagues = true)), setOf("soccer/eng.1"), other))
    }

    @Test fun `nothing is scheduled with alerts off`() {
        val pre = m(state = MatchState.PRE)
        for (config in listOf(mine.copy(alerts = mine.alerts.copy(enabled = false)), mine.copy(alerts = mine.alerts.copy(whenClosed = false)))) {
            val plan = MatchAlertScheduler.plan(AlertState(), listOf(pre), config, emptySet(), backgroundAllowed = true)
            assertTrue(plan.arm.isEmpty())
            assertTrue(plan.state.watched.isEmpty())
        }
    }

    @Test fun `the TV schedules nothing, even with a phone's settings synced to it`() {
        val plan = MatchAlertScheduler.plan(AlertState(), listOf(m(state = MatchState.PRE)), mine, emptySet(), backgroundAllowed = false)
        assertTrue(plan.arm.isEmpty())
        assertTrue(plan.state.watched.isEmpty())
    }

    @Test fun `schedule - arm new matches, keep a running check's chain, cancel what is no longer wanted`() {
        val pre = m(state = MatchState.PRE)
        val first = MatchAlertScheduler.plan(AlertState(), listOf(pre), mine, emptySet(), true)
        assertEquals(listOf(pre.key), first.arm.map { it.key })

        // Kicked off and being checked: a new board (now IN) must not re-arm it to "now" again.
        val checking = first.state.copy(seen = mapOf(pre.key to AlertSnapshot.of(m())))
        val second = MatchAlertScheduler.plan(checking, listOf(m()), mine, emptySet(), true)
        assertTrue(second.arm.isEmpty())
        assertTrue(pre.key in second.state.watched)

        // Turned off: cancel it and forget its state.
        val off = MatchAlertScheduler.plan(second.state, listOf(m()), mine.copy(alerts = mine.alerts.copy(enabled = false)), emptySet(), true)
        assertEquals(setOf(pre.key), off.cancel)
        assertTrue(off.state.seen.isEmpty())
    }

    @Test fun `a kick-off time that moves is re-armed`() {
        val pre = m(state = MatchState.PRE)
        val armed = MatchAlertScheduler.plan(AlertState(), listOf(pre), mine, emptySet(), true).state
        val moved = pre.copy(startMs = pre.startMs + 3_600_000)
        assertEquals(listOf(moved.startMs), MatchAlertScheduler.plan(armed, listOf(moved), mine, emptySet(), true).arm.map { it.startMs })
    }

    @Test fun `the state file round-trips`() {
        val s = AlertState(
            watched = mapOf("k" to m(home = 1)),
            seen = mapOf("k" to AlertSnapshot.of(m(home = 1, ht = true))),
            reminders = mapOf("k" to AlertState.Reminder(m(state = MatchState.PRE), 10, listOf("Sky Sport Bundesliga 1"))),
        )
        val back = AlertState.fromJson(s.toJson(now = 5))
        assertEquals(s.seen, back.seen)
        assertEquals(s.watched.getValue("k").key, back.watched.getValue("k").key)
        assertEquals(s.reminders.getValue("k").channels, back.reminders.getValue("k").channels)
        assertEquals(1_000_000L - 600_000L, back.reminders.getValue("k").atMs)
        assertEquals(AlertState(), AlertState.fromJson("not json"))
    }
}
