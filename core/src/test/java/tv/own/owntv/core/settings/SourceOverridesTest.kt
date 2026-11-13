package tv.own.owntv.core.settings

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import tv.own.owntv.core.database.entity.SourceEntity
import tv.own.owntv.core.model.SourceType
import tv.own.owntv.core.network.StreamHeaders
import tv.own.owntv.core.player.EnginePreference

class SourceOverridesTest {

    private fun source(
        tz: String? = null, offset: Int? = null, vod: String? = null, timeout: Int? = null, referer: String? = null,
    ) = SourceEntity(
        name = "p", type = SourceType.M3U, url = "http://x",
        catchupTimezone = tz, catchupOffsetMin = offset, vodEnginePreference = vod,
        liveTuneTimeoutSecs = timeout, httpReferer = referer,
    )

    @Test fun nullColumnsFollowTheGlobalSetting() {
        val s = source()
        assertNull(SourceOverrides.catchupTimeZoneOf(s))
        assertNull(SourceOverrides.vodEngineOf(s))
        assertNull(SourceOverrides.liveTuneTimeoutSecsOf(s))
        assertEquals("Cookie: a", SourceOverrides.headersWithReferer("Cookie: a", s))
        assertNull(SourceOverrides.catchupTimeZoneOf(null))
    }

    @Test fun manualCatchupZoneUsesTheOffset() {
        val tz = SourceOverrides.catchupTimeZoneOf(source(tz = "MANUAL", offset = 90))!!
        assertEquals(90 * 60_000, tz.rawOffset)
    }

    /** Automatic is the panel's reported zone when there is one, else the device's. */
    @Test fun automaticCatchupZoneUsesThePanelZone() {
        val panel = source(tz = "AUTOMATIC").copy(serverTimeZone = "Asia/Kolkata")
        assertEquals("Asia/Kolkata", SourceOverrides.catchupTimeZoneOf(panel)!!.id)
        assertEquals("Asia/Kolkata", SourceOverrides.panelTimeZoneOf(panel)!!.id)
        assertEquals(java.util.TimeZone.getDefault().id, SourceOverrides.catchupTimeZoneOf(source(tz = "AUTOMATIC"))!!.id)
        assertNull(SourceOverrides.panelTimeZoneOf(source()))
        assertEquals(60 * 60_000, SourceOverrides.panelTimeZoneOf(source().copy(serverTimeZone = "GMT+01:00"))!!.rawOffset)
    }

    @Test fun unknownNamesReadAsNoOverride() {
        assertNull(SourceOverrides.catchupTimeZoneOf(source(tz = "MARS")))
        assertNull(SourceOverrides.vodEngineOf(source(vod = "VLC")))
    }

    @Test fun vodEngineAndTimeoutParse() {
        assertEquals(EnginePreference.EXO_ONLY, SourceOverrides.vodEngineOf(source(vod = "EXO_ONLY")))
        assertEquals(0, SourceOverrides.liveTuneTimeoutSecsOf(source(timeout = 0)))
        assertEquals(60, SourceOverrides.liveTuneTimeoutSecsOf(source(timeout = 999)))
    }

    @Test fun refererIsAddedButAStreamsOwnWins() {
        val merged = StreamHeaders.decode(SourceOverrides.headersWithReferer("Cookie: a", source(referer = " https://r/ ")))
        assertEquals(mapOf("Cookie" to "a", "Referer" to "https://r/"), merged)
        assertEquals("Referer: https://own", SourceOverrides.headersWithReferer("Referer: https://own", source(referer = "https://r/")))
        assertEquals("Referer: https://r/", SourceOverrides.headersWithReferer(null, source(referer = "https://r/")))
        assertNull(SourceOverrides.headersWithReferer(null, source(referer = "  ")))
    }
}
