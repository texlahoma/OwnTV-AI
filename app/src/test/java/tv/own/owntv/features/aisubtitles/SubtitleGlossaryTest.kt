package tv.own.owntv.features.aisubtitles

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Pins the names-and-terms table's contract. Two things have to hold at once, and they pull in
 * opposite directions: inside a sports broadcast the ambiguous words must be resolved the way the
 * Chinese broadcast does it, and everywhere else television must be left exactly as it was. The gate
 * — an [SubtitleGlossary] anchor in the same window — is what keeps those two from colliding, so
 * every case below is about whether the gate fired, not about the wording of a prompt.
 */
class SubtitleGlossaryTest {

    // --- the source-side rewrite ---------------------------------------------------------------

    @Test
    fun `an unambiguous name is expanded on its own`() {
        assertEquals("Mohamed Salah scores", SubtitleGlossary.disambiguate("Salah scores"))
        assertEquals("Ronnie O'Sullivan leads", SubtitleGlossary.disambiguate("O'Sullivan leads"))
        assertEquals(
            "Kylian Mbappé finishes it",
            SubtitleGlossary.disambiguate("Mbappé finishes it"),
        )
    }

    @Test
    fun `a name already spoken in full is not expanded a second time`() {
        // The expansion has to eat a given name the window already carries, or the full form is
        // doubled and the corrupted text goes to the translator's text, its context and the Google
        // fallback alike.
        assertEquals("Mohamed Salah scores", SubtitleGlossary.disambiguate("Mohamed Salah scores"))
        assertEquals("Erling Haaland scores", SubtitleGlossary.disambiguate("Erling Haaland scores"))
        assertEquals("Judd Trump takes a break", SubtitleGlossary.disambiguate("Judd Trump takes a break"))
        assertEquals("Ronnie O'Sullivan leads", SubtitleGlossary.disambiguate("Ronnie O'Sullivan leads"))
        // The name is expanded whether the given name is already there or not, so the same window
        // cannot produce two different spellings of the same man.
        assertEquals("Mohamed Salah scores", SubtitleGlossary.disambiguate("Salah scores"))
        // `correct` repairs the slip first, leaving the given name in place; the expansion then has
        // to recognise it rather than add its own.
        assertEquals(
            "Erling Haaland scores",
            SubtitleGlossary.disambiguate(SubtitleGlossary.correct("Erling Haland scores")),
        )
    }

    @Test
    fun `a name that reads as an ordinary word waits for its sport to be anchored`() {
        // No anchor: "Trump" alone is left as spoken, so a news broadcast is not "corrected" to a
        // snooker player and "Sinner" is not turned into a name.
        assertEquals("Trump takes a break", SubtitleGlossary.disambiguate("Trump takes a break"))
        assertEquals("Sinner serves", SubtitleGlossary.disambiguate("Sinner serves"))

        // The same words with an anchor from the same sentence ARE the sports reading of them.
        assertEquals(
            "Judd Trump wins the frame",
            SubtitleGlossary.disambiguate("Trump wins the frame", anchoredBy = "O'Sullivan leads"),
        )
        assertEquals(
            "Jannik Sinner serves",
            SubtitleGlossary.disambiguate("Sinner serves", anchoredBy = "Djokovic returns"),
        )
    }

    @Test
    fun `the anchor may sit in the earlier words of the same sentence`() {
        // The fragment on its own carries no proof, so nothing is rewritten; the sentence it
        // continues does, and the gate has to look at the same window the translator is given.
        assertEquals("Trump to pot the red", SubtitleGlossary.disambiguate("Trump to pot the red"))

        val window = SubtitleGlossary.window(context = "O'Sullivan leads", text = "Trump to pot the red")
        assertEquals("Judd Trump to pot the red", SubtitleGlossary.disambiguate("Trump to pot the red", window))
    }

    @Test
    fun `matching is whole-word, so a name never fires inside a longer word`() {
        assertEquals("Salahuddin scores", SubtitleGlossary.disambiguate("Salahuddin scores"))
        assertEquals("Alcarazian defence", SubtitleGlossary.disambiguate("Alcarazian defence"))
    }

    // --- the misheard-name repair --------------------------------------------------------------

    @Test
    fun `a gated slip is repaired only once its sport is anchored`() {
        // The same slip is the player with a snooker anchor in the window...
        assertEquals(
            "Trump wins the frame",
            SubtitleGlossary.correct("Trunk wins the frame", anchoredBy = "O'Sullivan leads"),
        )
        // ...and an ordinary word without one, so television keeps the word it actually heard.
        assertEquals("A trunk in the attic", SubtitleGlossary.correct("A trunk in the attic"))
        assertEquals(
            "A trunk in the attic",
            SubtitleGlossary.correct("A trunk in the attic", anchoredBy = "the news at ten"),
        )
    }

    @Test
    fun `an anchor entry's slip is repaired on its own`() {
        // These slips are non-words, so they cannot mean anything else and need no anchor.
        assertEquals("higgins leads", SubtitleGlossary.correct("higgens leads"))
        assertEquals("Selby pots the red", SubtitleGlossary.correct("Selbi pots the red"))
        assertEquals("Djokovic returns", SubtitleGlossary.correct("Jokovic returns"))
    }

    @Test
    fun `the repair keeps the slip's own capitalisation`() {
        val anchoredBy = "O'Sullivan leads"
        // Sentence-initial: the repaired name is capitalised the way the slip was.
        assertEquals("Trump leads", SubtitleGlossary.correct("Trunk leads", anchoredBy))
        // Mid-sentence: it stays lower-case rather than being forced to title case.
        assertEquals("to trump the red", SubtitleGlossary.correct("to trunk the red", anchoredBy))
    }

    @Test
    fun `ordinary speech is left exactly as recognised`() {
        assertEquals("the game is now tied", SubtitleGlossary.correct("the game is now tied"))
        assertEquals("welcome back to the show", SubtitleGlossary.correct("welcome back to the show"))
    }

    // --- the decoder hotwords ------------------------------------------------------------------

