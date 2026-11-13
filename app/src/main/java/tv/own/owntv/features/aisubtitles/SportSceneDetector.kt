package tv.own.owntv.features.aisubtitles

import tv.own.owntv.features.aisubtitles.SubtitleGlossary.Sport

/**
 * Caps for [SportSceneDetector.scopeSummary]: at most this many clubs are listed with their mention
 * counts and their matched surnames, and this many surnames are named per club. The line has to stay
 * a line — the full roster would bury the reading it exists to show — and the two clubs a fixture is
 * made of are all a scoping decision ever needs. The cap is on the list, not on the scoped clubs: a
 * scoped side always appears however few times it was named, because a side held by its players alone
 * is exactly the case the line exists to make visible.
 */
private const val SCOPE_DIAG_CLUBS = 3
private const val SCOPE_DIAG_NAMES = 3

/**
 * How long a club keeps its place once heard: the horizon within which the two sides of a fixture
 * must both be heard. A side is heard by its players as well as by its name ([lastHeardAt]), so a
 * spell the commentary spends on one side — naming only its players — does not retire that side. A
 * horizon of minutes still lets a fixture's clubs go once its match is over, so the next match on a
 * channel showing back-to-back games cannot be scoped by the previous one.
 */
private const val TEAM_MENTION_WINDOW_MS = 600_000L

/**
 * Bound on the mention log. A commentary can say a club name several times a sentence and an
 * unbounded log would grow with the broadcast, so a cap is needed — but it must sit ABOVE anything
 * the horizon can hold, or it silently shortens the horizon it exists to bound: evicting the oldest
 * entry early turns "named in the last ten minutes" into "named in the last N mentions", and a club
 * still being commentated could fall out. 4096 is ten minutes at ~6.8 mentions a second, several
 * times the rate a commentary names clubs at, so a real broadcast never reaches it; it is a memory
 * valve for pathological input, not a scope rule.
 */
private const val TEAM_MENTION_LOG_CAP = 4096

/**
 * How much of the processed text the mention pass keeps, in characters. The recogniser hands its
 * hypothesis over in incremental slices, so a two-word club name can arrive as `Manchester` and then
 * `City`; matching a slice on its own would see only `City` and — for `Hull City` or `Newcastle
 * United` — credit the WRONG club. Keeping a bounded tail and matching the join resolves the whole
 * name, and the tail is only ever trimmed at a word boundary so a name is never cut in half.
 */
private const val MENTION_MATCH_CHARS = 256

/**
 * How long the scoped clubs stand before a NON-completing change may rebuild the decoder's list.
 * Adding a side to an incomplete fixture is applied at once — that completion is what scoping exists
 * for — but a replacement flips the bias and re-creates the recogniser's stream, and instead of one
 * side answer, the two sides of a fixture trade the lead in the counts constantly. A swap that
 * follows the counts slice by slice is heard as a stutter. The dwell lets the counts settle; a
 * genuine change of fixture still lands, just not on the first slice that out-counts the incumbent.
 */
internal const val SCOPE_DWELL_MS = 30_000L

/**
 * How long an OPEN scene survives a lull, as a multiple of the text window it is scored against.
 *
 * The window alone made the scene age out after about one window of commentary without one of the
 * sport's terms, and a live match spends longer than that describing play through its players alone
 * (`Van Dijk clears`, `Salah carries it`) — which read as the scene flipping from football to empty
 * MID-MATCH. Each flip is not cosmetic: applying it re-creates the recogniser's stream
 * ([KrokoStreamingRecognizer.setHotwords]), which drops the sentence being decoded and the encoder
 * context behind it, so the flip cost both the sport's vocabulary and a visible stutter. Waiting for
 * a second window's worth of newer text before the scene may drop keeps it through those lulls.
 *
 * Only the return to NO scene is delayed. A DIFFERENT sport still takes the channel the moment it
 * outweighs the incumbent ([SportSceneDetector]'s challenger rule), so a real change of broadcast is
 * unaffected; what waits is the drop to no bias, which is the case this hysteresis exists for.
 */
