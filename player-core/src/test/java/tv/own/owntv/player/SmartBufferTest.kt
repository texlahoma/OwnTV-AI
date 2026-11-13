package tv.own.owntv.player

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import tv.own.owntv.core.player.PlayerBudget

/** The Film buffer reaching memory: Auto and live untouched, a choice bounded by tier and ceiling. */
class SmartBufferTest {

    private val mib = 1024L * 1024L

    /** A heap class whose quarter (128 MiB) never binds below the low tier's 96 MiB film ceiling. */
    private val roomyHeapMb = 512

    // The three tiers exactly as PlayerBudget.compute builds them.
    private val low = PlayerBudget("48MiB", "16MiB", "10", "30", lowSpec = true, filmCeilingBytes = PlayerBudget.LOW_FILM_CEILING_BYTES)
    private val mid = PlayerBudget("128MiB", "32MiB", "30", "60", lowSpec = false, filmCeilingBytes = PlayerBudget.MID_FILM_CEILING_BYTES)
    private val high = PlayerBudget("256MiB", "64MiB", "60", "120", lowSpec = false, filmCeilingBytes = PlayerBudget.HIGH_FILM_CEILING_BYTES)

    /** A caller that builds a budget without the new field: no raise at all. */
    private val legacy = PlayerBudget("48MiB", "16MiB", readaheadSecs = "10", cacheSecs = "30", lowSpec = true)

    private fun mpvFilm(budget: PlayerBudget, secs: Int, bps: Long? = null, trimmed: Boolean = false) =
        SmartBuffer.mpvDemuxerMaxBytes(isLive = false, userSecs = secs, budget = budget, bitrateBps = bps, trimmed = trimmed)

    // --- Auto -------------------------------------------------------------------------------------

    @Test
    fun `Auto keeps every tier's mpv cache exactly`() {
        assertEquals("48MiB", mpvFilm(low, 0))
        assertEquals("128MiB", mpvFilm(mid, 0))
        assertEquals("256MiB", mpvFilm(high, 0))
    }

    @Test
    fun `Auto ignores even a known bitrate`() {
        assertEquals("48MiB", mpvFilm(low, 0, bps = 25_000_000))
        assertEquals(16 * mib, SmartBuffer.filmBytes(0, 16 * mib, 30, 96 * mib, 25_000_000))
    }

    @Test
    fun `Auto keeps ExoPlayer's tier target exactly`() {
        assertEquals((16 * mib).toInt(), SmartBuffer.exoTargetBytes(0, low, (16 * mib).toInt(), 30, null, roomyHeapMb))
        assertEquals((48 * mib).toInt(), SmartBuffer.exoTargetBytes(0, mid, (48 * mib).toInt(), 60, 40_000_000, roomyHeapMb))
    }

    // --- Live -------------------------------------------------------------------------------------

    @Test
    fun `live keeps the tier's cache whatever the film buffer says`() {
        for (b in listOf(low, mid, high)) {
            assertEquals(b.demuxerMaxBytes, SmartBuffer.mpvDemuxerMaxBytes(true, 300, b, 25_000_000, trimmed = false))
            assertEquals(b.demuxerMaxBytes, SmartBuffer.mpvDemuxerMaxBytes(true, 0, b, null, trimmed = false))
        }
    }

    // --- Tier ceilings ----------------------------------------------------------------------------

    @Test
    fun `a long buffer reaches each tier's film ceiling and no further`() {
        assertEquals("96MiB", mpvFilm(low, 300))
        assertEquals("256MiB", mpvFilm(mid, 300))
        assertEquals("512MiB", mpvFilm(high, 300))
        assertEquals("96MiB", mpvFilm(low, 300, bps = 80_000_000))
    }

    @Test
    fun `ExoPlayer's 16 MiB low-RAM cap now follows a chosen buffer, up to the ceiling`() {
        val tier = (16 * mib).toInt()
        // 60 s on a 30 s tier: twice the tier's bytes.
        assertEquals((32 * mib).toInt(), SmartBuffer.exoTargetBytes(60, low, tier, 30, null, roomyHeapMb))
        // 300 s would be 160 MiB; the low tier's film ceiling holds it at 96.
        assertEquals((96 * mib).toInt(), SmartBuffer.exoTargetBytes(300, low, tier, 30, null, roomyHeapMb))
    }

    @Test
    fun `ExoPlayer's heap cap is a quarter of the heap class`() {
        assertEquals(32 * mib, SmartBuffer.exoHeapCapBytes(128))
        assertEquals(48 * mib, SmartBuffer.exoHeapCapBytes(192))
        assertEquals(64 * mib, SmartBuffer.exoHeapCapBytes(256))
        assertEquals(96 * mib, SmartBuffer.exoHeapCapBytes(384))
        assertEquals(0L, SmartBuffer.exoHeapCapBytes(0))
    }

