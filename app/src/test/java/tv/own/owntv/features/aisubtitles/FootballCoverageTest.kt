package tv.own.owntv.features.aisubtitles

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The coverage matrix: the situations the review walked through, enumerated and pinned in one place.
 *
 * [SportSceneDetectorTest] narrates the gate and [SubtitleGlossaryTest] narrates the names table,
 * each following one mechanism deep. This suite is the breadth checklist that spans both — one row
 * per situation a broadcast actually threw at the pipeline, paired with the verdict it must get. A
 * change that widens the gate or loosens a repair has to break a row here, and because a row names
 * its situation the failure reads as "an ordinary talk show now opens a football scene" rather than
 * "some helper changed".
 */
class FootballCoverageTest {

    private val arsenal = setOf(SubtitleGlossary.Team.ARSENAL)
    private val liverpool = setOf(SubtitleGlossary.Team.LIVERPOOL)
    private val manchester = setOf(
        SubtitleGlossary.Team.MANCHESTER_CITY,
        SubtitleGlossary.Team.MANCHESTER_UNITED,
    )
    private val brentfordLeeds = setOf(SubtitleGlossary.Team.BRENTFORD, SubtitleGlossary.Team.LEEDS)
    private val chelseaPalace = setOf(SubtitleGlossary.Team.CHELSEA, SubtitleGlossary.Team.CRYSTAL_PALACE)

    // --- 1. the gate: what may and may not open a football bias ---------------------------------

    /** One situation: the slices a channel says, and the sport its decoder must end biased toward. */
    private class SceneRow(
        val situation: String,
        val says: List<String>,
        val sport: SubtitleGlossary.Sport?,
    )

    private val sceneRows = listOf(
        SceneRow("an ordinary talk show", listOf("welcome back to the show"), null),
        SceneRow("a weak word in \"your goal in life\"", listOf("my goal in life"), null),
        SceneRow("a weak word in \"round the corner\"", listOf("and the shop round the corner"), null),
        SceneRow(
            "a weak word in \"second half of the show\"",
            listOf("the second half of the show is a foul mood"),
            null,
        ),
        SceneRow(
            "a bare \"penalty\" in its legal sense",
            listOf("there is a penalty for late payment"),
            null,
        ),
        SceneRow(
            "a manager's name with no play",
            listOf("Mikel Arteta watches from the touchline"),
            null,
        ),
        SceneRow("an official's name with no play", listOf("the referee today is Michael Oliver"), null),
        SceneRow("one term, not yet proof", listOf("that looked offside"), null),
        SceneRow(
            "a second term opens it",
            listOf("that looked offside", "and that was a free kick"),
            SubtitleGlossary.Sport.FOOTBALL,
        ),
        SceneRow(
            "a set piece beside a bare corner",
            listOf("that was a free kick", "and he wins a corner"),
            SubtitleGlossary.Sport.FOOTBALL,
        ),
        SceneRow(
            "a set piece beside the keeper",
            listOf("a goal kick", "the goalkeeper takes it"),
            SubtitleGlossary.Sport.FOOTBALL,
        ),
        SceneRow(
            "a venue beside the keeper",
            listOf("we are under way at the Bernabeu", "and the keeper saves it"),
            SubtitleGlossary.Sport.FOOTBALL,
        ),
        SceneRow(
            "a snooker term alone",
            listOf("the cue ball is safe"),
            SubtitleGlossary.Sport.SNOOKER,
        ),
    )

    @Test
    fun `every scene situation reads as the matrix says`() {
        for (row in sceneRows) {
            val detector = SportSceneDetector()
            row.says.forEach { detector.observe(it) }
            assertEquals("scene: ${row.situation}", row.sport, detector.sport)
        }
    }

    // --- 2. a mid-match lull must never drop the scene to empty ----------------------------------

    @Test
    fun `an open scene outlasts a lull that names no term`() {
        val detector = SportSceneDetector()
        detector.observe("offside and a free kick")
        // The anchors leave the ~2400-char window, but the hold keeps the bias: the football scene
        // must NOT fall back to empty mid-match, the stutter the whole review set out to remove.
        repeat(50) { detector.observe("and the analyst keeps talking about the weather outside") }
        assertEquals(SubtitleGlossary.Sport.FOOTBALL, detector.sport)
    }

