package tv.own.owntv.features.home

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class HomeRulesTest {

    // --- Keep watching stills: TMDB backdrop → provider backdrop → poster → channel logo ---

    @Test
    fun tmdbBackdropWins() = assertEquals(HomeStill.Picture("tmdb"), homeStill("tmdb", "prov", "poster", null))

    @Test
    fun providerBackdropWhenNoTmdb() = assertEquals(HomeStill.Picture("prov"), homeStill(null, "prov", "poster", null))

    @Test
    fun posterIsCroppedWhenNoBackdrop() = assertEquals(HomeStill.Picture("poster"), homeStill(" ", null, "poster", null))

    @Test
    fun channelShowsItsLogo() = assertEquals(HomeStill.Logo("logo"), homeStill(null, null, null, "logo"))

    @Test
    fun nothingAtAll() = assertEquals(HomeStill.None, homeStill(null, "", null, ""))

    // --- the Trending pager ---

    @Test
    fun rightGoesToNext() = assertEquals(3, trendingPagerTarget(2, 6, 1))

    @Test
    fun rightOnLastWraps() = assertEquals(0, trendingPagerTarget(5, 6, 1))

    @Test
    fun leftGoesToPrevious() = assertEquals(1, trendingPagerTarget(2, 6, -1))

    @Test
    fun leftOnFirstIsNotThePagers() = assertNull(trendingPagerTarget(0, 6, -1))

    @Test
    fun singleTitleNeverPages() = assertNull(trendingPagerTarget(0, 1, 1))
}