    @Test
    fun `ExoPlayer's film buffer stops at the heap cap, below the film ceiling`() {
        // Mid tier: 48 MiB today, ceiling 256 MiB. A 192 MiB heap allows 48, so 300 s stays at 48;
        // a 384 MiB heap allows 96.
        val tier = (48 * mib).toInt()
        assertEquals(tier, SmartBuffer.exoTargetBytes(300, mid, tier, 60, null, heapClassMb = 192))
        assertEquals((96 * mib).toInt(), SmartBuffer.exoTargetBytes(300, mid, tier, 60, null, heapClassMb = 384))
        // Low tier with a 192 MiB heap: 300 s would be 160 MiB, the ceiling 96, the heap 48.
        assertEquals((48 * mib).toInt(), SmartBuffer.exoTargetBytes(300, low, (16 * mib).toInt(), 30, null, heapClassMb = 192))
    }

    @Test
    fun `a heap cap below the tier's value leaves the tier's value`() {
        val tier = (72 * mib).toInt()
        assertEquals(tier, SmartBuffer.exoTargetBytes(300, high, tier, 120, 40_000_000, heapClassMb = 128))
        // Unknown heap class: no raise at all.
        assertEquals(tier, SmartBuffer.exoTargetBytes(300, high, tier, 120, null, heapClassMb = 0))
    }

    @Test
    fun `the heap cap leaves mpv's native ceilings alone`() {
        assertEquals("256MiB", mpvFilm(mid, 300))
        assertEquals("96MiB", mpvFilm(low, 300))
    }

    @Test
    fun `a choice never shrinks today's cap`() {
        // 30 s chosen on a 60 s tier, and a 1 Mbps film: both would want less than the tier.
        assertEquals("128MiB", mpvFilm(mid, 30))
        assertEquals("128MiB", mpvFilm(mid, 120, bps = 1_000_000))
    }

    @Test
    fun `a budget without a film ceiling never raises anything`() {
        assertEquals("48MiB", mpvFilm(legacy, 300))
        assertEquals("48MiB", mpvFilm(legacy, 300, bps = 25_000_000))
    }

    // --- Bitrate known / unknown ------------------------------------------------------------------

    @Test
    fun `unknown bitrate scales the tier by the chosen seconds`() {
        assertEquals("96MiB", mpvFilm(low, 60))   // 48 MiB × 60/30
        assertEquals("256MiB", mpvFilm(mid, 120)) // 128 MiB × 120/60
    }

    @Test
    fun `a known bitrate sizes the cap to the film`() {
        // 8 Mbps × 120 s × 1.25 = 150,000,000 B → 144 MiB (rounded up), inside the mid tier's 128..256.
        assertEquals("144MiB", mpvFilm(mid, 120, bps = 8_000_000))
    }

    @Test
    fun `an implausible bitrate counts as unknown`() {
        assertEquals(mpvFilm(mid, 120), mpvFilm(mid, 120, bps = 50))
        assertEquals(mpvFilm(mid, 120), mpvFilm(mid, 120, bps = 5_000_000_000))
        assertEquals(mpvFilm(mid, 120), mpvFilm(mid, 120, bps = -1))
    }

    @Test
    fun `passive measurement needs enough bytes and enough media`() {
        assertNull(SmartBuffer.mediaBitrateBps(bytes = 500_000, mediaMs = 60_000))
        assertNull(SmartBuffer.mediaBitrateBps(bytes = 50 * mib, mediaMs = 5_000))
        assertNull(SmartBuffer.mediaBitrateBps(bytes = 50 * mib, mediaMs = -1))
        // 15 MB over 20 s of media = 6 Mbps.
        assertEquals(6_000_000L, SmartBuffer.mediaBitrateBps(bytes = 15_000_000, mediaMs = 20_000))
    }

    // --- Low-memory trim --------------------------------------------------------------------------

    @Test
    fun `the low-memory trim wins over every choice`() {
        assertEquals(PlayerBudget.TRIM_DEMUXER_BYTES, mpvFilm(high, 300, bps = 25_000_000, trimmed = true))
        assertEquals(PlayerBudget.TRIM_DEMUXER_BYTES, mpvFilm(low, 0, trimmed = true))
        assertEquals(PlayerBudget.TRIM_DEMUXER_BYTES, SmartBuffer.mpvDemuxerMaxBytes(true, 0, mid, null, trimmed = true))
    }

    // --- mpv byte notation ------------------------------------------------------------------------

    @Test
    fun `mpv byte sizes parse and format`() {
        assertEquals(48 * mib, SmartBuffer.parseMpvBytes("48MiB"))
        assertEquals(512 * 1024L, SmartBuffer.parseMpvBytes("512KiB"))
        assertEquals(1024 * mib, SmartBuffer.parseMpvBytes("1GiB"))
        assertEquals(1_048_576L, SmartBuffer.parseMpvBytes("1048576"))
        assertNull(SmartBuffer.parseMpvBytes("lots"))
        assertNull(SmartBuffer.parseMpvBytes("48MB"))
        assertEquals("2MiB", SmartBuffer.formatMpvBytes(mib + 1))
        assertEquals("1MiB", SmartBuffer.formatMpvBytes(mib))
    }

    @Test
    fun `an unparseable tier cache is passed through untouched`() {
        val odd = PlayerBudget("lots", "16MiB", "10", "30", lowSpec = true, filmCeilingBytes = 96 * mib)
        assertEquals("lots", mpvFilm(odd, 300))
    }
}
