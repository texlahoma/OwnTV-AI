package tv.own.owntv.core.content

import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test
import tv.own.owntv.core.database.entity.MovieEntity
import tv.own.owntv.core.database.entity.SeriesEntity

/** The search filter buttons: each kind switched on and off on its own, none on meaning everything. */
class SearchKindTest {

    private val results = SearchResults(
        movies = listOf(MovieEntity(id = 1, sourceId = 1, name = "Heat", streamUrl = "u1"), MovieEntity(id = 2, sourceId = 1, name = "Ran", streamUrl = "u2")),
        series = listOf(SeriesEntity(id = 3, sourceId = 1, name = "Dark")),
    )

    @Test
    fun noKindPicked_isEverything() {
        assertSame(results, results.only(emptySet()))
    }

    @Test
    fun oneKind_keepsOnlyThatKind() {
        val movies = results.only(setOf(SearchKind.MOVIES))
        assertEquals(results.movies, movies.movies)
        assertTrue(movies.series.isEmpty())
    }

    @Test
    fun severalKinds_keepEachOfThem() {
        val both = results.only(setOf(SearchKind.MOVIES, SearchKind.SERIES))
        assertEquals(2, both.count(SearchKind.MOVIES))
        assertEquals(1, both.count(SearchKind.SERIES))
    }

    @Test
    fun aKindWithNothingFound_leavesNothing() {
        assertTrue(results.only(setOf(SearchKind.TV_SHOWS)).isEmpty)
    }
}