private const val SPORT_HOLD_MULTIPLIER = 2

/**
 * Decides which sport — if any — a playback channel is currently showing, from the words the
 * recogniser is *already* decoding. No acoustic model, no extra inference, no second audio pass: the
 * transcript this pipeline produces anyway is the cheapest scene signal there is, and the words that
 * name a sport (`offside`, `deuce`, `snooker`) are exactly the ones the general-purpose model already
 * knows without help.
 *
 * ## Why this exists
 *
 * Contextual biasing is not free. Attaching a hotword list pushes the decoder toward those words and
 * away from whatever else it might have heard, so a football-heavy list on a news channel quietly
 * mangles ordinary speech. [SubtitleGlossary.recognitionHotwords] therefore ships a list per
 * [Sport], and this class is what picks one: the list only reaches the decoder once the channel has
 * *proved* the sport, so ordinary television is decoded with no bias at all.
 *
 * ## How it decides
 *
 * [observe] is fed one incremental slice of recognised words at a time (the cache the manager keeps
 * is the recogniser's ever-growing hypothesis, so only the truly new words are handed here — see
 * [AiSubtitleManager]). The slices are kept in a short rolling window of text and re-scored on every
 * slice, whole-word against [SubtitleGlossary.sceneHits]:
 *
 *  - the window is bounded in characters, so a sport word ages out a few sentences after the
 *    commentary stops saying it — that slide, plus the hold budget below, is the hysteresis, and it
 *    is why a channel returning to studio chat drops back to no bias without any timer or clock;
 *  - an incumbent sport keeps the channel while any of its terms remain in the window, and then for
 *    a further hold budget of newer text that does not name them ([SPORT_HOLD_MULTIPLIER]), because a
 *    live match calls long spells through its players alone — so a passing mention of another sport
 *    still cannot flap the bias, and neither can a lull;
 *  - but a channel can show SEVERAL sports in one session (a football match, then a tennis one), so
 *    the incumbent is not permanent: once another sport clears its own threshold ([enterHits]) AND
 *    carries strictly more evidence than the incumbent's fading terms, it takes the channel;
 *  - the entry threshold is why snooker (`snooker` is a rare word — one is proof) enters on one hit
 *    while football and tennis, whose terms can surface in passing, need two.
 *
 * ## Which clubs are on the pitch
 *
 * A football scene carries a second decision: which clubs the commentary is about, so their squads
 * can be added to the decoder's vocabulary. It is made by COUNTING club names
 * ([SubtitleGlossary.teamMentionHits]) over [TEAM_MENTION_WINDOW_MS] and taking the
 * [SubtitleGlossary.TEAM_FIXTURE_SIZE] the commentary names most — the two sides being commentated.
 *
 * Counting NAMES rather than surnames is deliberate, and it is what unblocks the decision at all. A
 * surname is the word the recogniser flattens hardest with no bias toward it, so a rule that demanded
 * a surname beside the club name (an earlier revision, `namedTeams`) was asking the surname pass to
 * work BEFORE it was allowed to help: the count sat at `arsenal 1` forever and no club ever scoped. A
 * club name, by contrast, is already in [SubtitleGlossary.recognitionHotwords] before any scoping and
 * decodes without help, and a commentary says it dozens of times an hour. The ambiguity of a short
 * form (`City`, `Palace`, `Hull`) is settled by the RANK instead: a club mentioned in passing cannot
 * out-count the two sides a match is actually being commentated on.
 *
 * The count is kept over a horizon of minutes rather than one batch, so the two sides need not be
 * named in the same breath. A side is heard by its PLAYERS as well as by its name
 * ([refreshFromEvidence]), so a spell the commentary spends describing one side entirely through its
 * squad holds that side in the rank; the horizon is minutes, so the previous match's clubs still
 * cannot scope the next one.
 *
 * The ranked clubs decide which squads the decoder is biased toward, and that bias is what makes the
 * surnames recognisable at all — which is why the squads enter the list only AFTER a club is ranked,
 * and why scoping to one side is allowed: the other is added when it is named.
 * [SubtitleGlossary.TEAM_FIXTURE_SIZE] caps the set at the two sides of a fixture, so a long horizon
 * keeps a fixture's worth rather than growing with every club it mentions. With no club ranked,
 * nothing is scoped and the unscoped list remains the baseline. [teams] is empty for any non-football
 * scene, and is read by the caller only alongside [sport].
 */
