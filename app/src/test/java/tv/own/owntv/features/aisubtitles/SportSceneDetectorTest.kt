package tv.own.owntv.features.aisubtitles

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Pins the gate that decides WHETHER a channel's decoder is biased at all. Ordinary television must
 * open no scene (and so carry no hotword list), a sport must prove itself before its list is sent,
 * and the scene must age out on its own once the commentary stops naming it — otherwise a news
 * bulletin is quietly decoded toward `Tottenham` and a football match toward `Wimbledon`.
 */
class SportSceneDetectorTest {

    @Test
    fun `ordinary television opens no scene and so gets no bias`() {
        val detector = SportSceneDetector()
        assertNull(detector.observe("welcome back to the show"))
        assertNull(detector.observe("tonight we discuss the budget"))
        // No scene means an empty list — the whole reason the gate exists.
        assertEquals("", SubtitleGlossary.recognitionHotwords(detector.sport))
    }

    @Test
    fun `football needs two of its terms before it opens`() {
        val detector = SportSceneDetector()
        // One passing mention is not proof: the channel stays unbiased.
        assertNull(detector.observe("that looked offside"))
        // A second football term is what opens it. The word "penalty" alone would NOT: it is
        // ordinary English, so only the pitch phrases ("free kick", "penalty spot") count.
        assertEquals(SubtitleGlossary.Sport.FOOTBALL, detector.observe("and that was a free kick"))
    }

    @Test
    fun `the bare word penalty does not open a football scene`() {
        val detector = SportSceneDetector()
        // "a penalty for late payment" is not football; the gate must not open on the word alone.
        assertNull(detector.observe("there is a penalty for late payment"))
        assertNull(detector.observe("the court upheld the penalty"))
    }

    @Test
    fun `a manager's or official's name alone does not open the scene`() {
        // Managers and match officials ride the decoder as BIAS (see SubtitleGlossary.FOOTBALL_OFFICIALS),
        // but a person's name is not proof of football: naming one on a chat show must not open the
        // gate and drag the channel toward the football vocabulary.
        assertNull(SportSceneDetector().observe("Mikel Arteta watches from the touchline"))
        assertNull(SportSceneDetector().observe("the referee today is Michael Oliver"))
    }

    @Test
    fun `bare ordinary football words cannot open the scene on their own`() {
        val detector = SportSceneDetector()
        // `goal`/`corner`/`half` are ordinary English ("your goal in life", "round the corner",
        // "second half of the show"), so together they must still score ZERO: they are the weak tier
        // (SubtitleGlossary.SCENE_WEAK_TERMS), counted only beside a strong football term. If this
        // ever opens, a talk show would be decoded toward the football vocabulary.
        assertNull(detector.observe("my goal in life"))
        assertNull(detector.observe("and the shop round the corner"))
        assertNull(detector.observe("the second half of the show is a foul mood"))
    }

    @Test
    fun `a bare football word counts once a strong term names the sport`() {
        val detector = SportSceneDetector()
        // One strong term is not yet proof...
        assertNull(detector.observe("that was a free kick"))
        // ...but a bare `corner` beside it IS the combination football is: strong + weak clears the
        // two-hit gate, so a spell of play called through the ordinary words no longer ages out.
        assertEquals(SubtitleGlossary.Sport.FOOTBALL, detector.observe("and he wins a corner"))
    }

    @Test
    fun `a set piece is not counted twice through the word inside it`() {
        val detector = SportSceneDetector()
        // `goal kick` contains the bare `goal`, but one set piece must stay ONE hit — otherwise the
        // strong term alone would clear the gate and a single mention would open the scene. The weak
        // pass blanks the strong matches first, so this stays closed until a second term arrives.
        assertNull(detector.observe("a goal kick"))
        assertEquals(SubtitleGlossary.Sport.FOOTBALL, detector.observe("the goalkeeper takes it"))
    }

    @Test
    fun `snooker opens on a single term`() {
        val detector = SportSceneDetector()
        // `snooker` is a rare word, so one hit is proof enough.
        assertEquals(SubtitleGlossary.Sport.SNOOKER, detector.observe("he needs the cue ball"))
    }

