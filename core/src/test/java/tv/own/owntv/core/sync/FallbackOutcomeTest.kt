package tv.own.owntv.core.sync

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * What the Xtream per-category fallback is allowed to prune. Before this the fallback
 * never pruned at all, so titles removed by the provider stayed forever on any panel that can't serve
 * the bulk list; the risk in fixing it is deleting rows the pass never actually saw.
 *
 * The prune is scoped to the categories fetched *completely*, never the whole source — an item with
 * no category can't be returned by a per-category request, so a source-wide prune here would delete
 * every uncategorized row.
 */
class FallbackOutcomeTest {

    private val ids = mapOf("c1" to 11L, "c2" to 22L, "c3" to 33L)

    @Test
    fun `all categories succeeded - complete, every category in scope`() {
        val outcome = FallbackOutcome(listOf("c1", "c2", "c3"), aborted = false, stoppedEarly = false, attempted = 3)

        assertTrue(outcome.complete)
        assertEquals(listOf(11L, 22L, 33L), outcome.pruneScope(ids))
    }

    @Test
    fun `a failed category is not complete and is left out of the prune scope`() {
        // c2 returned HTTP 512 and was skipped.
        val outcome = FallbackOutcome(listOf("c1", "c3"), aborted = false, stoppedEarly = false, attempted = 3)

        assertFalse(outcome.complete)
        // c2's rows are untouched; c1's and c3's are still cleaned up.
        assertEquals(listOf(11L, 33L), outcome.pruneScope(ids))
    }

    @Test
    fun `an aborted pass prunes nothing`() {
        // Network died partway: nothing completed, so nothing may be deleted.
        val outcome = FallbackOutcome(emptyList(), aborted = true, stoppedEarly = false, attempted = 3)

        assertFalse(outcome.complete)
        assertTrue(outcome.pruneScope(ids).isEmpty())
    }

    @Test
    fun `a pass stopped early is incomplete and only prunes what it finished`() {
        // Panel ignores category_id and kept truncating, so the fallback bailed after c1.
        val outcome = FallbackOutcome(listOf("c1"), aborted = false, stoppedEarly = true, attempted = 3)

        assertFalse(outcome.complete)
        assertEquals(listOf(11L), outcome.pruneScope(ids))
    }

    @Test
    fun `a category with no local id maps to nothing rather than widening the scope`() {
        val outcome = FallbackOutcome(listOf("c1", "unknown"), aborted = false, stoppedEarly = false, attempted = 2)

        assertEquals(listOf(11L), outcome.pruneScope(ids))
    }

    @Test
    fun `every category failed - scope is empty, so the caller skips the prune entirely`() {
        val outcome = FallbackOutcome(emptyList(), aborted = false, stoppedEarly = false, attempted = 3)

        assertFalse(outcome.complete)
        assertTrue(outcome.pruneScope(ids).isEmpty())
    }
}