    @Test
    fun `a lull holds the scene but not a real change of sport`() {
        val detector = SportSceneDetector()
        detector.observe("offside and a free kick")
        repeat(50) { detector.observe("and the analyst keeps talking about the weather outside") }
        // The hold must not freeze the bias either: once another sport clears its own threshold it
        // takes the channel, or the football list would keep biasing tennis audio.
        assertEquals(
            SubtitleGlossary.Sport.TENNIS,
            detector.observe("deuce tiebreak break point grand slam wimbledon"),
        )
    }

    // --- 3. names: what a fixture may repair, and what it must leave alone ------------------------

    private class NameRow(
        val situation: String,
        val teams: Set<SubtitleGlossary.Team>,
        val heard: String,
        val expected: String,
    )

    private val nameRows = listOf(
        NameRow("a bare surname one edit out", arsenal, "Whita clears it", "White clears it"),
        NameRow("an ordinary word one edit out is vetoed", arsenal, "While we wait", "While we wait"),
        NameRow("a bare surname below the length floor", arsenal, "Sakx carries it", "Sakx carries it"),
        NameRow(
            "a full name one slip out",
            arsenal,
            "Bukayo Sakx carries it",
            "Bukayo Saka carries it",
        ),
        NameRow(
            "a given name pins a two-edit surname",
            arsenal,
            "Bukayo Sakho carries it",
            "Bukayo Saka carries it",
        ),
        NameRow(
            "a surname pins a two-edit given name",
            liverpool,
            "Virktil van Dijk heads it clear",
            "Virgil van Dijk heads it clear",
        ),
        NameRow(
            "a compound surname inside a full name",
            liverpool,
            "Virgil van Dijx heads it clear",
            "Virgil van Dijk heads it clear",
        ),
        NameRow(
            "a half that fits two players pins neither",
            arsenal,
            "Martin Odegoord plays it",
            "Martin Odegoord plays it",
        ),
        NameRow(
            "a near-miss two scoped surnames explain",
            manchester,
            "Fernande plays it wide",
            "Fernande plays it wide",
        ),
        NameRow("ordinary speech", arsenal, "the corner shop is open", "the corner shop is open"),
        NameRow("no fixture scoped", emptySet(), "Kovacik turns away", "Kovacik turns away"),
    )

    @Test
    fun `every name situation is repaired or left exactly as the matrix says`() {
        for (row in nameRows) {
            assertEquals(
                "name: ${row.situation}",
                row.expected,
                SubtitleGlossary.correctNames(row.heard, row.teams),
            )
        }
    }

    // --- 4. a shared surname resolved from the club named in the same sentence --------------------

    private class ResolveRow(
        val situation: String,
        val teams: Set<SubtitleGlossary.Team>,
        val heard: String,
        val expected: String,
    )

    private val resolveRows = listOf(
        ResolveRow(
            "the club named picks the man",
            brentfordLeeds,
            "Wilson drives forward for Brentford",
            "Callum Wilson drives forward for Brentford",
        ),
        ResolveRow(
            "the other club picks the other man",
            brentfordLeeds,
            "Wilson drives forward for Leeds",
            "Harry Wilson drives forward for Leeds",
        ),
        ResolveRow(
            "a second shared pair is resolved too",
            chelseaPalace,
            "Henderson starts for Chelsea",
            "Jordan Henderson starts for Chelsea",
        ),
        ResolveRow(
            "both clubs named, nothing to choose",
            brentfordLeeds,
            "Brentford and Leeds, Wilson drives forward",
            "Brentford and Leeds, Wilson drives forward",
        ),
        ResolveRow(
            "no club named, no evidence",
            brentfordLeeds,
            "Wilson drives forward",
            "Wilson drives forward",
        ),
    )

    @Test
    fun `every in-sentence situation resolves as the matrix says`() {
        for (row in resolveRows) {
            assertEquals(
                "resolve: ${row.situation}",
                row.expected,
                SubtitleGlossary.resolveSurnames(row.heard, row.teams),
            )
        }
    }

    // --- 5. the fixture read from the whole broadcast, not one window ------------------------------

    /** One situation: the slices a channel says, and the two clubs they must scope the decoder to. */
    private class ScopeRow(
        val situation: String,
        val feed: (SportSceneDetector) -> Unit,
        val teams: Set<SubtitleGlossary.Team>,
    )

