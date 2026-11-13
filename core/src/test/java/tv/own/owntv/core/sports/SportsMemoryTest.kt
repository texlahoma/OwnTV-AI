package tv.own.owntv.core.sports

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import tv.own.owntv.core.database.entity.ChannelEntity
import tv.own.owntv.core.sports.model.Competitor
import tv.own.owntv.core.sports.model.Match
import tv.own.owntv.core.sports.model.MatchState
import tv.own.owntv.core.sports.model.Sport
import tv.own.owntv.core.sports.model.Team

class SportsMemoryTest {

    private val bayern = Team("132", "Bayern Munich", "Bayern", "FCB")
    private val dortmund = Team("124", "Borussia Dortmund", "Dortmund", "BVB")
    private fun bundesliga(id: String = "1") = Match(
        id, Sport.SOCCER, "soccer/ger.1", "ger.1", "German Bundesliga", 0, MatchState.PRE, "", "",
        competitors = listOf(Competitor(bayern, true, null, null), Competitor(dortmund, false, null, null)),
    )

    private fun ch(id: Long, source: Long, name: String) = ChannelEntity(id = id, sourceId = source, name = name, streamUrl = "http://x/$id")

    @Test fun `a remembered pick is stored by portable name and survives a re-sync`() {
        val before = ch(id = 10, source = 1, name = "DE| SKY SPORT BUNDESLIGA 1 ᴴᴰ")
        val config = SportsMemory.remember(SportsConfig(), bundesliga(), before)
        assertEquals(RememberedChannel("SKY SPORT BUNDESLIGA 1", "DE"), config.rememberedChannels["soccer/ger.1"])

        // The sync recreated every row: new id, and this time the provider wrote "FHD" in plain letters.
        val after = ch(id = 9_001, source = 1, name = "DE| SKY SPORT BUNDESLIGA 1 FHD")
        val other = ch(id = 9_002, source = 1, name = "DE| SKY SPORT BUNDESLIGA 2")
        val ranked = WatchMatcher.rank(emptyList(), emptyList(), emptyList(), listOf(other, after), SportsMemory.rememberedFor(config, bundesliga()))
        assertEquals(listOf(9_001L), ranked.filter { it.tier == WatchTier.REMEMBERED }.map { it.channel.id })
    }

    @Test fun `a remembered pick survives a backup and restore, onto another playlist`() {
        val config = SportsMemory.remember(SportsConfig(), bundesliga(), ch(10, 1, "DE| SKY SPORT BUNDESLIGA 1"))
        val restored = SportsConfig.fromJson(config.toJson().toString())
        assertEquals(config.rememberedChannels, restored.rememberedChannels)

        // Another device, another provider, other ids — and a country the pick did not name is refused.
        val onOtherDevice = ch(77, 5, "DE: Sky Sport Bundesliga 1")
        val austrian = ch(78, 5, "AT| SKY SPORT BUNDESLIGA 1")
        val ranked = WatchMatcher.rank(emptyList(), emptyList(), emptyList(), listOf(austrian, onOtherDevice), restored.rememberedChannels["soccer/ger.1"])
        assertEquals(listOf(77L), ranked.map { it.channel.id })
    }

    @Test fun `forget removes it`() {
        val config = SportsMemory.remember(SportsConfig(), bundesliga(), ch(10, 1, "SKY SPORT BUNDESLIGA 1"))
        assertTrue(SportsMemory.forget(config, "soccer/ger.1").rememberedChannels.isEmpty())
    }

    @Test fun `suggestions after three plays, never auto-followed, followed ones left out`() {
        var config = SportsConfig()
        repeat(2) { config = SportsMemory.recordPlay(config, bundesliga()) }
        assertTrue(SportsMemory.suggestions(config, followedLeagues = emptySet()).isEmpty())

        config = SportsMemory.recordPlay(config, bundesliga())
        config = SportsMemory.recordPlay(config, bundesliga())
        val s = SportsMemory.suggestions(config, followedLeagues = emptySet())
        assertEquals(listOf("soccer/132", "soccer/124", "soccer/ger.1"), s.map { it.key })
        assertEquals(4, s.first().plays) // "You watched 4 of their matches"
        assertEquals("Bundesliga", s.last().name)
        assertTrue(config.teams.isEmpty()) // nothing was followed

        val following = config.copy(teams = listOf(FollowedTeam(Sport.SOCCER, "132", "Bayern Munich")))
        assertEquals(listOf("soccer/124"), SportsMemory.suggestions(following, setOf("soccer/ger.1")).map { it.key })
    }

    @Test fun `a remembered competition is suggested and a cricket series offers the cricket feed`() {
        val cricket = bundesliga().copy(sport = Sport.CRICKET, feed = "cricket/all", leagueSlug = "1478", leagueName = "ICC Women's ODI")
        var config = SportsMemory.remember(SportsConfig(), cricket, ch(1, 1, "WILLOW HD"))
        repeat(3) { config = SportsMemory.recordPlay(config, cricket) }
        val league = SportsMemory.suggestions(config, emptySet()).single { it.team == null }
        assertEquals("cricket/all", league.key)
        assertEquals("ICC Women's ODI", league.name)
        assertTrue(SportsMemory.suggestions(config, setOf("cricket/all")).none { it.team == null })
    }

    @Test fun `play counts round-trip and are bounded`() {
        var config = SportsConfig()
        repeat(SportsConfig.MAX_PLAYED + 10) { i ->
            config = SportsMemory.recordPlay(config, bundesliga().copy(competitors = listOf(
                Competitor(Team("t$i", "Team $i"), true, null, null),
            )))
        }
        assertEquals(SportsConfig.MAX_PLAYED, config.playedTeams.size)
        assertEquals(config.playedTeams, SportsConfig.fromJson(config.toJson().toString()).playedTeams)
        assertEquals(config.playedLeagues, SportsConfig.fromJson(config.toJson().toString()).playedLeagues)
    }
}
