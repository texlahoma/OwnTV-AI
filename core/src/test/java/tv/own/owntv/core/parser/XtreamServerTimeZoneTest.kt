package tv.own.owntv.core.parser

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/** The zone an Xtream panel's timeshift `start` is written in, from its `server_info`. */
class XtreamServerTimeZoneTest {

    // 2023-11-14 22:13:20 UTC
    private val nowSec = 1_700_000_000L

    @Test
    fun `a real zone name wins`() {
        assertEquals("Europe/London", xtreamServerTimeZone("Europe/London", "2023-11-14 23:13:20", nowSec))
        assertEquals("America/New_York", xtreamServerTimeZone(" America/New_York ", null, null))
    }

    @Test
    fun `an unknown name falls back to the clock difference`() {
        assertEquals("GMT+01:00", xtreamServerTimeZone("Mars/Olympus", "2023-11-14 23:13:20", nowSec))
        assertEquals("GMT+01:00", xtreamServerTimeZone(null, "2023-11-14 23:13:20", nowSec))
        assertEquals("GMT-05:00", xtreamServerTimeZone("", "2023-11-14 17:13:20", nowSec))
        assertEquals("GMT+05:30", xtreamServerTimeZone(null, "2023-11-15 03:43:20", nowSec))
        assertEquals("GMT+00:00", xtreamServerTimeZone(null, "2023-11-14 22:13:20", nowSec))
    }

    /** `time_now` and `timestamp_now` are written a moment apart; a few seconds must not tip the zone. */
    @Test
    fun `the clock difference is rounded to the quarter hour`() {
        assertEquals("GMT+01:00", xtreamServerTimeZone(null, "2023-11-14 23:13:27", nowSec))
        assertEquals("GMT+01:00", xtreamServerTimeZone(null, "2023-11-14 23:13:12", nowSec))
    }

    @Test
    fun `nothing usable is null`() {
        assertNull(xtreamServerTimeZone(null, null, null))
        assertNull(xtreamServerTimeZone(null, "yesterday", nowSec))
        assertNull(xtreamServerTimeZone(null, "2023-11-14 23:13:20", null))
        assertNull(xtreamServerTimeZone(null, "2023-11-14 23:13:20", 0))
        // Further apart than any zone: a broken panel clock, not a time zone.
        assertNull(xtreamServerTimeZone(null, "2023-11-16 22:13:20", nowSec))
    }
}
