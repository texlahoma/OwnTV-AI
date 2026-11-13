package tv.own.owntv.core.sports

import tv.own.owntv.core.sports.model.Match
import tv.own.owntv.core.sports.model.MatchState

/**
 * Home's "Live sports" row. The row exists **only while a followed match is live
 * or kicks off within the hour**; then it lists every followed match still to come or under way —
 * live first, then by kick-off, the user's teams ahead of their leagues — so a "Sat" and
 * "Tomorrow" cards ride along behind the live one. Off otherwise: an empty list.
 *
 * The board comes from [SportsRepository] under [Demand.HomeRow]. Both apps' Home `when`s are
 * exhaustive, so a new `HomeRow` constant needs a branch in each.
 */
object SportsHomeRow {

    const val SOON_MS = 60 * 60_000L
    const val MAX_CARDS = 12
    private const val STALE_MS = 3 * 60 * 60_000L

    fun items(board: SportsBoard, config: SportsConfig, followedLeagues: Set<String>, now: Long): List<Match> {
        val teamKeys = config.teams.map { it.key }.toSet()
        fun mine(m: Match) = m.competitors.any { FollowedTeam(m.sport, it.team.id, "").key in teamKeys }
        // A kick-off that has passed while ESPN still says "pre" counts as live, as in the refresh policy,
        // for [STALE_MS]; after that it is a match ESPN never updated, not one to show.
        val followed = board.matches.filter { m ->
            (m.state == MatchState.IN || (m.state == MatchState.PRE && m.startMs > now - STALE_MS)) &&
                (mine(m) || m.feed in followedLeagues)
        }
        val showing = followed.any { it.state == MatchState.IN || it.startMs - now <= SOON_MS }
        if (!showing) return emptyList()
        return followed
            .sortedWith(compareBy({ it.state != MatchState.IN }, { !mine(it) }, { it.startMs }))
            .take(MAX_CARDS)
    }
}
