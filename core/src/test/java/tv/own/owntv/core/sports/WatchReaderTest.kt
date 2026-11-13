package tv.own.owntv.core.sports

import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import tv.own.owntv.core.database.entity.ChannelEntity
import tv.own.owntv.core.database.entity.EpgProgrammeLite
import tv.own.owntv.core.sports.model.Broadcast
import tv.own.owntv.core.sports.model.Competitor
import tv.own.owntv.core.sports.model.Match
import tv.own.owntv.core.sports.model.MatchState
import tv.own.owntv.core.sports.model.Score
import tv.own.owntv.core.sports.model.Sport
import tv.own.owntv.core.sports.model.Team

class WatchReaderTest {

    private val kickOff = 1_760_000_000_000L

    private fun match(id: String, home: Team, away: Team, start: Long = kickOff, score: Int = 0, broadcasts: List<Broadcast> = emptyList()) = Match(
        id = id, sport = Sport.SOCCER, feed = "soccer/ger.1", leagueSlug = "ger.1", leagueName = "German Bundesliga",
        startMs = start, state = MatchState.IN, statusText = "63'", title = "${away.name} at ${home.name}",
        competitors = listOf(
            Competitor(home, home = true, winner = null, score = Score("$score", score)),
            Competitor(away, home = false, winner = null, score = Score("1", 1)),
        ),
        broadcasts = broadcasts,
    )

    private val frankfurt = Team("125", "Eintracht Frankfurt", "Frankfurt", "SGE", location = "Frankfurt")
    private val koln = Team("122", "FC Cologne", "Cologne", "KOE", location = "Cologne")
    private val bayern = Team("132", "Bayern Munich", "Bayern", "MUN", location = "Munich")
    private val dortmund = Team("124", "Borussia Dortmund", "Dortmund", "DOR", location = "Dortmund")

    private var nextId = 1L
    private fun ch(name: String, epg: String? = null, source: Long = 1) =
        ChannelEntity(id = nextId++, sourceId = source, name = name, streamUrl = "http://x/${nextId}", epgChannelId = epg)

    private val skyBl1 = ch("DE| SKY SPORT BUNDESLIGA 1 FHD", "sky.bl1")
    private val skyAt = ch("AT| SKY SPORT AUSTRIA 1 HD", "sky.at1")
    private val event = ch("DE| BL 18:30 Frankfurt v Köln")
    private val skyBl2 = ch("DE| SKY SPORT BUNDESLIGA 2", "sky.bl2")
    private val skyUk = ch("UK| SKY SPORTS FOOTBALL FHD", "sky.uk.football")
    private val news = ch("DE| N-TV", "ntv")
    private val all = listOf(skyBl1, skyAt, event, skyBl2, skyUk, news)

    private inner class Fake : WatchData {
        var fp = "a"
        var programmeReads = 0
        val descriptionReads = mutableListOf<List<Long>>()
        val guide = mutableListOf(
            EpgProgrammeLite(1, "sky.bl1", kickOff - 30 * 60_000, kickOff + 2 * 3_600_000, "Bundesliga: Eintracht Frankfurt – 1. FC Köln", "Sport"),
            EpgProgrammeLite(2, "sky.at1", kickOff, kickOff + 2 * 3_600_000, "Live: Frankfurt gegen Köln", null),
            // Only one team in the title; the synopsis names the other — a near-miss read once.
            EpgProgrammeLite(3, "sky.bl2", kickOff, kickOff + 2 * 3_600_000, "Konferenz mit Bayern München", null),
            EpgProgrammeLite(4, "ntv", kickOff, kickOff + 3_600_000, "Nachrichten", null),
        )
        val synopsis = mapOf(3L to "Bayern München gegen Borussia Dortmund, live aus der Allianz Arena")

        override suspend fun fingerprint() = fp
        override suspend fun programmes(from: Long, to: Long, minStop: Long): List<EpgProgrammeLite> {
            programmeReads++
            return guide.filter { it.startMs in from until to && it.stopMs > minStop }
        }
        override suspend fun descriptions(ids: List<Long>): Map<Long, String> {
            descriptionReads += ids
            return ids.mapNotNull { id -> synopsis[id]?.let { id to it } }.toMap()
        }
        override suspend fun channelsForGuideKeys(epgKeys: Set<String>) =
            all.filter { it.epgChannelId in epgKeys }.groupBy { it.epgChannelId!! }
        override suspend fun channelsNamingAny(words: List<String>) =
            all.filter { c -> words.any { w -> c.name.lowercase().contains(w) } }
        override suspend fun channelsNamingAll(words: List<String>) =
            all.filter { c -> words.all { w -> c.name.lowercase().contains(w) } }
    }

    @Test fun `tiers - guide, event, remembered, likely - each once, best first`() = runBlocking {
        val fake = Fake()
        val reader = WatchReader(fake)
        val m = match("1", frankfurt, koln)
        val options = reader.watchFor(listOf(m)) { RememberedChannel("SKY SPORTS FOOTBALL", "UK") }.getValue(m.key)

        assertEquals(
            listOf(skyBl1 to WatchTier.GUIDE, skyAt to WatchTier.GUIDE, event to WatchTier.EVENT, skyUk to WatchTier.REMEMBERED, skyBl2 to WatchTier.LIKELY),
            options.map { it.channel to it.tier },
        )
        assertEquals("Bundesliga: Eintracht Frankfurt – 1. FC Köln", options[0].guideTitle)
        assertTrue(news.id !in options.map { it.channel.id })
    }

