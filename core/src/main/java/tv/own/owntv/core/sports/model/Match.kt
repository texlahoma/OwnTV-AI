package tv.own.owntv.core.sports.model

/** Where a match stands. ESPN's own `pre / in / post`, with the two ways a match does not happen split out. */
enum class MatchState { PRE, IN, POST, POSTPONED, CANCELLED }

/**
 * A team, or a single athlete in tennis, MMA and racing ([isAthlete]). [id] is ESPN's, unique within a
 * sport. [country] is set for athletes, from ESPN's flag ("Georgia").
 */
data class Team(
    val id: String,
    val name: String,
    val shortName: String = name,
    val abbreviation: String = "",
    val location: String? = null,
    val logoUrl: String? = null,
    val isAthlete: Boolean = false,
    val country: String? = null,
)

/** One period's score: a quarter's points, a set's games (with the tie-break points when one was played). */
data class PeriodScore(val value: Int, val tiebreak: Int? = null)

/**
 * A side's score. [text] is what ESPN shows and is always present ("2", "86/4", "158/6"); [value] is the
 * number where the sport has one (goals, points, cricket runs), so a goal can be detected by comparing it.
 */
data class Score(val text: String, val value: Int?, val periods: List<PeriodScore> = emptyList())

data class Competitor(
    val team: Team,
    val home: Boolean,
    val winner: Boolean?,
    val score: Score?,
)

/**
 * The match clock as shown: [display] is "67'", "Q3 4:12" pieces or "", [period] the half, quarter,
 * set or innings, and [overs] a cricket innings' overs ("14.1") when ESPN gives them. [halfTime] is
 * ESPN's own half-time status, so the half-time alert never reads display text.
 */
data class MatchClock(val display: String, val period: Int, val overs: String? = null, val halfTime: Boolean = false)

/** A broadcaster ESPN names (US only in practice: "Peacock", "Prime Video"). */
data class Broadcast(val name: String, val market: String? = null)

/** One thing that happened in a match, from a scoreboard's details or a summary's timeline. */
data class MatchEvent(
    val kind: Kind,
    val minute: String,
    val teamId: String?,
    val players: List<String>,
    val text: String? = null,
) {
    enum class Kind { GOAL, OWN_GOAL, PENALTY_GOAL, YELLOW_CARD, RED_CARD, SUBSTITUTION, WICKET, KICKOFF, HALF_TIME, FULL_TIME }
}

/**
 * One match on the board. [id] is ESPN's event id (or `<event>-<n>` for a racing session); [key] adds
 * the league, which is what the board and the caches are keyed by. [feed] is the followed league's key
 * it was fetched under, which differs from the league for cricket (`cricket/all`, one feed for every
 * series). A racing session has no [competitors]; a match has two, home first.
 */
data class Match(
    val id: String,
    val sport: Sport,
    val feed: String,
    val leagueSlug: String,
    val leagueName: String,
    val startMs: Long,
    val state: MatchState,
    val statusText: String,
    val title: String,
    val subtitle: String? = null,
    val clock: MatchClock? = null,
    val competitors: List<Competitor> = emptyList(),
    val venue: String? = null,
    val broadcasts: List<Broadcast> = emptyList(),
    val events: List<MatchEvent> = emptyList(),
) {
    val key: String get() = "${League.keyOf(sport, leagueSlug)}#$id"
    val home: Competitor? get() = competitors.firstOrNull { it.home } ?: competitors.getOrNull(0)
    val away: Competitor? get() = competitors.firstOrNull { !it.home && it !== home } ?: competitors.getOrNull(1)
    val isLive: Boolean get() = state == MatchState.IN
}