    private val scopeRows = listOf(
        ScopeRow(
            "one club, then its opponent's player",
            { detector ->
                detector.observe("Liverpool win a free kick, offside")
                detector.observe("Saka carries it forward")
            },
            setOf(SubtitleGlossary.Team.LIVERPOOL, SubtitleGlossary.Team.ARSENAL),
        ),
        ScopeRow(
            "a second side named windows later",
            { detector ->
                detector.observe("offside and a free kick")
                detector.observe("Liverpool with Van Dijk")
                // Minutes of commentary that name nobody push the first club out of the window; the
                // side has to be REMEMBERED for the two to meet, which is why the window is not the
                // rule. Without the memory the fixture would still be Liverpool-alone here.
                repeat(60) { detector.observe("and the analyst keeps talking about the weather outside") }
                detector.observe("Man City with Foden, offside and a free kick")
            },
            setOf(SubtitleGlossary.Team.LIVERPOOL, SubtitleGlossary.Team.MANCHESTER_CITY),
        ),
        ScopeRow(
            "a club split across two slices keeps its own tail",
            { detector ->
                detector.observe("offside and a free kick")
                detector.observe("Newcastle")
                detector.observe("United win a corner")
            },
            setOf(SubtitleGlossary.Team.NEWCASTLE),
        ),
        ScopeRow(
            "a club the table lacks lends no half",
            { detector ->
                detector.observe("offside and a free kick")
                detector.observe("Bristol City win a corner")
            },
            emptySet(),
        ),
        ScopeRow(
            "a player's name alone names no club",
            { detector ->
                detector.observe("offside and a free kick")
                detector.observe("Van Dijk heads it clear")
            },
            emptySet(),
        ),
        ScopeRow(
            "the short club names a commentary says",
            { detector ->
                detector.observe("offside and a free kick")
                detector.observe("City through Foden")
                detector.observe("United answer with Fernandes")
            },
            setOf(SubtitleGlossary.Team.MANCHESTER_CITY, SubtitleGlossary.Team.MANCHESTER_UNITED),
        ),
    )

    @Test
    fun `every fixture situation scopes exactly the matrix's clubs`() {
        for (row in scopeRows) {
            val detector = SportSceneDetector()
            row.feed(detector)
            assertEquals("scope: ${row.situation}", row.teams, detector.teams)
        }
    }

    @Test
    fun `the round-up cannot grow the fixture past a fixture's worth`() {
        val detector = SportSceneDetector()
        detector.observe("Liverpool win a free kick, offside")
        detector.observe("Man City answer through Foden")
        // A third club is the round-up after the match, not the fixture being commentated: the cap is
        // what keeps the third squad — and its ~16 players — off every decode of the first two.
        detector.observe("Arsenal with Saka in the round-up")
        assertEquals(SubtitleGlossary.TEAM_FIXTURE_SIZE, detector.teams.size)
    }

    // --- 6. every club's own entries ride its scoped list, checked club by club --------------------

    @Test
    fun `every club's squad, manager and ground ride its own scoped list`() {
        // The coverage the whole hotword table exists for, asserted per club rather than on a sample:
        // scoping to ONE club puts each of its squad (full name and spoken surname), its manager
        // (both forms) and each of its venues on the decoder list. The three tables name the same
        // clubs, so none can silently have a squad but no manager, or a club but no ground.
        assertEquals(
            "a club has a squad but no manager",
            SubtitleGlossary.TEAM_PLAYERS.keys,
            SubtitleGlossary.FOOTBALL_MANAGERS.keys,
        )
        assertEquals(
            "a club has a squad but no ground",
            SubtitleGlossary.TEAM_PLAYERS.keys,
            SubtitleGlossary.TEAM_GROUNDS.keys,
        )
        for ((team, players) in SubtitleGlossary.TEAM_PLAYERS) {
            assertTrue("$team carries no squad", players.isNotEmpty())
            val entries = SubtitleGlossary.recognitionHotwords(SubtitleGlossary.Sport.FOOTBALL, setOf(team))
                .split("/")
                .mapTo(HashSet()) { it.substringBefore(" :") }
            for (player in players) {
                assertTrue("$player ($team) is not on the scoped list", player in entries)
                val surname = SubtitleGlossary.spokenSurname(player)
                assertTrue("$surname ($player, $team) is not on the scoped list", surname in entries)
            }
            val manager = SubtitleGlossary.FOOTBALL_MANAGERS.getValue(team)
            assertTrue("$manager ($team) is not on the scoped list", manager in entries)
            val managerSurname = SubtitleGlossary.spokenSurname(manager)
            assertTrue("$managerSurname ($team) is not on the scoped list", managerSurname in entries)
            for (ground in SubtitleGlossary.TEAM_GROUNDS.getValue(team)) {
                assertTrue("$ground ($team) is not on the scoped list", ground in entries)
            }
        }
    }
}