    @Test
    fun `with no scene recognised the decoder carries no bias at all`() {
        // The reason the lists are per-sport: a channel whose scene the detector has not opened is
        // decoded with an EMPTY list, so ordinary television is never dragged toward a sport's words.
        assertEquals("", SubtitleGlossary.recognitionHotwords(null))
    }

    @Test
    fun `each scene is biased toward its own vocabulary and no other`() {
        val football = SubtitleGlossary.recognitionHotwords(SubtitleGlossary.Sport.FOOTBALL)
        // One name per '/', the separator the sherpa-onnx hotword JNI splits on.
        assertTrue(football, football.contains("Tottenham"))
        assertTrue(football, football.contains("Real Madrid"))
        assertTrue(football, football.contains("VAR"))
        assertTrue(football, football.contains("offside"))
        // The unscoped list carries the club names and the terminology — the words the scene is
        // RECOGNISED from — and deliberately NO player name. A player entry is a single-token hotword
        // that scores on every frame the decoder can reach it, so ~300 of them turn stadium commentary
        // into a string of surnames, the anchors stop appearing, and the scene that opened on them ages
        // straight back out. The squads enter only the scoped list, as the two proved sides' surnames.
        assertFalse(football, football.contains("Saka"))
        assertFalse(football, football.contains("Szoboszlai"))
        // A tennis or snooker name riding along would drag a football channel's decode the wrong way.
        assertFalse(football, football.contains("Djokovic"))
        assertFalse(football, football.contains("O'Sullivan"))

        val tennis = SubtitleGlossary.recognitionHotwords(SubtitleGlossary.Sport.TENNIS)
        assertTrue(tennis, tennis.contains("Novak Djokovic"))
        assertTrue(tennis, tennis.contains("deuce"))
        assertTrue(tennis, tennis.contains("Wimbledon"))
        assertFalse(tennis, tennis.contains("Tottenham"))

        val snooker = SubtitleGlossary.recognitionHotwords(SubtitleGlossary.Sport.SNOOKER)
        assertTrue(snooker, snooker.contains("Judd Trump"))
        assertTrue(snooker, snooker.contains("Ronnie O'Sullivan"))
        assertTrue(snooker, snooker.contains("cue ball"))
        assertFalse(snooker, snooker.contains("Tottenham"))

        // Every list stays well-formed for the '/' split the JNI walks.
        listOf(football, tennis, snooker).forEach { list ->
            assertTrue(list, list.split("/").all { term -> term.isNotBlank() })
        }
    }

    @Test
    fun `a proved squad rides the decoder as both its spoken surnames and its full names`() {
        val scoped = SubtitleGlossary.recognitionHotwords(
            SubtitleGlossary.Sport.FOOTBALL,
            setOf(SubtitleGlossary.Team.ARSENAL, SubtitleGlossary.Team.BOURNEMOUTH),
        )
        val entries = scoped.split("/")
        // The context graph matches a hotword from its FIRST token, so the surname has to be an entry
        // of its own: as a mere suffix of "Bukayo Saka" a bare `Saka` received none of the bias, and a
        // list that carries names only at full length corrects a player only when the commentary says
        // the whole name — which is the minority of mentions. Players ride the middle tier, so each
        // entry carries the ` :3.0` score suffix the parser reads (see HOTWORD_WEIGHT_PLAYER).
        assertTrue("surname must be its own entry", entries.contains("Saka :3.0"))
        assertTrue("surname must be its own entry", entries.contains("Odegaard :3.0"))
        // The full name rides beside the surname for the mirror-image gap: a surname entry matches
        // from `Saka` and so carries no bias toward `Bukayo`, whose own tokens the recogniser
        // flattens hardest — and a mention of the whole name is exactly where it flattens hardest.
        // Only a NAMED squad's full names are listed, so this stays a couple of squads, not ~300.
        assertTrue("full name must be its own entry", entries.contains("Bukayo Saka :3.0"))
        assertTrue("full name must be its own entry", entries.contains("Martin Odegaard :3.0"))
        // No name is carried unscoped (see above): the squads enter only through the scoped list.
        assertFalse(SubtitleGlossary.recognitionHotwords(SubtitleGlossary.Sport.FOOTBALL).contains("Bukayo Saka"))
        // A mononym is its own surname and must be listed exactly once for it.
        assertEquals(1, entries.count { it == "Evanilson :3.0" })
    }

    // --- the prompt glossary -------------------------------------------------------------------

    @Test
    fun `only the entries present are pinned, and only for a Chinese target`() {
        val cases = SubtitleGlossary.promptSection("Djokovic serving at deuce", "zh")
        assertTrue(cases, cases.contains("Novak Djokovic → 德约科维奇"))
        assertTrue(cases, cases.contains("deuce → 平分"))
        // Bounded by the matches, not by the table: no snooker and no football entries ride along.
        assertFalse(cases, cases.contains("特鲁姆普"))
        assertFalse(cases, cases.contains("越位"))

        // Every target in the table is a Chinese rendering, so a non-Chinese target gets nothing.
        assertEquals("", SubtitleGlossary.promptSection("Djokovic serving at deuce", "en"))
    }

    @Test
    fun `a gated term is pinned only once its sport is anchored`() {
        val gated = SubtitleGlossary.promptSection("O'Sullivan leads Trump", "zh")
        assertTrue(gated, gated.contains("Ronnie O'Sullivan → 奥沙利文"))
        assertTrue(gated, gated.contains("Judd Trump → 特鲁姆普"))

        // "frame" is an ordinary word; without a snooker anchor the prompt stays clean and a film
        // subtitle about frames is not told to caption 局.
        assertEquals("", SubtitleGlossary.promptSection("Trump frames his answer", "zh"))
        assertTrue(
            SubtitleGlossary.promptSection("O'Sullivan needs three frames", "zh").contains("frames → 局"),
        )
    }