    @Test fun `one guide read per kick-off slot, and a near-miss is confirmed by its synopsis`() = runBlocking {
        val fake = Fake()
        val reader = WatchReader(fake)
        val a = match("1", frankfurt, koln)
        val b = match("2", bayern, dortmund)
        val result = reader.watchFor(listOf(a, b))

        assertEquals(1, fake.programmeReads)
        assertEquals(1, reader.runs)
        assertEquals(listOf(listOf(3L)), fake.descriptionReads)
        assertEquals(skyBl2 to WatchTier.GUIDE, result.getValue(b.key).first().let { it.channel to it.tier })
    }

    @Test fun `nothing is matched until a screen asks, and then only the asked match`() = runBlocking {
        val fake = Fake()
        val reader = WatchReader(fake)
        assertEquals("a reader on its own reads nothing", 0, fake.programmeReads)
        val asked = match("1", frankfurt, koln)
        reader.watchFor(listOf(asked))
        assertEquals(1, reader.runs)
        reader.watchFor(listOf(asked))
        assertEquals("asked again with the guide unchanged: the cached answer", 1, reader.runs)
    }

    @Test fun `found - what a screen already searched, never a search of its own`() = runBlocking {
        val fake = Fake()
        val reader = WatchReader(fake)
        val m = match("1", frankfurt, koln)
        assertEquals("never searched", null, reader.found(m))
        assertEquals(0, fake.programmeReads)
        val searched = reader.watchFor(listOf(m)).getValue(m.key)
        assertEquals(searched, reader.found(m))
        fake.fp = "b" // the guide changed: the old answer no longer counts
        assertEquals(null, reader.found(m))
        assertEquals(1, fake.programmeReads)
    }

    @Test fun `a score refresh never runs the matcher again, a guide change does`() = runBlocking {
        val fake = Fake()
        val reader = WatchReader(fake)
        reader.watchFor(listOf(match("1", frankfurt, koln, score = 1)))
        reader.watchFor(listOf(match("1", frankfurt, koln, score = 2)))
        assertEquals(1, reader.runs)
        assertEquals(1, fake.programmeReads)

        fake.fp = "b" // an EPG sync wrote new rows
        reader.watchFor(listOf(match("1", frankfurt, koln, score = 2)))
        assertEquals(2, reader.runs)
    }

    @Test fun `a new match runs only its own slot`() = runBlocking {
        val fake = Fake()
        val reader = WatchReader(fake)
        reader.watchFor(listOf(match("1", frankfurt, koln)))
        reader.watchFor(listOf(match("1", frankfurt, koln), match("9", bayern, dortmund, start = kickOff + 3 * 3_600_000)))
        assertEquals(2, reader.runs)
        assertEquals(2, fake.programmeReads)
    }

    @Test fun `a broadcaster ESPN names makes a channel likely`() = runBlocking {
        val peacock = ch("US| PEACOCK EVENT 3")
        val fake = object : WatchData by Fake() {
            override suspend fun channelsNamingAll(words: List<String>) =
                (all + peacock).filter { c -> words.all { w -> c.name.lowercase().contains(w) } }
        }
        val m = match("1", frankfurt, koln, broadcasts = listOf(Broadcast("Peacock")))
        val options = WatchReader(fake).watchFor(listOf(m)).getValue(m.key)
        assertTrue(options.any { it.channel.id == peacock.id && it.tier == WatchTier.LIKELY })
    }

    @Test fun `facets read country, language and quality from the name`() {
        val f = ChannelFacets.of("AT| SKY SPORT AUSTRIA 1 HD")
        assertEquals("AT", f.country); assertEquals("DE", f.language); assertEquals(2, f.qualityRank)
        assertEquals(4, ChannelFacets.of("DE| SKY SPORT BUNDESLIGA UHD").qualityRank)
        assertEquals(2, ChannelFacets.of("DE| DAZN 1 ᴴᴰ").qualityRank)
        assertEquals("EN", ChannelFacets.of("UK| SKY SPORTS FOOTBALL FHD").language)
        assertEquals(null, ChannelFacets.of("BL 18:30 Frankfurt v Köln").language)
    }

    @Test fun `a fixture channel for another match and a divider row are never likely`() {
        val keys = WatchMatcher.Keys(match("1", frankfurt, koln))
        assertTrue(WatchMatcher.isLikely(keys, "DE| SKY SPORT BUNDESLIGA 1"))
        assertEquals(false, WatchMatcher.isLikely(keys, "Next | Hertha vs. Bochum | German Bundesliga"))
        assertTrue(WatchMatcher.isSeparator("##### BUNDESLIGA #####"))
        assertEquals(false, WatchMatcher.isSeparator("DE| SKY SPORT BUNDESLIGA 1"))
        val ranked = WatchMatcher.rank(emptyList(), emptyList(), listOf(ch("##### BUNDESLIGA #####"), ch("DE| SKY SPORT BUNDESLIGA 1")), emptyList(), null)
        assertEquals(listOf("DE| SKY SPORT BUNDESLIGA 1"), ranked.map { it.channel.name })
    }

    @Test fun `a colon country prefix gives country and language`() {
        val f = ChannelFacets.of("UK: PREMIER LEAGUE+ 1")
        assertEquals("UK", f.country); assertEquals("EN", f.language)
    }
}
