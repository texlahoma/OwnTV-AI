package tv.own.owntv.core.sports

import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import tv.own.owntv.core.database.entity.ChannelEntity
import tv.own.owntv.core.database.entity.EpgProgrammeLite
import tv.own.owntv.core.sports.model.Competitor
import tv.own.owntv.core.sports.model.Match
import tv.own.owntv.core.sports.model.MatchState
import tv.own.owntv.core.sports.model.Sport
import tv.own.owntv.core.sports.model.Team

/** Layer B works from the guide and the playlist alone — the fake has no ESPN anywhere. */
class SportOnChannelsTest {

    private val now = 1_760_000_000_000L
    private var id = 0L
    private fun ch(name: String, epg: String) = ChannelEntity(id = ++id, sourceId = 1, name = name, streamUrl = "http://x/$id", epgChannelId = epg)

    private val dyn = ch("DE| DYN SPORT 1", "dyn1")
    private val dazn = ch("DE| DAZN 2 ᴴᴰ", "dazn2")
    private val euro = ch("UK| EUROSPORT 1", "eurosport1")
    private val drama = ch("DE| ZDFneo", "zdfneo")
    private val sky = ch("DE| SKY SPORT BUNDESLIGA 1", "sky.bl1")
    private val channels = listOf(dyn, dazn, euro, drama, sky)

    private fun p(id: Long, epg: String, title: String, cat: String? = null, start: Long = now - 600_000) =
        EpgProgrammeLite(id, epg, start, start + 2 * 3_600_000, title, cat)

    private fun fake(vararg programmes: EpgProgrammeLite) = object : WatchData {
        override suspend fun fingerprint() = ""
        override suspend fun programmes(from: Long, to: Long, minStop: Long) =
            programmes.filter { it.startMs in from until to && it.stopMs > minStop }
        override suspend fun descriptions(ids: List<Long>) = emptyMap<Long, String>()
        override suspend fun channelsForGuideKeys(epgKeys: Set<String>) =
            channels.filter { it.epgChannelId in epgKeys }.groupBy { it.epgChannelId!! }
        override suspend fun channelsNamingAny(words: List<String>) = emptyList<ChannelEntity>()
        override suspend fun channelsNamingAll(words: List<String>) = emptyList<ChannelEntity>()
    }

    @Test fun `the hub's middle row - handball, darts, snooker, grouped by sport`() = runBlocking {
        val row = SportOnChannels(fake(
            p(1, "dyn1", "THW Kiel – SC Magdeburg", "Sport;Handball"),
            p(2, "dazn2", "Darts: European Championship"),
            p(3, "eurosport1", "Snooker: English Open, QF"),
            p(4, "zdfneo", "Rosamunde Pilcher: Liebe – Lügen"),
        )) { now }.read()

        assertEquals(listOf(GuideSport.HANDBALL, GuideSport.DARTS, GuideSport.SNOOKER), row.keys.toList())
        assertEquals(listOf(dyn), row.getValue(GuideSport.HANDBALL).single().channels)
        assertEquals("Darts: European Championship", row.getValue(GuideSport.DARTS).single().title)
    }

    @Test fun `a bare fixture counts only on a sports channel`() = runBlocking {
        val row = SportOnChannels(fake(
            p(1, "dyn1", "Füchse Berlin – SG Flensburg"),
            p(2, "zdfneo", "Liebe – Lügen"),
        )) { now }.read()
        assertEquals(listOf(dyn), row.getValue(GuideSport.OTHER).single().channels)
        assertEquals(1, row.values.flatten().size)
    }

    @Test fun `a programme confirmed for an ESPN match is left to the match card`() = runBlocking {
        val m = Match(
            "1", Sport.SOCCER, "soccer/ger.1", "ger.1", "German Bundesliga", now, MatchState.IN, "63'", "",
            competitors = listOf(
                Competitor(Team("125", "Eintracht Frankfurt", "Frankfurt"), true, null, null),
                Competitor(Team("122", "FC Cologne", "Cologne", location = "Cologne"), false, null, null),
            ),
        )
        val row = SportOnChannels(fake(
            p(1, "sky.bl1", "Bundesliga: Eintracht Frankfurt – 1. FC Köln", "Sport"),
            p(2, "dazn2", "Darts: European Championship"),
        )) { now }.read(listOf(m))
        assertEquals(listOf(GuideSport.DARTS), row.keys.toList())
    }

    @Test fun `finished and far-future programmes are not on now`() = runBlocking {
        val row = SportOnChannels(fake(
            p(1, "dazn2", "Darts: Premier League", start = now - 3 * 3_600_000), // ended an hour ago
            p(2, "eurosport1", "Snooker: Final", start = now + 3 * 3_600_000),
            p(3, "eurosport1", "Radsport: Paris–Tours", start = now + 10 * 60_000), // starting soon
        )) { now }.read()
        assertEquals(listOf(GuideSport.CYCLING), row.keys.toList())
    }

    @Test fun `observe emits at once`() = runBlocking {
        val first = SportOnChannels(fake(p(2, "dazn2", "Darts: European Championship"))) { now }.observe().first()
        assertTrue(GuideSport.DARTS in first)
    }

    @Test fun `sport words in several languages`() {
        assertEquals(GuideSport.HANDBALL, SportOnChannels.sportOf("Balonmano: Barça – Veszprém", null))
        assertEquals(GuideSport.CYCLING, SportOnChannels.sportOf("Cyclisme : Paris-Roubaix", null))
        assertEquals(GuideSport.MOTORSPORT, SportOnChannels.sportOf("Formel 1: Großer Preis der USA", null))
        assertEquals(GuideSport.MOTORSPORT, SportOnChannels.sportOf("Formula 1 - Qualifying", null))
        assertEquals(GuideSport.AMERICAN_FOOTBALL, SportOnChannels.sportOf("Football américain : NFL", null))
        assertEquals(GuideSport.FOOTBALL, SportOnChannels.sportOf("Süper Lig: Beşiktaş - Fenerbahçe", "Spor"))
        assertEquals(GuideSport.ICE_HOCKEY, SportOnChannels.sportOf("Eishockey: Adler Mannheim – Kölner Haie", null))
        assertNull(SportOnChannels.sportOf("Grand Designs", null))
        assertNull(SportOnChannels.sportOf("Super Size Me", "Documentary"))
    }
}