    @Test
    fun `a bare scoreline proves football through the numeral beside nil`() {
        // No anchor word anywhere in the fragment, but the numeral is proof enough for football, so
        // the gate opens on the scoreline and the fragment is told to render nil as 零.
        assertTrue(
            SubtitleGlossary.promptSection("It's one nil to Liverpool", "zh").contains("nil → 零"),
        )
        assertTrue(SubtitleGlossary.promptSection("they won two nil", "zh").contains("nil → 零"))
        assertTrue(
            SubtitleGlossary.promptSection("three nil at half time", "zh").contains("nil → 零"),
        )
        // A digit counts too, so a scoreboard-style "3 nil" reads the same as the spelled number.
        assertTrue(SubtitleGlossary.promptSection("a 3 nil win", "zh").contains("nil → 零"))
        // A bare "nil nil" has no numeral; the doubled word is proof enough on its own.
        assertTrue(SubtitleGlossary.promptSection("it is nil nil", "zh").contains("nil → 零"))

        // Without a numeral beside it, "nil" is still an ordinary word and nothing is pinned.
        assertEquals("", SubtitleGlossary.promptSection("nil by mouth", "zh"))
    }

    // --- the misheard scoreline ---------------------------------------------------------------

    @Test
    fun `an evidenced scoreline is repaired to its numeral and its word`() {
        // The slips measured against the shipped model, inside a commentary that names a club.
        assertEquals(
            "Tottenham have gone two nil ahead",
            SubtitleGlossary.correct("Tottenham have gone too nil ahead"),
        )
        assertEquals("Chelsea one nil Everton", SubtitleGlossary.correct("Chelsea won nil Everton"))
        assertEquals("Real Madrid lead two nil", SubtitleGlossary.correct("Real Madrid lead to nil"))
        // "nil nil" carries no numeral; two nil-forms together are proof enough on their own.
        assertEquals(
            "it is nil nil at half time",
            SubtitleGlossary.correct("it is Nill Nill at half time"),
        )

        // The repair reaches the glossary too, so the Chinese still gets nil → 零.
        val repaired = SubtitleGlossary.correct("Tottenham have gone too nil ahead")
        assertTrue(SubtitleGlossary.promptSection(repaired, "zh").contains("nil → 零"))
    }

    @Test
    fun `a scoreline shape with no football evidence is left as recognised`() {
        // "to nil" is not a score without football around it, and neither is a bare name or verb.
        assertEquals("reduce the risk to nil", SubtitleGlossary.correct("reduce the risk to nil"))
        assertEquals(
            "Neil Armstrong took the step",
            SubtitleGlossary.correct("Neil Armstrong took the step"),
        )
        assertEquals("Chelsea won the game", SubtitleGlossary.correct("Chelsea won the game"))
    }

    @Test
    fun `ordinary speech adds no glossary at all`() {
        assertEquals("", SubtitleGlossary.promptSection("the game is now tied", "zh"))
        assertEquals("", SubtitleGlossary.promptSection("welcome back to the show", "zh"))
        assertEquals("", SubtitleGlossary.promptSection("I love this film", "zh"))
        assertEquals("", SubtitleGlossary.promptSection("", "zh"))
    }

    @Test
    fun `a term is not detected inside another word`() {
        // "ace" sits inside "place"; the boundary check is what stops a tennis term being pinned out
        // of an ordinary English word.
        assertFalse(
            SubtitleGlossary.promptSection("Djokovic earns a place in the final", "zh").contains("→ ACE"),
        )
        assertTrue(SubtitleGlossary.promptSection("Djokovic hits his first ace", "zh").contains("ace → ACE"))
    }

    // --- the club scope ------------------------------------------------------------------------

    @Test
    fun `a club is ranked by how often the commentary names it, not by a surname beside the name`() {
        // The short name alone is a town, a building or a plural noun, but the COUNT is what retires
        // the ambiguity: within a football scene the two sides being commentated out-count anything
        // mentioned in passing, so ranking a club needs no surname to confirm it.
        assertEquals(
            mapOf(SubtitleGlossary.Team.BRIGHTON to 1),
            SubtitleGlossary.teamMentionHits("Brighton will sign him, alongside Mitoma"),
        )
        // Counted, not merely seen: the repeats are what make the rank follow the commentary.
        assertEquals(
            mapOf(SubtitleGlossary.Team.BRIGHTON to 3),
            SubtitleGlossary.teamMentionHits("Brighton, Brighton and Brighton again"),
        )
    }

    @Test
    fun `a nickname the table admits counts for its club`() {
        // A nickname is admitted on the same terms as a short form — the club's own, said by a
        // commentary, and not another programme's own subject. `Gunners` shares its sentence with the
        // full name and both count for Arsenal.
        assertEquals(
            mapOf(SubtitleGlossary.Team.ARSENAL to 2),
            SubtitleGlossary.teamMentionHits("the Gunners break, and Arsenal score"),
        )
        assertEquals(
            mapOf(SubtitleGlossary.Team.NEWCASTLE to 1),
            SubtitleGlossary.teamMentionHits("the Magpies press high"),
        )
        assertEquals(
            mapOf(SubtitleGlossary.Team.MANCHESTER_UNITED to 1),
            SubtitleGlossary.teamMentionHits("a Red Devils counter"),
        )
        // The withheld ones are the ambiguous ones: `Blues` is Chelsea's AND Ipswich's, so crediting
        // it to either would be a wrong club rather than noise.
        assertTrue(
            SubtitleGlossary.teamMentionHits("the Blues sit deep").toString(),
            SubtitleGlossary.teamMentionHits("the Blues sit deep").isEmpty(),
        )
    }

    @Test
    fun `a spoken surname keeps its particle, so the form the commentary says is the one matched`() {
        // The stored `Virgil van Dijk` has to be matched as `Van Dijk`: a bare last token (`Dijk`) is
        // not the form a commentary says, and the particle is part of the name.
        val liverpool = SubtitleGlossary.squadEvidence(
            listOf(SubtitleGlossary.Team.LIVERPOOL),
            "Liverpool, with Van Dijk and Szoboszlai",
        ).single()
        assertTrue(liverpool.surnames.toString(), liverpool.surnames.contains("van Dijk"))
        assertTrue(liverpool.surnames.toString(), liverpool.surnames.contains("Szoboszlai"))
        // The full name is NOT what has to be said.
        assertTrue(liverpool.surnames.toString(), liverpool.surnames.none { it.contains("Virgil") })
        // `Micky van de Ven` is the case a bare last token gets outright wrong: it reduced to `Ven`,
        // losing the particle and the token a commentary actually says.
        assertEquals(
            listOf("van de Ven"),
            SubtitleGlossary.squadEvidence(
                listOf(SubtitleGlossary.Team.TOTTENHAM),
                "Tottenham with Van de Ven",
            ).single().surnames,
        )
        // A compound surname matches however the recogniser splits it.
        val city = SubtitleGlossary.squadEvidence(
            listOf(SubtitleGlossary.Team.MANCHESTER_CITY),
            "Man City and Ait Nouri, Foden and Doku",
        ).single()
        assertTrue(city.surnames.toString(), city.surnames.contains("Ait-Nouri"))
        assertTrue(city.surnames.toString(), city.surnames.contains("Foden"))
    }

