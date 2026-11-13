package tv.own.owntv.core.ui.stage

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Test

class PlaylistMarkTest {

    // --- the three documented examples ---

    @Test
    fun iptvPrefixAndSeparatorAreDropped() = assertEquals("GOLD", PlaylistMark.textOf("IPTV_GOLD"))

    @Test
    fun longNameKeepsFourLetters() = assertEquals("JUNI", PlaylistMark.textOf("Junior"))

    @Test
    fun shortNameStaysWhole() = assertEquals("X", PlaylistMark.textOf("X"))

    // --- the edges ---

    @Test
    fun tvPrefixIsDroppedToo() = assertEquals("SPOR", PlaylistMark.textOf("TV - Sports"))

    @Test
    fun prefixOnlyCountsAsAWholeWord() = assertEquals("TVOL", PlaylistMark.textOf("Tvoli"))

    @Test
    fun stackedPrefixesAreAllDropped() = assertEquals("GOLD", PlaylistMark.textOf("iptv tv gold"))

    @Test
    fun nameThatIsOnlyThePrefixKeepsIt() = assertEquals("IPTV", PlaylistMark.textOf("IPTV"))

    @Test
    fun separatorsInsideAreRemoved() = assertEquals("MYLI", PlaylistMark.textOf("my.list-2"))

    @Test
    fun digitsCount() = assertEquals("4KUH", PlaylistMark.textOf("4K UHD"))

    @Test
    fun nonLatinLettersAreKept() = assertEquals("КИНО", PlaylistMark.textOf("кино тв"))

    @Test
    fun emptyNameGivesEmptyMark() = assertEquals("", PlaylistMark.textOf(" _ "))

    // --- colour ---

    @Test
    fun colourFollowsPositionAndWraps() {
        assertNotEquals(PlaylistMark.of("A", 0).argb, PlaylistMark.of("A", 1).argb)
        assertEquals(PlaylistMark.of("A", 0).argb, PlaylistMark.of("B", 6).argb)
    }
}