    @Test
    fun `an opened scene lingers through sentences that name no term`() {
        val detector = SportSceneDetector()
        assertEquals(SubtitleGlossary.Sport.FOOTBALL, detector.observe("offside and a free kick"))
        // Ordinary commentary inside the same match keeps the bias: the anchors are still in window.
        assertEquals(SubtitleGlossary.Sport.FOOTBALL, detector.observe("and the crowd is on its feet"))
    }

    @Test
    fun `a passing mention of another sport cannot flap the incumbent`() {
        val detector = SportSceneDetector()
        assertEquals(SubtitleGlossary.Sport.SNOOKER, detector.observe("that is a century break"))
        // A tennis turn of phrase heard during the snooker coverage does not switch the bias.
        assertEquals(SubtitleGlossary.Sport.SNOOKER, detector.observe("he served it up"))
    }

    @Test
    fun `a lone shared word cannot take the channel from the incumbent`() {
        val detector = SportSceneDetector()
        assertEquals(SubtitleGlossary.Sport.FOOTBALL, detector.observe("offside and a free kick"))
        // One tennis term is below tennis's own two-hit threshold, so football keeps the bias.
        assertEquals(SubtitleGlossary.Sport.FOOTBALL, detector.observe("he calls deuce"))
    }

    @Test
    fun `one channel that changes sport switches the bias`() {
        val detector = SportSceneDetector()
        // First a football match on this channel...
        assertEquals(SubtitleGlossary.Sport.FOOTBALL, detector.observe("offside and a free kick"))
        // ...then, later in the SAME session, tennis. Once the new sport carries far more evidence
        // than the football terms still in the window, the bias follows the broadcast — otherwise the
        // football list would keep biasing tennis audio, the contamination the split exists to avoid.
        assertEquals(
            SubtitleGlossary.Sport.TENNIS,
            detector.observe("deuce tiebreak break point grand slam wimbledon"),
        )
    }

    @Test
    fun `the scene ages out once its terms leave the window and the hold is spent`() {
        val detector = SportSceneDetector()
        assertEquals(SubtitleGlossary.Sport.SNOOKER, detector.observe("the cue ball is safe"))
        // Fill the bounded window with ordinary words until the anchor is trimmed away AND the further
        // hold budget is spent: the channel returns to no bias without any timer, which is what a
        // studio segment after the match needs. The window is a few minutes of commentary (~2400
        // chars) and the hold is a second such window (SPORT_HOLD_MULTIPLIER), and the hold only starts
        // counting once the anchor has left the window — so the fill has to cover BOTH, ~7200 chars.
        // 150 sentences of ~55 chars is ~8400, comfortably past it; the scene is still held after 120.
        repeat(150) { detector.observe("and the analyst keeps talking about the weather outside") }
        assertNull(detector.sport)
    }

    @Test
    fun `an open football scene survives a lull longer than the window itself`() {
        val detector = SportSceneDetector()
        assertEquals(SubtitleGlossary.Sport.FOOTBALL, detector.observe("offside and a free kick"))
        // ~2800 chars of play called through the players alone: the anchors above are evicted from the
        // window (2400 chars), but the lull is still inside the hold budget, so the scene must NOT drop
        // to empty mid-match — dropping it would rebuild the decoder's bias and stutter the subtitles.
        repeat(50) { detector.observe("and the analyst keeps talking about the weather outside") }
        assertEquals(SubtitleGlossary.Sport.FOOTBALL, detector.sport)
    }

    @Test
    fun `a different sport still takes the channel from a held incumbent`() {
        val detector = SportSceneDetector()
        assertEquals(SubtitleGlossary.Sport.FOOTBALL, detector.observe("offside and a free kick"))
        // A lull holds the football scene (above), but the hold must not block a real change of sport:
        // once tennis clears its own threshold with more evidence than the incumbent's zero hits, it
        // takes the channel — otherwise the football list would bias the tennis audio.
        repeat(50) { detector.observe("and the analyst keeps talking about the weather outside") }
        assertEquals(
            SubtitleGlossary.Sport.TENNIS,
            detector.observe("deuce tiebreak break point grand slam wimbledon"),
        )
    }

    @Test
    fun `a commentary's set pieces count as football evidence`() {
        val detector = SportSceneDetector()
        // One set piece is not proof...
        assertNull(detector.observe("a goal kick"))
        // ...but the keeper taking it is the second term, so the scene opens on play that names none
        // of the rarer anchors ("offside", "free kick") a lull can leave unsaid for minutes.
        assertEquals(SubtitleGlossary.Sport.FOOTBALL, detector.observe("the goalkeeper takes it"))
    }