    @Test
    fun `a nested short form cannot name a club the text never named`() {
        // `Hull City` contains `City`, which is how a commentary names Manchester City. Counting raw
        // hits would credit BOTH clubs; the longest match wins, so only Hull is counted...
        assertEquals(
            mapOf(SubtitleGlossary.Team.HULL to 1),
            SubtitleGlossary.teamMentionHits("Hull City win a corner"),
        )
        // ...and `Newcastle United` counts Newcastle, not United.
        assertEquals(
            mapOf(SubtitleGlossary.Team.NEWCASTLE to 1),
            SubtitleGlossary.teamMentionHits("Newcastle United win a corner"),
        )
    }

    @Test
    fun `a bare United or City counts only when no other club name precedes it`() {
        // West Ham United and Leicester City are clubs the table does not carry: nothing longer
        // competes for the generic half, so without the proper-noun guard `United` and `City` would
        // be credited to Manchester — a club the commentary never named.
        assertTrue(
            SubtitleGlossary.teamMentionHits("West Ham United win a corner").toString(),
            SubtitleGlossary.teamMentionHits("West Ham United win a corner").isEmpty(),
        )
        assertTrue(
            SubtitleGlossary.teamMentionHits("Leicester City win a corner").toString(),
            SubtitleGlossary.teamMentionHits("Leicester City win a corner").isEmpty(),
        )
        // The Manchester clubs' own names still count: at the head of the window, and introduced by
        // a lower-case word rather than a proper noun.
        assertEquals(
            mapOf(SubtitleGlossary.Team.MANCHESTER_CITY to 1),
            SubtitleGlossary.teamMentionHits("City win a corner"),
        )
        assertEquals(
            mapOf(SubtitleGlossary.Team.MANCHESTER_UNITED to 1),
            SubtitleGlossary.teamMentionHits("the United defence holds"),
        )
    }

    @Test
    fun `a generic half that tails a club name does not name Manchester`() {
        // The table cannot carry every club in the world, so the guard may not rest on the names it
        // happens to list: `Bristol City` and `Oxford United` arrive the same way `Hull City` does,
        // and crediting their generic half to Manchester would scope a club nobody named.
        assertTrue(
            SubtitleGlossary.teamMentionHits("Bristol City win a corner").toString(),
            SubtitleGlossary.teamMentionHits("Bristol City win a corner").isEmpty(),
        )
        assertTrue(
            SubtitleGlossary.teamMentionHits("Oxford United win a corner").toString(),
            SubtitleGlossary.teamMentionHits("Oxford United win a corner").isEmpty(),
        )
        // A word that merely OPENS a clause is capitalised by the recogniser at the head of a
        // sentence, so it is not the proper noun of a club name: `However City hold on` still names
        // Manchester City.
        assertEquals(
            mapOf(SubtitleGlossary.Team.MANCHESTER_CITY to 1),
            SubtitleGlossary.teamMentionHits("However City hold on"),
        )
        assertEquals(
            mapOf(SubtitleGlossary.Team.MANCHESTER_UNITED to 1),
            SubtitleGlossary.teamMentionHits("And United press on"),
        )
    }

    @Test
    fun `an ordinary lower-case half names no club`() {
        // The recogniser capitalises a proper noun, so a lower-case `city` is the ordinary word — the
        // same signal `squadSidesIn` reads from a surname. Without it every `the city` would vote for
        // Manchester City, and `united` for Manchester United.
        assertTrue(
            SubtitleGlossary.teamMentionHits("the city council met earlier").toString(),
            SubtitleGlossary.teamMentionHits("the city council met earlier").isEmpty(),
        )
        assertTrue(
            SubtitleGlossary.teamMentionHits("they stand united behind the manager").toString(),
            SubtitleGlossary.teamMentionHits("they stand united behind the manager").isEmpty(),
        )
    }

    @Test
    fun `the football vocabulary carries every set piece the gate may open on`() {
        // The gate ([SportSceneDetector]'s SCENE_TERMS) can OPEN football on a set piece or the
        // keeper, so the decoder vocabulary must carry those same words: a term that opens the scene
        // but is never biased toward is the recognition/tuning mismatch that lets the scene age back
        // out. Exact membership, not substring, so `corner kick` cannot be satisfied by `corner`.
        val entries = SubtitleGlossary.recognitionHotwords(SubtitleGlossary.Sport.FOOTBALL)
            .split("/")
            .map { it.substringBefore(" :") }
        for (term in listOf(
            "goal kick", "corner kick", "goalkeeper", "goalmouth", "penalty kick", "penalty box",
        )) {
            assertTrue("'$term' opens a football scene but is not in the decoder vocabulary", term in entries)
        }
    }

    @Test
    fun `every ground that opens the gate also rides the decoder vocabulary`() {
        // The gate ([SportSceneDetector]'s SCENE_TERMS) opens football on a ground named alone, so the
        // decoder vocabulary must carry the same grounds: a term that opens the scene but is never
        // biased toward is the recognition/tuning mismatch that lets the scene age back out. Exact
        // membership, not substring.
        val entries = SubtitleGlossary.recognitionHotwords(SubtitleGlossary.Sport.FOOTBALL)
            .split("/")
            .map { it.substringBefore(" :") }
        for (ground in listOf(
            "Anfield", "Camp Nou", "Bernabeu", "San Siro", "Signal Iduna Park", "Maracana",
            "Tottenham Hotspur Stadium", "Westfalenstadion", "Estadio da Luz",
        )) {
            assertTrue("'$ground' opens a football scene but is not in the decoder vocabulary", ground in entries)
        }
    }

