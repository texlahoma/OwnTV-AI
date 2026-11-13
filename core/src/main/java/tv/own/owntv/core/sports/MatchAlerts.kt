package tv.own.owntv.core.sports

import org.json.JSONObject
import tv.own.owntv.core.sports.model.Match
import tv.own.owntv.core.sports.model.MatchEvent
import tv.own.owntv.core.sports.model.MatchState
import tv.own.owntv.core.sports.model.Sport

/** Something worth telling the user about a match. [teamId] is the side it happened to. */
data class MatchAlert(val kind: Kind, val match: Match, val teamId: String? = null, val player: String? = null) {
    enum class Kind { KICKOFF, GOAL, WICKET, RED_CARD, HALF_TIME, FULL_TIME, POSTPONED, CORRECTED }
}

/**
 * What the last check saw of a match — the "tiny file" behind goal alerts, one entry per match. Scores
 * are the numeric values; cricket's wickets come from its text ("86/4" → 4).
 */
data class AlertSnapshot(
    val state: MatchState,
    val home: Int?,
    val away: Int?,
    val homeWickets: Int?,
    val awayWickets: Int?,
    val redCards: Int,
    val halfTime: Boolean,
) {
    fun toJson(): JSONObject = JSONObject().put("s", state.name).putOpt("h", home).putOpt("a", away)
        .putOpt("hw", homeWickets).putOpt("aw", awayWickets).put("r", redCards).put("ht", halfTime)

    companion object {
        fun of(m: Match) = AlertSnapshot(
            state = m.state,
            home = m.home?.score?.value,
            away = m.away?.score?.value,
            homeWickets = m.home?.score?.text?.let(::wickets),
            awayWickets = m.away?.score?.text?.let(::wickets),
            redCards = m.events.count { it.kind == MatchEvent.Kind.RED_CARD },
            halfTime = m.clock?.halfTime == true,
        )

        fun fromJson(o: JSONObject): AlertSnapshot? = runCatching {
            AlertSnapshot(
                MatchState.valueOf(o.getString("s")),
                o.optIntOrNull("h"), o.optIntOrNull("a"), o.optIntOrNull("hw"), o.optIntOrNull("aw"),
                o.optInt("r"), o.optBoolean("ht"),
            )
        }.getOrNull()

        private fun JSONObject.optIntOrNull(k: String): Int? = if (has(k)) optInt(k) else null

        /** The current innings: "86/4" → 4, "312/7d & 86/4" → 4, "158" (all out) → null. */
        internal fun wickets(text: String): Int? =
            text.substringAfterLast('/', "").takeWhile(Char::isDigit).toIntOrNull()
    }
}

/** The pure rules of goal alerts: who gets them, and what changed between two checks. Unit-tested. */
object MatchAlertDiff {

    /** Sports where every change of the score is worth an alert. Basketball and AFL score too often. */
    private val SCORE_SPORTS = setOf(
        Sport.SOCCER, Sport.HOCKEY, Sport.FOOTBALL, Sport.BASEBALL, Sport.RUGBY, Sport.RUGBY_LEAGUE, Sport.LACROSSE,
    )

    /**
     * Whether [match] is alerted for this profile: alerts on, and the match involves a followed team
     * (when "My teams" is on) or belongs to a followed league (when "Followed leagues" is on).
     */
    fun wants(config: SportsConfig, followedLeagues: Set<String>, match: Match): Boolean {
        val a = config.alerts
        if (!a.enabled) return false
        val teamKeys = config.teams.map { it.key }.toSet()
        val mine = a.myTeams && match.competitors.any { FollowedTeam(match.sport, it.team.id, "").key in teamKeys }
        val league = a.followedLeagues && match.feed in followedLeagues
        return mine || league
    }

    /** What changed from [before] to [now], kept to the [kinds] the user chose. The first check reports only a kick-off. */
    fun diff(before: AlertSnapshot?, now: Match, kinds: Set<AlertKind>): List<MatchAlert> {
        val after = AlertSnapshot.of(now)
        val out = mutableListOf<MatchAlert>()
        if (before == null) {
            if (now.state == MatchState.IN) out += MatchAlert(MatchAlert.Kind.KICKOFF, now)
            return out.filter { it.kind.allowedBy(kinds) }
        }
        if (before.state == MatchState.PRE && now.state == MatchState.IN) out += MatchAlert(MatchAlert.Kind.KICKOFF, now)
        if (now.sport == Sport.CRICKET) {
            wicket(before.homeWickets, after.homeWickets, now, now.home?.team?.id)?.let(out::add)
            wicket(before.awayWickets, after.awayWickets, now, now.away?.team?.id)?.let(out::add)
        } else if (now.sport in SCORE_SPORTS) {
            score(before.home, after.home, now, now.home?.team?.id)?.let(out::add)
            score(before.away, after.away, now, now.away?.team?.id)?.let(out::add)
        }
        if (after.redCards > before.redCards) {
            val card = now.events.lastOrNull { it.kind == MatchEvent.Kind.RED_CARD }
            out += MatchAlert(MatchAlert.Kind.RED_CARD, now, card?.teamId, card?.players?.firstOrNull())
        }
        if (after.halfTime && !before.halfTime) out += MatchAlert(MatchAlert.Kind.HALF_TIME, now)
        if (before.state != MatchState.POST && now.state == MatchState.POST) out += MatchAlert(MatchAlert.Kind.FULL_TIME, now)
        val off = setOf(MatchState.POSTPONED, MatchState.CANCELLED)
        if (before.state !in off && now.state in off) out += MatchAlert(MatchAlert.Kind.POSTPONED, now)
        return out.filter { it.kind.allowedBy(kinds) }
    }

    /** Whether checking can stop for good. */
    fun finished(m: Match): Boolean = m.state == MatchState.POST || m.state == MatchState.POSTPONED || m.state == MatchState.CANCELLED

    private fun score(before: Int?, after: Int?, m: Match, teamId: String?): MatchAlert? = when {
        before == null || after == null || before == after -> null
        // A goal taken back (VAR, a correction): say so once, never as a goal.
        after < before -> MatchAlert(MatchAlert.Kind.CORRECTED, m, teamId)
        else -> {
            val scorer = m.events.lastOrNull { it.teamId == teamId && it.kind in GOALS }
            MatchAlert(MatchAlert.Kind.GOAL, m, teamId, scorer?.players?.firstOrNull())
        }
    }

    private fun wicket(before: Int?, after: Int?, m: Match, teamId: String?): MatchAlert? = when {
        after == null || (before ?: 0) >= after -> null
        else -> MatchAlert(MatchAlert.Kind.WICKET, m, teamId)
    }

    private val GOALS = setOf(MatchEvent.Kind.GOAL, MatchEvent.Kind.PENALTY_GOAL, MatchEvent.Kind.OWN_GOAL)

    private fun MatchAlert.Kind.allowedBy(kinds: Set<AlertKind>): Boolean = when (this) {
        MatchAlert.Kind.GOAL, MatchAlert.Kind.WICKET, MatchAlert.Kind.CORRECTED -> AlertKind.GOALS in kinds
        MatchAlert.Kind.KICKOFF, MatchAlert.Kind.FULL_TIME, MatchAlert.Kind.POSTPONED -> AlertKind.KICKOFF_FULL_TIME in kinds
        MatchAlert.Kind.HALF_TIME -> AlertKind.HALF_TIME in kinds
        MatchAlert.Kind.RED_CARD -> AlertKind.RED_CARDS in kinds
    }
}
