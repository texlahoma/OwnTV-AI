package tv.own.owntv.core.repository

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The tee'd XMLTV cache used to be written straight to its final name, so a download
 * cut short by a network drop left a truncated guide that was indistinguishable from a complete one
 * for the whole 24h TTL. A later smart-match reading it concluded the missing channels simply had no
 * schedule.
 *
 * The fix is a naming contract: in-flight downloads live under `.xmltv.tmp` and are renamed only
 * after a clean parse. These tests pin that contract.
 */
class EpgCacheFilesTest {

    private val ttl = 24L * 60 * 60 * 1000
    private val now = 1_784_989_800_000L

    @Test
    fun `a truncated in-flight cache is rejected rather than served`() {
        // Same store, same freshness, non-empty — the ONLY difference is that it was never promoted.
        assertFalse(isUsableEpgCache("epg_3.xmltv.tmp", 12_000, now - 1000, now, ttl))
        assertTrue(isUsableEpgCache("epg_3.xmltv", 12_000, now - 1000, now, ttl))
    }

    @Test
    fun `a complete fresh cache is served`() {
        assertTrue(isUsableEpgCache("epg_1.xmltv", 1, now, now, ttl))
        assertTrue(isUsableEpgCache("epg_42.xmltv", 9_000_000, now - (ttl - 1), now, ttl))
    }

    @Test
    fun `empty stale and foreign files are rejected`() {
        assertFalse("zero length", isUsableEpgCache("epg_1.xmltv", 0, now, now, ttl))
        assertFalse("exactly at the TTL", isUsableEpgCache("epg_1.xmltv", 10, now - ttl, now, ttl))
        assertFalse("past the TTL", isUsableEpgCache("epg_1.xmltv", 10, now - ttl - 1, now, ttl))
        assertFalse("not ours", isUsableEpgCache("other.xmltv", 10, now, now, ttl))
        assertFalse("wrong suffix", isUsableEpgCache("epg_1.xml", 10, now, now, ttl))
    }

    /** A clock change must not make a cache look eternally fresh. */
    @Test
    fun `a future timestamp counts as stale`() {
        assertFalse(isUsableEpgCache("epg_1.xmltv", 10, now + 60_000, now, ttl))
    }

    @Test
    fun `only long-abandoned temp files are swept`() {
        assertTrue(isOrphanedEpgTempCache("epg_1.xmltv.tmp", now - ttl - 1, now, ttl))
        // A temp file this fresh may belong to another source syncing right now.
        assertFalse(isOrphanedEpgTempCache("epg_1.xmltv.tmp", now - 5_000, now, ttl))
        assertFalse("promoted caches are never swept", isOrphanedEpgTempCache("epg_1.xmltv", 0, now, ttl))
        assertFalse("not ours", isOrphanedEpgTempCache("something.tmp", 0, now, ttl))
    }

    @Test
    fun `store ids parse from promoted names only`() {
        assertEquals(7L, epgCacheStoreId("epg_7.xmltv"))
        assertNull(epgCacheStoreId("epg_7.xmltv.tmp"))
        assertNull(epgCacheStoreId("epg_abc.xmltv"))
        assertNull(epgCacheStoreId("epg_.xmltv"))
        assertNull(epgCacheStoreId("backup.json"))
    }
}