    @Test
    fun `a proved fixture narrows the decoder to its two clubs, their squads and the core calls`() {
        val full = SubtitleGlossary.recognitionHotwords(SubtitleGlossary.Sport.FOOTBALL)
        val scoped = SubtitleGlossary.recognitionHotwords(
            SubtitleGlossary.Sport.FOOTBALL,
            setOf(SubtitleGlossary.Team.LIVERPOOL, SubtitleGlossary.Team.MANCHESTER_CITY),
        )
        val entries = scoped.split("/")
        // Both sides' squads ride, as the surnames a commentary actually says and as the full names
        // that reach a given-name token. They are the bulk of the list and ride the middle tier.
        assertTrue(scoped, entries.contains("Szoboszlai :3.0"))
        assertTrue(scoped, entries.contains("Gakpo :3.0"))
        assertTrue(scoped, entries.contains("Wirtz :3.0"))
        assertTrue(scoped, entries.contains("Foden :3.0"))
        assertTrue(scoped, entries.contains("Haaland :3.0"))
        assertTrue(scoped, entries.contains("Dominik Szoboszlai :3.0"))
        // ...no other squad is, which is the context-graph length each decode is spent on...
        assertFalse(scoped, scoped.contains("Saka"))
        assertFalse(scoped, scoped.contains("Palmer"))
        // The two clubs are the loudest tier, the squads the middle, the calls of the game the base.
        assertTrue(scoped, entries.contains("Liverpool :4.0"))
        assertTrue(scoped, entries.contains("Manchester City :4.0"))
        assertTrue(scoped, entries.contains("VAR :2.5"))
        assertTrue(scoped, entries.contains("nil"))
        // The narrowing keeps the calls a live commentary repeats most, the set pieces included.
        val coreCalls = entries.map { it.substringBefore(" :") }
        assertTrue(scoped, coreCalls.contains("corner kick"))
        assertTrue(scoped, coreCalls.contains("goal kick"))
        assertTrue(scoped, coreCalls.contains("goalkeeper"))
        // A proved fixture NARROWS the graph: the other clubs and the rest of the terminology are
        // dropped from the decoder's list — they still carry the unscoped baseline that OPENED the
        // scene, so the fixture is a narrow-but-deep bias rather than the whole table plus two squads.
        assertFalse(scoped, entries.contains("Tottenham"))
        assertTrue("a proved fixture is narrower than the unscoped baseline", scoped.length < full.length)
        // The unscoped list carries no squad at all, so scoping is what adds the named sides' names.
        assertFalse(full, full.contains("Szoboszlai"))
        assertFalse(full, full.contains("Palmer"))
    }

    @Test
    fun `a scope carries one side as readily as two, and refuses an over-full set`() {
        val full = SubtitleGlossary.recognitionHotwords(SubtitleGlossary.Sport.FOOTBALL)
        // No club named: nothing to add, so the scene vocabulary rides unchanged.
        assertEquals(
            full,
            SubtitleGlossary.recognitionHotwords(SubtitleGlossary.Sport.FOOTBALL, emptySet()),
        )
        // ONE club is enough — that is what scoping early means: the squad has to be on the graph
        // before its players can be recognised, and the other side joins it when it is named.
        val one = SubtitleGlossary.recognitionHotwords(
            SubtitleGlossary.Sport.FOOTBALL,
            setOf(SubtitleGlossary.Team.LIVERPOOL),
        )
        assertTrue(one, one.split("/").contains("Szoboszlai :3.0"))
        assertTrue(one, one.split("/").contains("Liverpool :4.0"))
        // Only the named side is scoped; the other side's squad joins it when that club is named.
        assertFalse(one, one.contains("Haaland"))
        assertTrue("one named side scopes the list to that side", one.length < full.length)
        // Three named clubs are no longer a fixture being commentated but the round-up after it; the
        // cap is what keeps every squad a long window mentions off the graph.
        assertEquals(
            full,
            SubtitleGlossary.recognitionHotwords(
                SubtitleGlossary.Sport.FOOTBALL,
                setOf(
                    SubtitleGlossary.Team.LIVERPOOL,
                    SubtitleGlossary.Team.MANCHESTER_CITY,
                    SubtitleGlossary.Team.ARSENAL,
                ),
            ),
        )
    }

    @Test
    fun `a proved fixture keeps its own grounds on the scoped decoder list`() {
        // The scoped list REPLACES the unscoped baseline, so the two proved clubs' venues have to
        // ride it: a commentary can spend a whole passage naming only the ground (`Anfield is
        // rocking`), and without its own venue on the list the fixture would age back to the empty
        // scene while the match is still on. The ground rides the loudest, club tier.
        val scoped = SubtitleGlossary.recognitionHotwords(
            SubtitleGlossary.Sport.FOOTBALL,
            setOf(SubtitleGlossary.Team.LIVERPOOL, SubtitleGlossary.Team.EVERTON),
        )
        val entries = scoped.split("/")
        assertTrue(scoped, entries.contains("Anfield :4.0"))
        assertTrue(scoped, entries.contains("Goodison Park :4.0"))
        // ...and a club not on the pitch keeps its venue off the list, the same narrowing rule.
        assertFalse(scoped, scoped.contains("Emirates Stadium"))
    }

    @Test
    fun `managers and officials are biased and protected but never quoted as evidence`() {
        // Bias: the touchline and officialdom names ride the unscoped decoder list, so a mangled
        // `Arteta` / `Kavanagh` is decoded toward the name a commentary is actually saying.
        val full = SubtitleGlossary.recognitionHotwords(SubtitleGlossary.Sport.FOOTBALL)
        assertTrue(full, full.contains("Mikel Arteta"))
        assertTrue(full, full.contains("Michael Oliver"))
        // The SCOPED list carries the fixture's own two managers, beside the squads they send out...
        val scoped = SubtitleGlossary.recognitionHotwords(
            SubtitleGlossary.Sport.FOOTBALL,
            setOf(SubtitleGlossary.Team.ARSENAL),
        )
        assertTrue(scoped, scoped.contains("Mikel Arteta"))
        assertTrue(scoped, scoped.contains("Arteta"))
        // ...but no other club's manager, which is the same narrowing the squads get.
        assertFalse(scoped, scoped.contains("David Moyes"))
        // Protection: the table KNOWS the names, so the repair pass never reads one as a surname slip.
        assertTrue(SubtitleGlossary.knows("Arteta"))
        assertTrue(SubtitleGlossary.knows("Kavanagh"))
    }

