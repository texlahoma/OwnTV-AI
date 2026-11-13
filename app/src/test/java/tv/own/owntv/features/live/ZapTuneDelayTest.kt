package tv.own.owntv.features.live

import org.junit.Assert.assertEquals
import org.junit.Test

/** CH+/- timing: an isolated press opens at once, a run of presses still resolves to at most two opens. */
class ZapTuneDelayTest {

    @Test
    fun `the first press of a session opens at once`() {
        assertEquals(0L, zapTuneDelayMs(lastPressMs = null, nowMs = 10_000))
    }

    @Test
    fun `a press after a quiet spell opens at once`() {
        assertEquals(0L, zapTuneDelayMs(lastPressMs = 10_000, nowMs = 10_000 + ZAP_ISOLATED_PRESS_MS))
        assertEquals(0L, zapTuneDelayMs(lastPressMs = 10_000, nowMs = 60_000))
    }

    @Test
    fun `a press close behind another keeps the wait`() {
        assertEquals(ZAP_TUNE_DELAY_MS, zapTuneDelayMs(lastPressMs = 10_000, nowMs = 10_300))
        assertEquals(ZAP_TUNE_DELAY_MS, zapTuneDelayMs(lastPressMs = 10_000, nowMs = 10_000 + ZAP_ISOLATED_PRESS_MS - 1))
    }

    @Test
    fun `a clock that went backwards counts as isolated`() {
        assertEquals(0L, zapTuneDelayMs(lastPressMs = 10_000, nowMs = 9_000))
    }

    @Test
    fun `a held key opens the first step and the last, never every step`() {
        // Thirteen steps 170 ms apart, as measured on a real remote.
        assertEquals(2, streamOpens(pressesEvery(170, count = 13)))
    }

    @Test
    fun `a held key on a single-connection panel still opens at most two streams`() {
        // The burst that once locked a max_connections=1 account: thirteen opens in 3.6 s. The rule has no
        // per-panel branch, so this is the same simulation the reasons below report as single-connection.
        val presses = pressesEvery(170, count = 22) // ~3.6 s of key repeat
        assertEquals(2, streamOpens(presses))
        assertEquals("isolated (single-connection)", zapWaitReason(null, presses.first(), singleConnection = true))
        presses.zipWithNext().forEach { (before, now) ->
            assertEquals(
                "rapid (previous press 170ms ago) (single-connection)",
                zapWaitReason(before, now, singleConnection = true),
            )
        }
    }

    @Test
    fun `quick taps then a pause then a tap open three streams, the last at once`() {
        val presses = listOf(0L, 300L, 600L, 600L + 2_000L)
        assertEquals(3, streamOpens(presses))
    }

    @Test
    fun `the isolation window is several key repeats long`() {
        assertEquals(1_500L, ZAP_ISOLATED_PRESS_MS)
        assertEquals(500L, ZAP_TUNE_DELAY_MS)
    }

    @Test
    fun `the logged reason tells isolated from rapid presses`() {
        assertEquals("isolated", zapWaitReason(lastPressMs = 1_000, nowMs = 10_000, singleConnection = false))
        assertEquals("isolated (single-connection)", zapWaitReason(lastPressMs = 1_000, nowMs = 10_000, singleConnection = true))
        assertEquals(
            "rapid (previous press 400ms ago)",
            zapWaitReason(lastPressMs = 9_600, nowMs = 10_000, singleConnection = false),
        )
    }

    private fun pressesEvery(gapMs: Long, count: Int, startMs: Long = 100_000L): List<Long> =
        List(count) { startMs + it * gapMs }

    /**
     * How many streams a run of presses opens, the way [LiveViewModel.zapTo] schedules them: a press with
     * no wait opens at once; a press with a wait opens [ZAP_TUNE_DELAY_MS] later unless the next press
     * arrives first and cancels it.
     */
    private fun streamOpens(pressTimes: List<Long>): Int {
        var opens = 0
        var last: Long? = null
        var pendingAt: Long? = null
        for (now in pressTimes) {
            if (pendingAt != null && pendingAt <= now) opens++ // the earlier delayed open already ran
            pendingAt = null // this press cancels a delayed open still waiting
            val delay = zapTuneDelayMs(last, now)
            if (delay == 0L) opens++ else pendingAt = now + delay
            last = now
        }
        if (pendingAt != null) opens++
        return opens
    }
}
