package tv.own.owntv.core.nav

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class MainSectionTest {

    @Test
    fun `sports sits right after Live TV and is a browse item`() {
        val order = MainSection.browseOrder
        assertEquals(order.indexOf(MainSection.LIVE_TV) + 1, order.indexOf(MainSection.SPORTS))
        assertTrue(MainSection.SPORTS.isBrowse)
        assertTrue(MainSection.SPORTS in MainSection.allBrowse)
    }

    @Test
    fun `dynamic mode always shows sports, with or without a playlist`() {
        for (live in listOf(false, true)) for (movies in listOf(false, true)) for (series in listOf(false, true)) {
            assertTrue(MainSection.SPORTS in MainSection.dynamicVisible(live, movies, series))
        }
        assertEquals(setOf(MainSection.HOME, MainSection.SPORTS), MainSection.dynamicVisible(false, false, false))
    }

    @Test
    fun `static mode hides sports only when the user hid it`() {
        // An existing user's hidden set never names SPORTS, so the update shows it.
        assertTrue(MainSection.SPORTS in MainSection.staticVisible(setOf("MOVIES", "DOWNLOADS", "GONE")))
        assertFalse(MainSection.SPORTS in MainSection.staticVisible(setOf("SPORTS")))
    }
}