    @Test
    fun `every scoped player rides the decoder list and carries a pinned rendering`() {
        // The guarantee scoping exists for: once a club is proved, EVERY man in its squad is biased by
        // the decoder ([recognitionHotwords]) and has a Chinese rendering the fixture prompt can pin
        // ([PLAYER_CHINESE]). A squad member absent from either table is one the pipeline can hear but
        // never render, or hear wrong and never repair — so the two are kept in step by hand, and this
        // asserts they actually are, per club, rather than trusting a sample name.
        for ((team, players) in SubtitleGlossary.TEAM_PLAYERS) {
            assertTrue("$team carries no squad", players.isNotEmpty())
            val hotwords = SubtitleGlossary.recognitionHotwords(SubtitleGlossary.Sport.FOOTBALL, setOf(team))
                .split("/")
                .mapTo(HashSet()) { it.substringBefore(" :") }
            for (player in players) {
                assertTrue("$player ($team) is not on the scoped decoder list", player in hotwords)
                // The surname is the form a commentary says most, and the one the near-miss repair
                // ([correctNames]) is keyed on, so it has to ride beside the full name.
                val surname = SubtitleGlossary.spokenSurname(player)
                assertTrue("$surname ($player, $team) is not on the scoped decoder list", surname in hotwords)
                assertTrue(
                    "$player ($team) has no rendering in PLAYER_CHINESE",
                    player in SubtitleGlossary.PLAYER_CHINESE,
                )
            }
        }
    }

    @Test
    fun `every shared rendering is told apart by its whole name`() {
        // Where two squad members render to the SAME Chinese word, a bare `Surname → rendering` line
        // cannot say which man a mention means, so [playerPromptSection] pins the whole name too — and
        // pinning the whole name needs the given name's rendering in [GIVEN_CHINESE]. A collision
        // without one silently loses the disambiguation, so every collision is asserted covered.
        val shared = SubtitleGlossary.PLAYER_CHINESE.values
            .groupingBy { it }
            .eachCount()
            .filterValues { it > 1 }
            .keys
        assertTrue("no rendering collides, so this test guards nothing", shared.isNotEmpty())
        for ((fullName, chinese) in SubtitleGlossary.PLAYER_CHINESE) {
            if (chinese !in shared) continue
            assertTrue(
                "$fullName renders $chinese like a team-mate but has no given-name pin",
                fullName.substringBefore(' ') in SubtitleGlossary.GIVEN_CHINESE,
            )
        }
    }

    @Test
    fun `squad evidence reports the players a ranked club is carrying`() {
        // A ranked club with one of its own surnames in the window. The verdict alone cannot say
        // whether the surname pass is matching at all, so this reading is what tells "no club ranked"
        // apart from "ranked but no player matched".
        val arsenal = SubtitleGlossary.squadEvidence(
            listOf(SubtitleGlossary.Team.ARSENAL),
            "Arsenal Saka",
        ).single { it.team == SubtitleGlossary.Team.ARSENAL }
        assertEquals(listOf("Saka"), arsenal.surnames)
        // A squad with none of its players in the window reports an EMPTY roster rather than nothing
        // at all, so a surname pass that is not matching looks different from a club never ranked.
        assertEquals(
            emptyList<String>(),
            SubtitleGlossary.squadEvidence(listOf(SubtitleGlossary.Team.ARSENAL), "offside and a free kick")
                .single().surnames,
        )
    }

    @Test
    fun `a squad side is found through a capitalised unique surname only`() {
        // The surname alone identifies the club, which is what lets the detector find the second side
        // of a fixture before its club name is ever said.
        assertEquals(
            setOf(SubtitleGlossary.Team.ARSENAL),
            SubtitleGlossary.squadSidesIn("Saka carries it forward"),
        )
        // A lower-case word that happens to be a surname (`a long ball`) is not a name: the capital
        // is the recogniser's own signal that it heard a proper noun.
        assertEquals(emptySet<SubtitleGlossary.Team>(), SubtitleGlossary.squadSidesIn("saka"))
        // A club name is not a squad member, so it finds nothing here — this pass is the PLAYERS'.
        assertEquals(emptySet<SubtitleGlossary.Team>(), SubtitleGlossary.squadSidesIn("Liverpool attack"))
    }

    @Test
    fun `the fixture prompt names the scoped clubs as well as their squads`() {
        val section = SubtitleGlossary.fixturePromptSection(
            setOf(SubtitleGlossary.Team.ARSENAL),
            "zh-CN",
        )
        assertTrue(section, section.startsWith("Clubs:"))
        assertTrue(section, section.contains("Arsenal → 阿森纳"))
        assertTrue(section, section.contains("Saka → 萨卡"))
        // Every spelling the club is recognised by is pinned, not just the label: a fragment that
        // says only `Gunners` would otherwise be rendered by the nickname's literal sense.
        assertTrue(section, section.contains("Gunners → 阿森纳"))
        // Nothing scoped, or a non-Chinese target, costs the prompt nothing.
        assertEquals("", SubtitleGlossary.fixturePromptSection(emptySet(), "zh-CN"))
        assertEquals("", SubtitleGlossary.fixturePromptSection(setOf(SubtitleGlossary.Team.ARSENAL), "en"))
    }

