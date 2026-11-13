package tv.own.owntv.core.sync

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/** How an M3U series entry's name is split into show, season and episode. */
class M3uEpisodeNameTest {

    private fun parse(name: String) = M3uSyncer.parseM3uEpisode(name)

    @Test
    fun theCommonMarkerKeepsWorking() {
        val p = parse("Stranger Things S01E05 - The Flea and the Acrobat")
        assertEquals("Stranger Things", p.show)
        assertEquals(1, p.season)
        assertEquals(5, p.episode)
        assertEquals("The Flea and the Acrobat", p.title)
    }

    @Test
    fun specialsAreSeasonZero() {
        assertEquals(0, parse("Doctor Who S00E01").season)
        assertEquals(0, parse("Doctor Who 0x01").season)
        assertEquals(0, parse("Doctor Who Season 0 Episode 1").season)
    }

    @Test
    fun yearSeasonsAndLongRunsAreRead() {
        val year = parse("MythBusters S2019E05")
        assertEquals(2019, year.season)
        assertEquals(5, year.episode)
        assertEquals(1050, parse("One Piece S01E1050").episode)
    }

    @Test
    fun theMarkerWrittenOutInWords() {
        val p = parse("The Office Season 2 Episode 3 - Office Olympics")
        assertEquals("The Office", p.show)
        assertEquals(2, p.season)
        assertEquals(3, p.episode)
        assertEquals("Office Olympics", p.title)
        assertEquals(4, parse("Lost - Season 1 - Ep. 4").episode)
        assertEquals(7, parse("Lost season 1, ep 7").episode)
    }

    @Test
    fun theNxNMarkerKeepsWorking() {
        val p = parse("Show 2x03 - Pilot")
        assertEquals(2, p.season)
        assertEquals(3, p.episode)
    }

    @Test
    fun anUnmarkedNameStaysSeasonOneInPlaylistOrder() {
        val p = parse("Tales From The Crypt (1989-90s)")
        assertEquals(1, p.season)
        assertEquals(0, p.episode)
        assertNull(p.title)
    }
}
