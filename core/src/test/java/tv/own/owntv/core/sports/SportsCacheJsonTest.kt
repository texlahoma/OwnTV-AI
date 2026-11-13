package tv.own.owntv.core.sports

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import tv.own.owntv.core.sports.model.League
import tv.own.owntv.core.sports.model.Sport

class SportsCacheJsonTest {

    @Test
    fun `every parsed fixture survives the stored-board round trip unchanged`() {
        val bundesliga = League(Sport.SOCCER, "ger.1", "Bundesliga")
        val soccer = EspnParser.scoreboard(bundesliga, fixture("scoreboard_soccer_ger1.json"))
        val tennis = EspnParser.scoreboard(League(Sport.TENNIS, "atp", "ATP"), fixture("scoreboard_tennis_atp.json"))
        val cricket = EspnParser.cricketHeader(League(Sport.CRICKET, "all", "Cricket"), fixture("header_cricket.json"))
        val f1 = EspnParser.scoreboard(League(Sport.RACING, "f1", "F1"), fixture("scoreboard_racing_f1.json"))
        val summary = EspnParser.summary(soccer.first(), fixture("summary_soccer_ger1.json"))
        val table = EspnParser.standings(bundesliga, fixture("standings_soccer_ger1.json"))
        val board = SportsBoard(
            today = mapOf("soccer/ger.1" to soccer, "tennis/atp" to tennis, "cricket/all" to cricket),
            tomorrow = mapOf("racing/f1" to f1),
            summaries = mapOf(soccer.first().key to summary),
            standings = mapOf(bundesliga.key to table),
            rebuiltAt = 1_000L,
        )

        assertEquals(board, SportsCacheJson.read(SportsCacheJson.write(board)))
    }

    @Test
    fun `live flags are not stored, and a foreign or broken file is ignored`() {
        val flagged = SportsBoard(failing = setOf(Feed.Scoreboard("soccer/ger.1")), paused = true)
        assertEquals(SportsBoard(), SportsCacheJson.read(SportsCacheJson.write(flagged)))
        assertNull("the format before R1", SportsCacheJson.read("{\"v\":1,\"at\":0,\"today\":{}}"))
        assertNull(SportsCacheJson.read("{\"v\":99,\"at\":0}"))
        assertNull(SportsCacheJson.read("not json"))
    }
}