    @Test
    fun `two players who render the same are told apart by their whole names`() {
        // `Fernandez` (City) and `Fernandes` (United) both render 费尔南德斯, so a surname-to-rendering
        // line cannot say which man a full-name mention means. The colliding pair is pinned by their
        // whole names as well, the given name qualified.
        val section = SubtitleGlossary.fixturePromptSection(
            setOf(SubtitleGlossary.Team.MANCHESTER_CITY, SubtitleGlossary.Team.MANCHESTER_UNITED),
            "zh-CN",
        )
        assertTrue(section, section.contains("Enzo Fernandez → 恩佐·费尔南德斯"))
        assertTrue(section, section.contains("Bruno Fernandes → 布鲁诺·费尔南德斯"))
        // The plain surname line still rides for a bare-surname mention.
        assertTrue(section, section.contains("Fernandez → 费尔南德斯"))
        assertTrue(section, section.contains("Fernandes → 费尔南德斯"))
        // A fixture with no collision keeps the plain line only: no extra line and no whole-name pin.
        val single = SubtitleGlossary.fixturePromptSection(
            setOf(SubtitleGlossary.Team.ARSENAL),
            "zh-CN",
        )
        assertFalse(single, single.contains("Bukayo Saka →"))
    }

    @Test
    fun `a near-miss of a scoped surname is repaired to it`() {
        val city = setOf(SubtitleGlossary.Team.MANCHESTER_CITY)
        // `Kovacik` is one substitution from `Kovacic` and from no other surname on the pitch
        // (`Khusanov` is not close), so it is repaired — with the heard form's own capitalisation,
        // and for a name the hand-written table does not carry, which is the point of the pass.
        assertEquals("Kovacic turns away", SubtitleGlossary.correctNames("Kovacik turns away", city))
        // Nothing scoped is a no-op, as is a word the table already carries spelled right.
        assertEquals("Kovacik turns away", SubtitleGlossary.correctNames("Kovacik turns away", emptySet()))
        assertEquals("Kovacic turns away", SubtitleGlossary.correctNames("Kovacic turns away", city))
        // A surname below the five-letter floor is not a candidate, because at four letters the
        // near-miss of an ordinary word is the common case (`Word` / `Wood`) and the first-letter
        // rule cannot separate them: `Rices` is not repaired to the short `Rice`.
        assertEquals(
            "Rices into the box",
            SubtitleGlossary.correctNames("Rices into the box", setOf(SubtitleGlossary.Team.ARSENAL)),
        )
    }

    @Test
    fun `a near-miss that two scoped surnames could explain is left as heard`() {
        // `Fernandez` and `Fernandes` are one edit apart and both on the pitch for a Manchester
        // fixture, so repairing either way would be a guess — the ambiguity is exactly what the
        // uniqueness rule refuses. `Fernande` is the truncation both would explain.
        val manchester = setOf(
            SubtitleGlossary.Team.MANCHESTER_CITY,
            SubtitleGlossary.Team.MANCHESTER_UNITED,
        )
        assertEquals(
            "Fernande plays it wide",
            SubtitleGlossary.correctNames("Fernande plays it wide", manchester),
        )
    }

    @Test
    fun `an ordinary English word is never rewritten to the surname it is one edit from`() {
        val arsenal = setOf(SubtitleGlossary.Team.ARSENAL)
        // `While` is one substitution from Arsenal's `White`, and the bare-surname pass sees only the
        // token in isolation — without the common-word veto the recogniser's own reading of a
        // perfectly good sentence would be rewritten into a name.
        assertEquals("While we wait", SubtitleGlossary.correctNames("While we wait", arsenal))
        // A token shaped like a name but NOT an ordinary word is still repaired, so the veto does not
        // cost the correction it exists to protect.
        assertEquals("White clears it", SubtitleGlossary.correctNames("Whita clears it", arsenal))
    }

    @Test
    fun `a fixture's repair index is cached yet still keyed to its own clubs`() {
        val city = setOf(SubtitleGlossary.Team.MANCHESTER_CITY)
        val liverpool = setOf(SubtitleGlossary.Team.LIVERPOOL)
        // The index is built on first use and reused, so repeated calls must stay identical...
        repeat(3) {
            assertEquals("Kovacic turns away", SubtitleGlossary.correctNames("Kovacik turns away", city))
        }
        // ...and a different fixture must be served its OWN index, not the first one's.
        assertEquals("Gakpo turns away", SubtitleGlossary.correctNames("Gakpe turns away", liverpool))
        assertEquals("Kovacic turns away", SubtitleGlossary.correctNames("Kovacik turns away", city))
    }

    @Test
    fun `every squad member's full name is repaired from a one-letter slip`() {
        // The full-name guarantee: for EVERY squad member in the table, a surname the recogniser gets
        // ONE letter wrong in his full name is repaired to the table's spelling. This is the pass that
        // covers the names the bare-surname repair deliberately refuses — a short surname (`Saka`,
        // `Eze`) and a compound one (`van Dijk`, `Lewis-Potter`, `O'Riley`) — because the given name
        // supplies the context a bare surname lacks. Scoped to the club that fields him, one at a time.
        for ((team, players) in SubtitleGlossary.TEAM_PLAYERS) {
            for (player in players) {
                val slipped = player.dropLast(1) + if (player.last().lowercaseChar() == 'x') 'q' else 'x'
                assertEquals(
                    "$player ($team) was not repaired from a full-name slip",
                    "and $player carries it",
                    SubtitleGlossary.correctNames("and $slipped carries it", setOf(team)),
                )
            }
        }
    }

    @Test
    fun `a full name repairs a compound surname and a given-name slip alike`() {
        val liverpool = setOf(SubtitleGlossary.Team.LIVERPOOL)
        // `van Dijk` carries a space and a lower-case particle, so the bare-surname pass never sees
        // it as a candidate; the whole name does.
        assertEquals(
            "Virgil van Dijk heads it clear",
            SubtitleGlossary.correctNames("Virgil van Dijx heads it clear", liverpool),
        )
        // A slip in the GIVEN name, away from the first letter, is repaired too — that is the run the
        // bare-surname pass cannot see at all.
        assertEquals(
            "Virgil van Dijk heads it clear",
            SubtitleGlossary.correctNames("Virgix van Dijk heads it clear", liverpool),
        )
    }