internal class SportSceneDetector(
    /**
     * Rolling-text budget. ~2400 chars is a few minutes of commentary.
     *
     * Deliberately generous. At the old ~600 chars the window was one or two sentences, so a
     * commentary that spent a short spell on something else (a substitution, a replay, a crowd shot)
     * aged every anchor out and the bias dropped to none — and re-opened on the next term, which on a
     * real match read as the decoder flickering between the sport's vocabulary and none. A window of
     * minutes keeps the scene through those lulls while still letting a genuine change of sport
     * (handled by [pick]'s challenger rule) take the channel.
     */
    private val maxChars: Int = 2400,
    /** Hits of a sport's own [SubtitleGlossary.sceneHits] it takes to open that scene. */
    private val enterHits: Map<Sport, Int> = mapOf(
        Sport.FOOTBALL to 2,
        Sport.TENNIS to 2,
        Sport.SNOOKER to 1,
    ),
    /**
     * Monotonic milliseconds. The mention horizon ([TEAM_MENTION_WINDOW_MS]) and the scope hold
     * ([SCOPE_DWELL_MS]) are ELAPSED time, so they are measured against a clock that cannot jump: a
     * wall clock corrected mid-match would expire every mention at once, or hold one forever.
     * Injected so both can be tested across minutes without waiting for them.
     */
    private val now: () -> Long = { System.nanoTime() / 1_000_000L },
) {

    /**
     * Absent-text budget before an OPEN scene may drop to no scene, derived from the window the
     * scene is scored against (see [SPORT_HOLD_MULTIPLIER]).
     */
    private val holdChars: Int = maxChars * SPORT_HOLD_MULTIPLIER

    /**
     * Characters of newer text seen since each sport's most recent hit — the reading [holdChars] is
     * compared against, and the reason a scene can outlive its own words. Clock-free on purpose: it
     * advances with the commentary, so silence (no words) does not age a scene, exactly as the window
     * itself does not.
     */
    private val absentChars = mutableMapOf<Sport, Int>()

    // The recognised words of the last few sentences, concatenated. A StringBuilder rather than a
    // word list so the whole-word matcher sees real word boundaries (see [SubtitleGlossary.sceneHits]).
    private val window = StringBuilder()

    /** The scene currently driving the decoder's bias, or null for none. */
    var sport: Sport? = null
        private set

    /**
     * The clubs whose squads the football hotword list carries: the clubs still being heard inside
     * [TEAM_MENTION_WINDOW_MS], ranked by how often the commentary NAMED them and capped at
     * [SubtitleGlossary.TEAM_FIXTURE_SIZE]. A side the commentary describes only through its players
     * keeps its place with no name of its own, and an INCOMPLETE fixture gets its missing side the same
     * way (see [refreshFromEvidence] and [discoverMissingSides]). The raw ranking is not
     * exposed, only the verdict, so the set the caller compares is the same set that decides the
     * list. Empty while no club is heard (or the scene is not football), which is the unscoped
     * baseline.
     */
    var teams: Set<SubtitleGlossary.Team> = emptySet()
        private set

    /**
     * Every club MENTION seen inside [TEAM_MENTION_WINDOW_MS], oldest first, each stamped with the
     * time it was counted. The rolling window is only a few minutes of commentary and is rebuilt from
     * each slice, so the fixture cannot be ranked from it alone — two sides named minutes apart would
     * never meet. Keeping the mentions for the horizon is what lets them meet, and what retires a club
     * that has genuinely gone quiet. Written on every [observe], read when [teams] is rebuilt.
     */
    private val teamMentions = ArrayDeque<TeamMention>()

    /** One club mention: which club, and when it was counted. */
    private class TeamMention(val team: SubtitleGlossary.Team, val at: Long)

    /**
     * When each club was last HEARD — named ([countMentions]), described through one of its
     * players ([refreshFromEvidence]), or FOUND through a player while the fixture was incomplete
     * ([discoverMissingSides]) — stamped with the time of the hearing. This is the liveness
     * clock: a club is a candidate for [teams] while it sits inside [TEAM_MENTION_WINDOW_MS] here,
     * and it is what the horizon retires.
     *
     * Kept apart from [teamMentions] because the count is the RANK and this is only presence: a side
     * the commentary is still describing by its players has to stay on the pitch even though it has
     * not been NAMED, and folding those hearings into the log would let the rank follow which side
     * the commentary names its players for rather than which side it names.
     */
    private val lastHeardAt = mutableMapOf<SubtitleGlossary.Team, Long>()

    /**
     * How many times each club was named inside [TEAM_MENTION_WINDOW_MS] — the rank [teams] is taken
     * from, refreshed on every [observe] and read by the diagnostics (see [scopeSummary]).
     */
    private var mentionCounts: Map<SubtitleGlossary.Team, Int> = emptyMap()

    /**
     * The set the RANK asks for, before [settleScope] decides what to apply. Equal to [teams] except
     * while a change waits out [SCOPE_DWELL_MS], and that gap is the thing no other reading shows: a
     * change of fixture has been detected and is being held, so the squads on the decoder are not the
     * ones the commentary has moved to. Reported as `pend` (see [scopeSummary]).
     */
    private var rankedScope: Set<SubtitleGlossary.Team> = emptySet()

    /**
     * The spoken surnames the CURRENT window carries for the ranked squads
     * ([SubtitleGlossary.squadEvidence]). [teams] reports only the verdict, so this is the reading the
     * diagnostics need to tell "no club ranked yet" apart from "ranked but its players' surnames never
     * matched" (see [scopeSummary]). Refreshed on every [observe], emptied when the scene is not
     * football.
     */
    private var windowEvidence: List<SubtitleGlossary.TeamEvidence> = emptyList()

    /** Characters in the rolling window, so a window that is not filling at all is visible. */
    private var windowChars: Int = 0

    /**
     * The tail of the text the mention pass has already matched, and the index its first character
     * has in the whole stream. [countMentions] matches the join of this and the next slice, which is
     * what resolves a club name the recogniser split across slices (see [MENTION_MATCH_CHARS]).
     */
    private var mentionText = ""
    private var mentionBase = 0L

    /**
     * The club-name ranges already counted, in absolute stream coordinates. A growing hypothesis
     * re-offers its tail on every slice, so the same mention is matched again and again; comparing
     * ranges is what keeps each mention counted once (see [countMentions]).
     */
    private val countedSpans = ArrayDeque<CountedSpan>()

    /** One counted club-name range, absolute in the stream. */
    private class CountedSpan(val first: Long, val last: Long)

    /** When the scoped set last changed, read by the [SCOPE_DWELL_MS] hold (see [settleScope]). */
    private var lastScopeChangeAt = 0L

    /**
     * Feed the words recognised since the previous call and re-decide. Returns the (possibly
     * unchanged) [sport]. Callers pass the incremental slice, not the whole hypothesis — appending
     * the re-emitted hypothesis would count the same term dozens of times and open a scene on a
     * single word.
     */
    fun observe(newWords: String): Sport? {
        var added = 0
        if (newWords.isNotBlank()) {
            if (window.isNotEmpty()) {
                window.append(' ')
                added++
            }
            window.append(newWords)
            added += newWords.length
            trim()
        }
        // One snapshot of the window, read by both decisions: the scene pick and the club rank.
        val text = window.toString()
        windowChars = window.length
        val hits = SubtitleGlossary.sceneHits(text)
        // Age each sport's evidence by the words just added, and reset it on a hit. This is what lets
        // an OPEN scene outlive its own words for [holdChars] (see [SPORT_HOLD_MULTIPLIER]); a sport
        // that is not an incumbent gets no benefit from it, because [pick] only consults it for one.
        for (s in enterHits.keys) {
            absentChars[s] = if ((hits[s] ?: 0) > 0) 0 else (absentChars[s] ?: 0) + added
        }
        sport = pick(hits)
        // Clubs are only meaningful once football is the scene, and only a football window can rank
        // them anyway; gating here keeps a tennis channel from paying for the club pass.
        teams = if (sport == Sport.FOOTBALL) {
            val at = now()
            countMentions(newWords, at)
            // A side is held by its players as well as its name, so it has to be refreshed from the
            // words just recognised BEFORE the horizon retires it (see [refreshFromEvidence]).
            refreshFromEvidence(newWords, at)
            // And a side NOT yet scoped can be FOUND the same way, so a fixture that has so far named
            // only one club still gets its opponent biased once an opposing player is named (see
            // [discoverMissingSides]).
            discoverMissingSides(newWords, at)
            expireMentions(at)
            mentionCounts = tallyMentions()
            val ranked = rankedTeams(at)
            rankedScope = ranked
            settleScope(ranked, at).also {
                windowEvidence = SubtitleGlossary.squadEvidence(it, text)
            }
        } else {
            // A different sport took the channel, so the clubs on the pitch changed with it. A null
            // scene is merely a lull and keeps the log — the horizon retires it if the lull is real.
            if (sport != null) {
                teamMentions.clear()
                lastHeardAt.clear()
            }
            mentionCounts = emptyMap()
            rankedScope = emptySet()
            windowEvidence = emptyList()
            emptySet()
        }
        return sport
    }

    /**
     * Hold the sides already scoped while the commentary describes them through their PLAYERS.
     *
     * Naming a club is the exception in a commentary, not the rule: a long spell is spent saying
     * `Van Dijk clears` and `Salah carries it`, with the club named only at the events. The rank
     * counts names, so on names alone the horizon would retire a side minutes into exactly the spell
     * that most needs its squad biased — and the surnames read for the scoped squads
     * ([SubtitleGlossary.squadEvidence]) are proof the side is still the one being described.
     *
     * Only the clubs already scoped are refreshed, so this can never put a club on the pitch that
     * the commentary did not name, and the count is untouched: this decides presence, not rank.
     *
     * [slice] is the words just recognised, never the whole window. The window is where the text the
     * horizon has already retired still sits, and a surname left in it would hold a side the
     * commentary has genuinely gone quiet on — the very thing the horizon exists to let go.
     */
    private fun refreshFromEvidence(slice: String, at: Long) {
        if (teams.isEmpty()) return
        for (reading in SubtitleGlossary.squadEvidence(teams, slice)) {
            if (reading.surnames.isNotEmpty()) lastHeardAt[reading.team] = at
        }
    }

    /**
     * Find the SECOND side of a half-named fixture from the words just recognised, so a match whose
     * commentary has so far named only one club still gets its opponent's squad biased.
     *
     * Naming both clubs is what [rankedTeams] wants, but a commentary opening on `Liverpool win a
     * free kick` and then `Van Dijk heads it clear` has named one side and described it through a
     * player; without this the opponent's squad goes unbiased for as long as it takes the second club
     * name to arrive. A surname that belongs to exactly one club identifies that club on its own
     * ([SubtitleGlossary.squadSidesIn]), so hearing one is the evidence that puts the missing side on
     * the pitch — presence only, with the count untouched so the rank still comes from NAMES, exactly
     * as [refreshFromEvidence] treats the sides already scoped.
     *
     * Runs only on a HALF-named fixture — exactly one side scoped. With both sides known the roster
     * pass has nothing to find and a third club's surname would be evidence about no fixture that is
     * on; with NO side named it must not run at all, because a player's name says which player was
     * heard, not which two clubs are playing, and a squad must never open a scope by itself. A single
     * candidate only — two clubs' surnames in one slice identify no one opponent, and biasing the
     * wrong side would cost the one already scoped its bias.
     */
    private fun discoverMissingSides(slice: String, at: Long) {
        if (slice.isBlank()) return
        if (teams.size != 1) return
        val candidate = SubtitleGlossary.squadSidesIn(slice) - teams
        if (candidate.size != 1) return
        lastHeardAt[candidate.first()] = at
    }

    /**
     * Count the club names [slice] brings into the stream, matching it joined to the tail already
     * seen rather than on its own.
     *
     * The recogniser hands its hypothesis over in increments, so a two-word name arrives in pieces —
     * `Hull` then `City`, `Newcastle` then `United`, `Manchester` then `City`. A slice matched alone
     * resolves that trailing word by itself, which for `Hull City` credits Manchester City and for
     * `Newcastle United` credits Manchester United: the longest-first rule in
     * [SubtitleGlossary.teamMentionSpans] never sees the two words together, so it cannot apply.
     * Matching the JOIN gives it both words, and the whole name wins as it should. The tail is bounded
     * ([MENTION_MATCH_CHARS]) and trimmed only at a word boundary, so it never starts mid-name.
     *
     * A range already counted is skipped: the tail is re-matched on every slice, and without that
     * check a mention would be counted once per re-emission and the rank would follow the slice rate
     * rather than the commentary. It also keeps a name from being counted twice as it grows — the
     * earlier `Newcastle` range overlaps the later `Newcastle United` one and is already counted.
     */
    private fun countMentions(slice: String, at: Long) {
        if (slice.isBlank()) return
        val joined = if (mentionText.isEmpty()) slice else "$mentionText $slice"
        if (joined.length > MENTION_MATCH_CHARS) {
            val space = joined.indexOf(' ', joined.length - MENTION_MATCH_CHARS)
            val drop = if (space >= 0) space + 1 else joined.length - MENTION_MATCH_CHARS
            mentionBase += drop
            mentionText = joined.substring(drop)
        } else {
            mentionText = joined
        }
        while (countedSpans.isNotEmpty() && countedSpans.first().last < mentionBase) {
            countedSpans.removeFirst()
        }
        // The words just recognised begin where the retained tail ends, so the generic-half guard
        // can tell a club name split across slices from a fresh mention that follows one (see
        // [SubtitleGlossary.teamMentionSpans]).
        val freshFrom = mentionText.length - slice.length
        for (span in SubtitleGlossary.teamMentionSpans(mentionText, freshFrom)) {
            val first = mentionBase + span.first
            val last = mentionBase + span.last
            if (countedSpans.any { it.first <= last && first <= it.last }) continue
            countedSpans.addLast(CountedSpan(first, last))
            teamMentions.addLast(TeamMention(span.team, at))
            lastHeardAt[span.team] = at
        }
        while (teamMentions.size > TEAM_MENTION_LOG_CAP) teamMentions.removeFirst()
    }

    /** Retire the mentions that have fallen out of [TEAM_MENTION_WINDOW_MS]. */
    private fun expireMentions(at: Long) {
        while (teamMentions.isNotEmpty() && teamMentions.first().at < at - TEAM_MENTION_WINDOW_MS) {
            teamMentions.removeFirst()
        }
    }

    /**
     * Hold [ranked] against the flicker of a live count. [ranked] COMPLETES the fixture when it still
     * contains every club already scoped — that is a side being added — and completion is applied at
     * once, because it is what scoping exists for. Any OTHER change replaces or drops a side, which
     * flips the decoder's bias and re-creates the recogniser's stream, so it waits out
     * [SCOPE_DWELL_MS] from the last change. The two sides of a fixture trade the count lead
     * constantly, and a club mentioned in a round-up can briefly out-count one of them; without the
     * hold each of those would rebuild the list mid-match, heard as a stutter. A genuine change of
     * fixture still lands, once its counts have stood.
     */
    private fun settleScope(
        ranked: Set<SubtitleGlossary.Team>,
        at: Long,
    ): Set<SubtitleGlossary.Team> {
        if (ranked == teams) return teams
        val completes = teams.all { it in ranked }
        if (completes || at - lastScopeChangeAt >= SCOPE_DWELL_MS) {
            lastScopeChangeAt = at
            return ranked
        }
        return teams
    }

    /** How many times each club was named inside the horizon. */
    private fun tallyMentions(): Map<SubtitleGlossary.Team, Int> {
        val counts = mutableMapOf<SubtitleGlossary.Team, Int>()
        for (mention in teamMentions) counts[mention.team] = (counts[mention.team] ?: 0) + 1
        return counts
    }

    /**
     * One line of the state that decides the club scope, for the on-screen and logcat diagnostics —
     * the reading [teams] cannot give, because it reports only the verdict.
     *
     * `w` is the rolling window's size, which says whether the detector is being fed text at all;
     * `names` is how many times each club was named inside the [TEAM_MENTION_WINDOW_MS] horizon, most
     * named first, which is the rank the fixture is taken from — `names=manchester_city 12*,liverpool
     * 9*` is a fixture being commentated, while `names=arsenal 2` is a club named twice and NOT
     * scoped. The scoped clubs carry `*`, and the two readings are deliberately not the same list: the
     * rank is taken from the top of `names`, but a side the commentary has stopped NAMING is held on
     * the pitch by its players ([lastHeardAt]), so it shows here with a count of `0` — `names=liverpool
     * 0*` is a squad still riding the decoder through `Van Dijk` alone, which read on names alone
     * would look like a side that had fallen out. `sur` is the spoken surnames the window carries for
     * the SCOPED squads, which says whether the squads handed to the decoder are being heard —
     * `sur=liverpool 1(van dijk)` is the bias working, `sur=liverpool 0()` after minutes of commentary
     * is it not; `pend` appears only while [settleScope] is holding a change back, and names the set
     * the rank is asking for, so a fixture being replaced is visible while it is held rather than only
     * once it lands; and the verdict is `scoped` or `idle`, the same decision [teams] carries. "off"
     * for any scene that is not football, which has nothing to scope.
     */
    fun scopeSummary(): String {
        if (sport != Sport.FOOTBALL) return "off"
        val scoped = teams
        val byRank = compareByDescending<SubtitleGlossary.Team> { mentionCounts[it] ?: 0 }
            .thenByDescending { lastHeardAt[it] ?: 0L }
            .thenBy { it.ordinal }
        // The named clubs and the scoped ones, which are not the same set: the rank comes from the
        // counts, but a side held by its players has a count of zero. Taking the top counts alone
        // would cut exactly that side out of the line that exists to show it, so the scoped clubs are
        // carried first and the remaining slots go to the best-counting club NOT scoped — the side
        // about to take the list, which is what `pend` is the verdict on.
        val challengers = mentionCounts.keys
            .filter { it !in scoped }
            .sortedWith(byRank)
            .take(SCOPE_DIAG_CLUBS - scoped.size)
        val names = (scoped + challengers)
            .sortedWith(byRank)
            .joinToString(",") {
                "${it.name.lowercase()} ${mentionCounts[it] ?: 0}${if (it in scoped) "*" else ""}"
            }
            .ifEmpty { "none" }
        val sur = windowEvidence
            .joinToString(",") {
                "${it.team.name.lowercase()} ${it.surnames.size}" +
                    "(${it.surnames.take(SCOPE_DIAG_NAMES).joinToString("|") { n -> n.lowercase() }})"
            }
            .ifEmpty { "none" }
        val pending = rankedScope - scoped
        val pend = if (pending.isEmpty()) {
            ""
        } else {
            " pend=" + pending.sortedBy { it.ordinal }.joinToString(",") { it.name.lowercase() }
        }
        val verdict = if (scoped.isEmpty()) "idle" else "scoped"
        return "w$windowChars names=$names sur=$sur$pend $verdict"
    }

    /**
     * The clubs to scope the football list to: the [SubtitleGlossary.TEAM_FIXTURE_SIZE] still HEARD
     * inside [TEAM_MENTION_WINDOW_MS], ranked by how often the commentary NAMED them.
     *
     * The count is the rank, so a club mentioned in passing cannot take the list from the fixture —
     * the round-up after the match names clubs too, and it must not push the fixture out. The most
     * recent hearing breaks what counts cannot, so of two clubs named equally often the one still
     * being heard keeps the place.
     *
     * Being heard is what makes a club a candidate at all, and a side is heard by its players as
     * well as its name ([lastHeardAt]). A side the commentary describes only through its squad
     * therefore stays in the running with a count of zero rather than falling out of the horizon.
     */
    private fun rankedTeams(at: Long): Set<SubtitleGlossary.Team> {
        val heard = lastHeardAt.filterValues { it >= at - TEAM_MENTION_WINDOW_MS }
        if (heard.isEmpty()) return emptySet()
        return heard.entries
            .sortedWith(
                compareByDescending<Map.Entry<SubtitleGlossary.Team, Long>> {
                    mentionCounts[it.key] ?: 0
                }
                    .thenByDescending { it.value }
                    .thenBy { it.key.ordinal },
            )
            .take(SubtitleGlossary.TEAM_FIXTURE_SIZE)
            .mapTo(mutableSetOf()) { it.key }
    }

    /** Drop the oldest words once the window is over budget, never splitting a word in half. */
    private fun trim() {
        if (window.length <= maxChars) return
        val space = window.indexOf(" ", window.length - maxChars)
        window.delete(0, if (space >= 0) space + 1 else window.length - maxChars)
    }

    /**
     * The scene [hits] (from [SubtitleGlossary.sceneHits]) says the channel is showing, or null.
     *
     * An incumbent holds while its terms are still in the window, and then for [holdChars] of newer
     * text that does not name them — a live match spends spells longer than one window describing play
     * through its players, and dropping to no scene there was the mid-match flicker
     * [SPORT_HOLD_MULTIPLIER] exists to remove. While the incumbent is held, a challenger still takes
     * the channel by the same rule as ever (its own threshold, and strictly more evidence than the
     * incumbent's — which is now zero during a lull, so a real change of sport lands promptly).
     */
    private fun pick(hits: Map<Sport, Int>): Sport? {
        val current = sport
        if (current != null) {
            val incumbentHits = hits[current] ?: 0
            val holds = incumbentHits > 0 || (absentChars[current] ?: 0) < holdChars
            if (holds) {
                // A channel is NOT married to one sport — a broadcaster shows a football match, then a
                // tennis one. Once the new sport clears its own threshold AND carries strictly more
                // evidence than the incumbent's fading terms, it takes the channel; otherwise the old
                // sport's list would keep biasing the new sport's audio, the very contamination the
                // per-sport lists exist to avoid. A tie keeps the incumbent, so the hand-over needs the
                // new sport to actually dominate rather than merely be mentioned.
                val challenger = hits
                    .filter { (s, count) -> s != current && count >= (enterHits[s] ?: Int.MAX_VALUE) }
                    .maxByOrNull { it.value }
                if (challenger == null || challenger.value <= incumbentHits) return current
                return challenger.key
            }
        }
        // No incumbent (or its hold is spent): a challenger has to prove itself; ties resolve by Sport
        // order (football first).
        return hits
            .filter { (s, count) -> count >= (enterHits[s] ?: Int.MAX_VALUE) }
            .maxByOrNull { it.value }
            ?.key
    }
}