    @Test
    fun `a ground a commentary names opens the gate with the play around it`() {
        val detector = SportSceneDetector()
        // The venue is one strong term, not yet the two the gate needs...
        assertNull(detector.observe("we are under way at the Bernabeu"))
        // ...but the ground beside a bare `keeper` is the combination football is, so a match the
        // commentary names only by its venue no longer ages back to empty between anchors.
        assertEquals(SubtitleGlossary.Sport.FOOTBALL, detector.observe("and the keeper saves it"))
    }

    @Test
    fun `a club is ranked as soon as the commentary names it, and the second side joins it`() {
        val detector = SportSceneDetector()
        assertEquals(SubtitleGlossary.Sport.FOOTBALL, detector.observe("Liverpool win a free kick, offside"))
        // The NAME alone ranks the club. The count is the evidence, and the scene is already football,
        // so nothing has to be confirmed by a surname the decoder had no bias toward yet.
        assertEquals(setOf(SubtitleGlossary.Team.LIVERPOOL), detector.teams)
        // The second side is ADDED rather than replacing it, so both squads ride the decoder.
        detector.observe("Man City answer through Foden")
        assertEquals(
            setOf(SubtitleGlossary.Team.LIVERPOOL, SubtitleGlossary.Team.MANCHESTER_CITY),
            detector.teams,
        )
        // ...and the set never grows past a fixture's worth, however many clubs a long window names.
        detector.observe("Arsenal with Saka in the round-up")
        assertEquals(SubtitleGlossary.TEAM_FIXTURE_SIZE, detector.teams.size)
    }

    @Test
    fun `an opposing player finds the second side before its club is named`() {
        val detector = SportSceneDetector()
        assertEquals(SubtitleGlossary.Sport.FOOTBALL, detector.observe("Liverpool win a free kick, offside"))
        assertEquals(setOf(SubtitleGlossary.Team.LIVERPOOL), detector.teams)
        // The commentary has named only Liverpool, but the next thing it says is an ARSENAL player.
        // The unique surname identifies that side on its own, so its squad joins the bias now rather
        // than minutes later when `Arsenal` is finally said.
        detector.observe("Saka carries it forward")
        assertEquals(
            setOf(SubtitleGlossary.Team.LIVERPOOL, SubtitleGlossary.Team.ARSENAL),
            detector.teams,
        )
    }

    @Test
    fun `an ordinary lower-case word that is a surname finds no side`() {
        val detector = SportSceneDetector()
        assertEquals(SubtitleGlossary.Sport.FOOTBALL, detector.observe("Liverpool win a free kick, offside"))
        // `saka` lower-case is the word the recogniser did NOT capitalise, so it is not evidence of a
        // name; the fixture stays at the one side it has.
        detector.observe("saka carries it forward")
        assertEquals(setOf(SubtitleGlossary.Team.LIVERPOOL), detector.teams)
    }

    @Test
    fun `the scope summary reports the state the club verdict hides`() {
        val detector = SportSceneDetector()
        // Football open, no club named yet: no evidence at all, and the honest verdict is idle.
        assertEquals(SubtitleGlossary.Sport.FOOTBALL, detector.observe("offside and a free kick"))
        assertTrue(detector.scopeSummary(), detector.scopeSummary().contains("names=none"))
        assertTrue(detector.scopeSummary(), detector.scopeSummary().endsWith("idle"))
        // Arsenal named once is ranked: the summary shows the count that did it, and the surnames the
        // ranked squad is already matching — the reading that says the squads reached the decoder.
        detector.observe("Arsenal with Saka")
        val summary = detector.scopeSummary()
        assertTrue(summary, summary.contains("names=arsenal 1"))
        assertTrue(summary, summary.contains("sur=arsenal 1(saka)"))
        assertTrue(summary, summary.endsWith("scoped"))
        assertEquals(setOf(SubtitleGlossary.Team.ARSENAL), detector.teams)
        // A channel with no scene has nothing to scope, and says so rather than reporting a window.
        assertEquals("off", SportSceneDetector().scopeSummary())
    }