    @Test
    fun `a full name is repaired, but a bare short surname and ordinary speech are not`() {
        val arsenal = setOf(SubtitleGlossary.Team.ARSENAL)
        // The whole name carries the context, so a slip in it is repaired...
        assertEquals(
            "Bukayo Saka carries it",
            SubtitleGlossary.correctNames("Bukayo Sakx carries it", arsenal),
        )
        // ...while a BARE short surname stays as heard: with no given name beside it the collision
        // with an ordinary word is exactly what the bare-surname pass refuses to guess at.
        assertEquals("Sakx carries it", SubtitleGlossary.correctNames("Sakx carries it", arsenal))
        // Ordinary speech is untouched: no window of it is a near-miss of a scoped full name.
        assertEquals(
            "the corner shop is open",
            SubtitleGlossary.correctNames("the corner shop is open", arsenal),
        )
    }

    @Test
    fun `a name's two halves correct each other`() {
        val arsenal = setOf(SubtitleGlossary.Team.ARSENAL)
        // The GIVEN name is heard right, so it pins the man and repairs a surname two edits out —
        // `Sakho`/`Saka` is past the single-edit rule, so only the mutual half rule reaches it.
        assertEquals(
            "Bukayo Saka carries it",
            SubtitleGlossary.correctNames("Bukayo Sakho carries it", arsenal),
        )
        // And the other way round: the SURNAME pins the man and repairs a given name two edits out.
        assertEquals(
            "Virgil van Dijk heads it clear",
            SubtitleGlossary.correctNames("Virktil van Dijk heads it clear", setOf(SubtitleGlossary.Team.LIVERPOOL)),
        )
    }

    @Test
    fun `a half that fits two players pins neither`() {
        // Arsenal fields TWO Martins (`Odegaard`, `Zubimendi`), so `Martin` names no one man and cannot
        // be the anchor that repairs the surname; the mutual rule refuses rather than guess a man —
        // the same reason a bare short surname is left alone.
        assertEquals(
            "Martin Odegoord plays it",
            SubtitleGlossary.correctNames("Martin Odegoord plays it", setOf(SubtitleGlossary.Team.ARSENAL)),
        )
    }

    @Test
    fun `scoping teams passes a fixture through and refuses an over-full set`() {
        val one = setOf(SubtitleGlossary.Team.LIVERPOOL)
        val two = one + SubtitleGlossary.Team.MANCHESTER_CITY
        assertEquals(one, SubtitleGlossary.scopingTeams(one))
        assertEquals(two, SubtitleGlossary.scopingTeams(two))
        assertEquals(two, SubtitleGlossary.scopingTeams(SubtitleGlossary.scopingTeams(two)))
        assertEquals(
            emptySet<SubtitleGlossary.Team>(),
            SubtitleGlossary.scopingTeams(two + SubtitleGlossary.Team.ARSENAL),
        )
    }

    // --- the short club spellings ---------------------------------------------------------------

    @Test
    fun `a scoped club carries the short spellings a commentary actually says`() {
        // `Man City` / `Man United` are said far more often than the full name, so they have to ride
        // the decoder's scoped list or the short form receives none of the club's own bias — the
        // decoder would hear the league's most-used nickname with no help at all.
        val city = SubtitleGlossary.recognitionHotwords(
            SubtitleGlossary.Sport.FOOTBALL,
            setOf(SubtitleGlossary.Team.MANCHESTER_CITY),
        ).split("/")
        assertTrue(city.toString(), city.contains("Man City :4.0"))
        val united = SubtitleGlossary.recognitionHotwords(
            SubtitleGlossary.Sport.FOOTBALL,
            setOf(SubtitleGlossary.Team.MANCHESTER_UNITED),
        ).split("/")
        assertTrue(united.toString(), united.contains("Man United :4.0"))
        assertTrue(united.toString(), united.contains("Man Utd :4.0"))
    }

    // --- the in-sentence player disambiguation --------------------------------------------------

    @Test
    fun `a surname two scoped players share is expanded when the sentence names one club`() {
        val fixture = setOf(SubtitleGlossary.Team.BRENTFORD, SubtitleGlossary.Team.LEEDS)
        // `Wilson` is Callum Wilson (Brentford) AND Harry Wilson (Leeds). The club named in the same
        // sentence is the missing evidence, so the bare surname can be expanded to the one man it
        // can only be — on the ENGLISH side, before the translator sees it, so both captions agree.
        assertEquals(
            "Callum Wilson drives forward for Brentford",
            SubtitleGlossary.resolveSurnames("Wilson drives forward for Brentford", fixture),
        )
        assertEquals(
            "Harry Wilson drives forward for Leeds",
            SubtitleGlossary.resolveSurnames("Wilson drives forward for Leeds", fixture),
        )
        // The same pass gives the other shared pair (`Henderson`: Chelsea and Palace) a name too.
        assertEquals(
            "Jordan Henderson starts for Chelsea",
            SubtitleGlossary.resolveSurnames(
                "Henderson starts for Chelsea",
                setOf(SubtitleGlossary.Team.CHELSEA, SubtitleGlossary.Team.CRYSTAL_PALACE),
            ),
        )
    }

    @Test
    fun `a shared surname the sentence cannot attribute is left exactly as heard`() {
        val fixture = setOf(SubtitleGlossary.Team.BRENTFORD, SubtitleGlossary.Team.LEEDS)
        // Both sides named: the two Wilsons are as indistinguishable as the clubs were, so any
        // expansion would be a guess that re-attributes the mention to the wrong man.
        assertEquals(
            "Brentford and Leeds, Wilson drives forward",
            SubtitleGlossary.resolveSurnames("Brentford and Leeds, Wilson drives forward", fixture),
        )
        // Neither side named carries no evidence at all, and no fixture scoped is a no-op.
        assertEquals(
            "Wilson drives forward",
            SubtitleGlossary.resolveSurnames("Wilson drives forward", fixture),
        )
        assertEquals(
            "Wilson drives forward",
            SubtitleGlossary.resolveSurnames("Wilson drives forward", emptySet()),
        )
    }

    @Test
    fun `a surname only one scoped player carries is left to the whole-name pinning`() {
        // `Saka` belongs to Arsenal alone, so there is nothing to disambiguate and this pass must not
        // rewrite it — expanding a uniquely-owned surname is the prompt pinning's job, not this one's.
        assertEquals(
            "Saka carries it forward",
            SubtitleGlossary.resolveSurnames(
                "Saka carries it forward",
                setOf(SubtitleGlossary.Team.ARSENAL),
            ),
        )
    }
}