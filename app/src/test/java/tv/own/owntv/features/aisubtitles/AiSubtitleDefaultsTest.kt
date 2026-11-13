package tv.own.owntv.features.aisubtitles

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** Pins the live hold-back's contract: off, or a value inside the band the pipeline can use. */
class AiSubtitleDefaultsTest {

    @Test
    fun `a hold-back is either off or inside the useful band`() {
        assertEquals(AiSubtitleDefaults.LIVE_DELAY_OFF, AiSubtitleDefaults.clampLiveDelay(-3))
        assertEquals(AiSubtitleDefaults.LIVE_DELAY_OFF, AiSubtitleDefaults.clampLiveDelay(0))
        assertEquals(AiSubtitleDefaults.LIVE_DELAY_MIN, AiSubtitleDefaults.clampLiveDelay(1))
        assertEquals(5, AiSubtitleDefaults.clampLiveDelay(5))
        assertEquals(9, AiSubtitleDefaults.clampLiveDelay(9))
        assertEquals(AiSubtitleDefaults.LIVE_DELAY_MAX, AiSubtitleDefaults.clampLiveDelay(42))
    }

    @Test
    fun `the offered choices stay inside the band`() {
        assertTrue(
            AiSubtitleDefaults.LIVE_DELAY_CHOICES.all {
                it == AiSubtitleDefaults.LIVE_DELAY_OFF ||
                    it in AiSubtitleDefaults.LIVE_DELAY_MIN..AiSubtitleDefaults.LIVE_DELAY_MAX
            },
        )
        // The default is 12 s: comfortably fits decode plus round trips inside the 20 s band the
        // pipeline allows, while staying short enough that zapping still feels live.
        assertEquals(12, AiSubtitleDefaults.LIVE_DELAY_DEFAULT)
    }
}