    @Test
    fun `a non-football scene never scopes a squad`() {
        val detector = SportSceneDetector()
        assertEquals(SubtitleGlossary.Sport.TENNIS, detector.observe("deuce, break point"))
        assertTrue(detector.teams.isEmpty())
    }

    @Test
    fun `a fixture named by the short club names a commentary actually uses is scoped`() {
        val detector = SportSceneDetector()
        assertEquals(SubtitleGlossary.Sport.FOOTBALL, detector.observe("offside and a free kick"))
        // Commentary says "City" and "United", not the curated long forms. The count is what ranks
        // them, so neither needs one of its own squad's surnames beside the short name.
        detector.observe("City through Foden")
        detector.observe("United answer with Fernandes")
        assertEquals(
            setOf(SubtitleGlossary.Team.MANCHESTER_CITY, SubtitleGlossary.Team.MANCHESTER_UNITED),
            detector.teams,
        )
    }

    @Test
    fun `the scope is built from clubs named in different windows`() {
        val detector = SportSceneDetector()
        assertEquals(SubtitleGlossary.Sport.FOOTBALL, detector.observe("offside and a free kick"))
        // Liverpool is named first...
        detector.observe("Liverpool with Van Dijk")
        assertEquals(setOf(SubtitleGlossary.Team.LIVERPOOL), detector.teams)
        // ...and then its evidence is pushed out of the window by minutes of commentary before the
        // second side is ever named. The naming has to be REMEMBERED for the two sides to meet, which
        // is the whole reason the window alone is not the rule.
        repeat(60) { detector.observe("and the analyst keeps talking about the weather outside") }
        detector.observe("Man City with Foden, offside and a free kick")
        assertEquals(
            setOf(SubtitleGlossary.Team.LIVERPOOL, SubtitleGlossary.Team.MANCHESTER_CITY),
            detector.teams,
        )
    }

    @Test
    fun `a club name split across two slices stays with its own club`() {
        val detector = SportSceneDetector()
        assertEquals(SubtitleGlossary.Sport.FOOTBALL, detector.observe("offside and a free kick"))
        // The recogniser emits `Newcastle` first and the rest of the name on the next slice. Matched
        // slice by slice, the bare `United` names Manchester United; the join has to keep it with
        // Newcastle, which is the club the commentary actually named.
        detector.observe("Newcastle")
        detector.observe("United win a corner")
        assertEquals(setOf(SubtitleGlossary.Team.NEWCASTLE), detector.teams)
    }

    @Test
    fun `a generic half that tails an unknown club does not scope Manchester`() {
        val detector = SportSceneDetector()
        assertEquals(SubtitleGlossary.Sport.FOOTBALL, detector.observe("offside and a free kick"))
        // `Bristol City` is a club the table does not carry; its `City` half must not be read as
        // Manchester City, or the commentary would be scoped to a side that is not on.
        detector.observe("Bristol City win a corner")
        assertTrue(detector.scopeSummary(), detector.teams.isEmpty())
    }

    @Test
    fun `a club named once is not counted again as later slices re-match the tail`() {
        val detector = SportSceneDetector()
        detector.observe("offside and a free kick")
        detector.observe("Liverpool win a corner")
        // Each later slice re-matches the tail as well as its own words; the mention already counted
        // must not be counted once per slice, or the rank would follow the slice rate.
        detector.observe("and the crowd roars")
        detector.observe("Liverpool again")
        assertTrue(detector.scopeSummary(), detector.scopeSummary().contains("names=liverpool 2"))
    }

    @Test
    fun `a settled fixture is not rebuilt for a club that only briefly out-counts it`() {
        var clock = 0L
        val detector = SportSceneDetector(now = { clock })
        detector.observe("offside and a free kick")
        detector.observe("Liverpool with Van Dijk")
        detector.observe("Man City with Foden")
        assertEquals(
            setOf(SubtitleGlossary.Team.LIVERPOOL, SubtitleGlossary.Team.MANCHESTER_CITY),
            detector.teams,
        )
        // A third club out-counts both inside the hold. Swapping the fixture out would rebuild the
        // decoder's bias mid-match, so the two sides stand.
        clock += 5_000
        repeat(5) { detector.observe("Arsenal with Saka") }
        assertEquals(
            setOf(SubtitleGlossary.Team.LIVERPOOL, SubtitleGlossary.Team.MANCHESTER_CITY),
            detector.teams,
        )
        // Once the hold has passed the change is allowed, so a real change of fixture is not blocked.
        clock += SCOPE_DWELL_MS
        detector.observe("Arsenal with Saka")
        assertTrue(detector.teams.toString(), detector.teams.contains(SubtitleGlossary.Team.ARSENAL))
    }

