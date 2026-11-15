package tv.own.owntv.player

import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * The seams this fork sews into upstream's live player, held by source scanning. Each of these
 * broke silently during the core-1.0.71 merge — the code compiled, the feature read fine, and a
 * real TV paid for it — so the wiring below is what holds them.
 */
class HoldBackSeamAuditTest {

    private val engine: String by lazy {
        val file = File("src/main/java/tv/own/owntv/player/LivePreviewEngine.kt")
        assertTrue("expected to run from the player-core module, cwd=${File(".").absolutePath}", file.isFile)
        file.readText()
    }

    /**
     * The AI-subtitle hold-back queues its whole depth in decoded audio, so the load control must
     * be ALLOWED to grow to that capacity — the merge unwired [LivePreviewEngine.effectiveLoadControlSecs]
     * from all three of its consumers, leaving the base 10 s cap in force. A 16 s caption delay then
     * asked a 10 s buffer for a queue it could never fill: the tune spun for 30 s and died in the open
     * watchdog ("open stalled: 10450ms buffered (needs 1000ms) after 30079ms") — on every channel,
     * every time, subtitles on. The capacity must reach the build, the byte budget, and the
     * staleness check that rebuilds a player when a delay arrives between tunes.
     */
    @Test
    fun `the load control honours the hold-back capacity`() {
        val buildSite = engine.contains("loadControlFor(effectiveLoadControlSecs()")
        val stalenessSite = engine.contains("effectiveLoadControlSecs() ?: effectiveLiveBufferSecs()")
        val bytesSite = engine.contains("targetBufferBytes(effectiveLoadControlSecs()")
        assertTrue(
            "the player build must size its load control from the hold-back capacity",
            buildSite,
        )
        assertTrue(
            "the staleness check must rebuild when the hold-back capacity changes between tunes",
            stalenessSite,
        )
        assertTrue(
            "the byte budget must cover the hold-back depth, not just the base buffer",
            bytesSite,
        )
    }

    /**
     * The build log must print the hold-back depth beside the buffer numbers. These lines are the
     * only release-build way to tell "the setting didn't apply" from "the buffer filled that fast" —
     * without the depth on the same line, the exact failure this file holds was diagnosed from a
     * user's export with the key number nowhere in it.
     */
    @Test
    fun `the load-control log carries the hold-back depth`() {
        val occurrences = Regex(Regex.escape("holdBack=${'$'}{effectiveHoldBackMs}ms")).findAll(engine).count()
        assertTrue(
            "both the diagnostics event and the logcat line must print holdBack= (found $occurrences)",
            occurrences >= 2,
        )
        assertTrue(
            "the hold-back depth must sit on the load_control line",
            engine.contains("load_control min=") && engine.contains("holdBack="),
        )
    }
}
