package tv.own.owntv.core.sync

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The catalog-shrink guard. A provider having a bad hour (a truncated list, a panel answering
 * with a tiny payload) must not be able to delete most of a source's catalog, because every favorite,
 * history entry and resume position keyed to those rows goes with it.
 */
class SyncPruneGuardTest {

    @Test
    fun `a normal delta prunes as before`() {
        assertTrue(SyncSupport.shouldPrune(stored = 170_000, stale = 400, force = false))
        assertTrue(SyncSupport.shouldPrune(stored = 1_000, stale = 500, force = false)) // exactly at the limit
    }

    @Test
    fun `a drastic shrink on a large catalog is refused`() {
        assertFalse(SyncSupport.shouldPrune(stored = 170_000, stale = 169_900, force = false))
        assertFalse(SyncSupport.shouldPrune(stored = 1_000, stale = 501, force = false))
    }

    @Test
    fun `small and fresh sources are never blocked`() {
        assertTrue(SyncSupport.shouldPrune(stored = 100, stale = 100, force = false))
        assertTrue(SyncSupport.shouldPrune(stored = 0, stale = 0, force = false))
    }

    @Test
    fun `a force clean sync bypasses the guard`() {
        assertTrue(SyncSupport.shouldPrune(stored = 170_000, stale = 170_000, force = true))
    }

    @Test
    fun `nothing stale is trivially allowed`() {
        assertTrue(SyncSupport.shouldPrune(stored = 170_000, stale = 0, force = false))
    }
}