    @Test
    fun `a club drops out once its mentions age past the horizon`() {
        var clock = 0L
        val detector = SportSceneDetector(now = { clock })
        assertEquals(SubtitleGlossary.Sport.FOOTBALL, detector.observe("offside and a free kick"))
        detector.observe("Liverpool with Van Dijk")
        assertEquals(setOf(SubtitleGlossary.Team.LIVERPOOL), detector.teams)
        // Minutes later the club has not been named again: it stops counting and the scope falls back
        // to the unscoped baseline rather than biasing the rest of the broadcast.
        clock += 601_000
        detector.observe("still offside, and another free kick")
        assertTrue(detector.scopeSummary(), detector.teams.isEmpty())
    }

    @Test
    fun `a side the commentary describes only through its players is not retired`() {
        var clock = 0L
        val detector = SportSceneDetector(now = { clock })
        detector.observe("offside and a free kick")
        detector.observe("Liverpool with Van Dijk")
        detector.observe("Man City with Foden")
        assertEquals(
            setOf(SubtitleGlossary.Team.LIVERPOOL, SubtitleGlossary.Team.MANCHESTER_CITY),
            detector.teams,
        )
        // Play runs for minutes with the commentary calling the players and never the club — naming a
        // club is the exception in a commentary, not the rule. On names alone the horizon would retire
        // Liverpool minutes into exactly the spell that most needs its squad biased.
        clock += 9 * 60_000
        detector.observe("Van Dijk heads it clear")
        clock += 9 * 60_000
        detector.observe("Van Dijk again")
        assertTrue(detector.teams.toString(), detector.teams.contains(SubtitleGlossary.Team.LIVERPOOL))
    }

    @Test
    fun `a player's name alone never puts his club on the pitch`() {
        val detector = SportSceneDetector()
        detector.observe("offside and a free kick")
        // Nobody has named a club. A surname says which player was heard, not which two clubs are
        // playing, so the scene stays on the unscoped baseline until a club is actually named.
        detector.observe("Van Dijk heads it clear")
        assertTrue(detector.teams.toString(), detector.teams.isEmpty())
    }

    @Test
    fun `the summary marks the scoped sides, so a side held only by its players reads as covered`() {
        var clock = 0L
        val detector = SportSceneDetector(now = { clock })
        detector.observe("offside and a free kick")
        detector.observe("Liverpool with Van Dijk")
        assertTrue(detector.scopeSummary(), detector.scopeSummary().contains("liverpool 1*"))
        // The club's own name then falls out of the horizon, while Van Dijk keeps the side on the
        // pitch. On the counts alone it would have vanished from the line, so the mark is what says
        // the squad is still riding the decoder: a zero beside it means held by its players, not
        // dropped.
        clock += 11 * 60_000
        detector.observe("Van Dijk wins it back, still offside")
        val summary = detector.scopeSummary()
        assertTrue(summary, summary.contains("liverpool 0*"))
        assertTrue(summary, summary.endsWith("scoped"))
        assertEquals(setOf(SubtitleGlossary.Team.LIVERPOOL), detector.teams)
    }

    @Test
    fun `the summary names the fixture the dwell is holding back`() {
        var clock = 0L
        val detector = SportSceneDetector(now = { clock })
        detector.observe("offside and a free kick")
        detector.observe("Liverpool with Van Dijk")
        assertTrue(detector.scopeSummary(), "pend=" !in detector.scopeSummary())
        // A round-up then names two clubs more recently than Liverpool, so the rank has moved off the
        // side on the pitch — adding a club would merely complete the fixture and land at once, but
        // dropping one re-creates the decode stream, so it waits out the dwell. The line has to show
        // that the change is being held, or the stale squads on the decoder look like the current rank.
        clock += 5_000
        detector.observe("Arsenal with Saka and Man City with Foden")
        assertTrue(detector.scopeSummary(), detector.scopeSummary().contains("pend="))
        assertEquals(setOf(SubtitleGlossary.Team.LIVERPOOL), detector.teams)
    }
}