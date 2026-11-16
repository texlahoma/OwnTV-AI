package tv.own.owntv.player

import org.junit.Assert.assertEquals
import org.junit.Test
import tv.own.owntv.core.player.AudioOutputKind
import tv.own.owntv.core.player.EnginePreference
import tv.own.owntv.core.player.OutputAudioDelays
import tv.own.owntv.core.player.SurroundMode
import tv.own.owntv.core.settings.SubtitleStyle
import tv.own.owntv.core.theme.AppFontFamily

/**
 * The snapshot is built from a list of stored values by position. Two neighbours of the same type
 * swapped would compile and run, so every position is pinned to its field here.
 */
class PlaybackSettingsTest {

    private val delays = OutputAudioDelays(mapOf(AudioOutputKind.entries.first() to 40))

    /** One value per position, no two alike among the non-boolean ones; every boolean is false. */
    private fun values(): Array<Any?> = arrayOf(
        false, false, SurroundMode.SURROUND, false,
        EnginePreference.MPV_ONLY, false, 106, 107,
        false, 1.09f, AppFontFamily.MONOSPACE, "#111111",
        SubtitleStyle.Position.TOP_LEFT, 113, 114, "audio-lang",
        "sub-lang", "zoom", 118, 119, false,
        EnginePreference.EXO_FIRST, 122,
        123, 124, 125,
        false, false, false,
        129, 130, false, false,
        false, delays, false,
    )

    @Test
    fun `every value that is not a switch lands in its own field`() {
        val s = PlaybackSettings.fromValues(values())
        assertEquals(SurroundMode.SURROUND, s.surroundMode)
        assertEquals(EnginePreference.MPV_ONLY, s.vodEnginePreference)
        assertEquals(106, s.livePrerollSecs)
        assertEquals(107, s.liveBufferSeconds)
        assertEquals(1.09f, s.subtitleScaleMpv)
        assertEquals(AppFontFamily.MONOSPACE, s.subtitleFont)
        assertEquals("#111111", s.subtitleColor)
        assertEquals(SubtitleStyle.Position.TOP_LEFT, s.subtitlePosition)
        assertEquals(113, s.subtitleBgOpacity)
        assertEquals(114, s.audioDelayMs)
        assertEquals("audio-lang", s.preferredAudioLang)
        assertEquals("sub-lang", s.preferredSubLang)
        assertEquals("zoom", s.defaultZoom)
        assertEquals(118, s.defaultVolume)
        assertEquals(119, s.seekStepSec)
        assertEquals(EnginePreference.EXO_FIRST, s.liveEnginePreference)
        assertEquals(122, s.liveTuneTimeoutSecs)
        assertEquals(123, s.vodBufferSecs)
        assertEquals(124, s.vodNetworkTimeoutSecs)
        assertEquals(125, s.vodReconnects)
        assertEquals(129, s.maxVideoHeight)
        assertEquals(130, s.mobileDataMaxVideoHeight)
        assertEquals(delays, s.outputAudioDelays)
    }

    @Test
    fun `each switch turns on its own field and no other`() {
        val switches: List<Pair<Int, (PlaybackSettings) -> Boolean>> = listOf(
            0 to { it.preferSdr },
            1 to { it.hwDecoding },
            3 to { it.autoPlayNext },
            5 to { it.measuredStreamStats },
            8 to { it.subtitleStyleEnabled },
            20 to { it.autoFrameRateFilms },
            26 to { it.audioPassthrough },
            27 to { it.nightMode },
            28 to { it.volumeLevelling },
            31 to { it.tunneledPlayback },
            32 to { it.backgroundPlayback },
            33 to { it.softwareDecodeAbove1080p },
            35 to { it.autoFrameRateLive },
        )
        assertEquals("every position is covered", 36, switches.size + 23)
        for ((index, _) in switches) {
            val s = PlaybackSettings.fromValues(values().also { it[index] = true })
            assertEquals("position $index", switches.map { it.first == index }, switches.map { it.second(s) })
        }
    }
}
