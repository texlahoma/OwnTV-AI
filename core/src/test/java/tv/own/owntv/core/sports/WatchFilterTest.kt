package tv.own.owntv.core.sports

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import tv.own.owntv.core.database.entity.ChannelEntity

class WatchFilterTest {

    private var id = 0L
    private fun opt(name: String, tier: WatchTier, source: Long = 1) =
        WatchOption(ChannelEntity(id = ++id, sourceId = source, name = name, streamUrl = "http://x/$id"), tier)

    // Seven channels for Frankfurt – Köln.
    private val bl1 = opt("DE| SKY SPORT BUNDESLIGA 1 FHD", WatchTier.GUIDE)
    private val uhd = opt("DE| SKY SPORT BUNDESLIGA UHD", WatchTier.GUIDE)
    private val austria = opt("AT| SKY SPORT AUSTRIA 1 HD", WatchTier.GUIDE, source = 2)
    private val event = opt("DE| BL 18:30 Frankfurt v Köln HD", WatchTier.EVENT)
    private val uk = opt("UK| SKY SPORTS FOOTBALL FHD", WatchTier.REMEMBERED)
    private val arabic = opt("AR| BEIN SPORTS 3 HD", WatchTier.LIKELY, source = 2)
    private val unknown = opt("BUNDESLIGA LIVE 720p", WatchTier.LIKELY)
    private val seven = listOf(bl1, uhd, austria, event, uk, arabic, unknown)

    @Test fun `German and English, any country, HD and better - showing 5 of 7`() {
        val list = WatchList.of(
            seven,
            WatchFilter(languages = setOf("DE", "EN"), minQuality = MinQuality.HD),
            preferredLanguages = listOf("DE", "EN"),
            alsoList = setOf("AR", "ES"),
        )
        assertEquals(5, list.shown.size)
        assertEquals(7, list.total)
        assertEquals(2, list.hidden)
        assertEquals(listOf(bl1, uhd, austria, event, uk), list.shown)
        assertEquals(linkedMapOf("DE" to 4, "EN" to 1, "AR" to 1, "ES" to 0, "" to 1), list.languageCounts)
        assertEquals(listOf("DE" to 3, "AT" to 1, "UK" to 1, "AR" to 1), list.countryCounts.toList())
        assertEquals(mapOf(1L to 5, 2L to 2), list.sourceCounts)
        assertEquals(listOf("AR", ""), list.hiddenLanguages)
    }

    @Test fun `only confirmed keeps guide and event channels`() {
        val list = WatchList.of(seven, WatchFilter(onlyConfirmed = true))
        assertEquals(listOf(bl1, uhd, austria, event), list.shown)
    }

    @Test fun `quality - unknown never hides, 4K only keeps UHD`() {
        val list = WatchList.of(seven, WatchFilter(minQuality = MinQuality.UHD))
        assertEquals(listOf(uhd), list.shown.filter { it.facets.qualityRank > 0 })
        assertTrue(list.shown.none { it.facets.qualityRank in 1..3 })
    }

    @Test fun `country and playlist`() {
        assertEquals(listOf(austria), WatchList.of(seven, WatchFilter(countries = setOf("AT"))).shown)
        assertEquals(listOf(austria, arabic), WatchList.of(seven, WatchFilter(sourceIds = setOf(2L))).shown)
    }

    @Test fun `likely switched off in Settings is neither shown nor counted`() {
        val list = WatchList.of(seven, WatchFilter(showLikely = false))
        assertEquals(5, list.total)
        assertEquals(0, list.hidden)
    }

    @Test fun `preferred language first within a tier`() {
        val en = opt("UK| SKY SPORTS PREMIER LEAGUE", WatchTier.GUIDE)
        val de = opt("DE| SKY SPORT TOP EVENT", WatchTier.GUIDE)
        assertEquals(listOf(de, en), WatchList.of(listOf(en, de), WatchFilter(), preferredLanguages = listOf("DE")).shown)
    }

    @Test fun `default filter from Settings lets Unknown through, and is strict`() {
        val f = WatchFilter.from(SportsConfig(), "de-DE", liveSources = listOf(1L))
        assertEquals(setOf("DE", ""), f.languages)
        assertTrue(f.accepts(unknown))
        assertTrue(f.strict)
    }

    @Test fun `strict on - only passing channels, off - every channel with the passing ones first`() {
        val german = WatchFilter(languages = setOf("DE"))
        val strict = WatchList.of(seven, german)
        assertEquals("AT| counts as German", listOf(bl1, uhd, austria, event), strict.shown)
        assertEquals(3, strict.hidden)

        val all = WatchList.of(seven, german.copy(strict = false))
        assertEquals("passing first, then the rest, each in tier order", listOf(bl1, uhd, austria, event, uk, arabic, unknown), all.shown)
        assertEquals(0, all.hidden)
        assertEquals(emptyList<String>(), all.hiddenLanguages)
    }

    @Test fun `the Settings playlist filter keeps only this device's playlists`() {
        val config = SportsConfig(sourceIds = setOf(2L, 99L), strictFilter = false)
        val f = WatchFilter.from(config, "de", liveSources = listOf(1L, 2L))
        assertEquals(setOf(2L), f.sourceIds)
        assertEquals(false, f.strict)
        val restored = WatchFilter.from(SportsConfig(sourceIds = setOf(99L)), "de", liveSources = listOf(1L, 2L))
        assertEquals("ids from another device name nothing here: every playlist", emptySet<Long>(), restored.sourceIds)
        assertEquals(seven.size, WatchList.of(seven, restored.copy(languages = emptySet())).shown.size)
    }
}
