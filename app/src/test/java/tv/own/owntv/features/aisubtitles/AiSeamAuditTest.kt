package tv.own.owntv.features.aisubtitles

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * The seams where the fork's AI-subtitle layer meets upstream's shell, held by source-scanning
 * (no Android graph needed). The core-1.0.71 merge rewrote the tune path and the Settings shell
 * around these seams and dropped them silently — compiling cleanly all the while. Each check
 * below is one seam that actually broke once.
 */
class AiSeamAuditTest {

    private fun read(path: String): String {
        val file = File(path)
        assertTrue("expected to run from the app module, cwd=${File(".").absolutePath}", file.isFile)
        return file.readText()
    }

    private val liveViewModel: String by lazy { read("src/main/java/tv/own/owntv/features/live/LiveViewModel.kt") }
    private val shell: String by lazy { read("src/main/java/tv/own/owntv/features/shell/OwnTVShell.kt") }
    private val app: String by lazy { read("src/main/java/tv/own/owntv/OwnTVApp.kt") }
    private val backupExtra: String by lazy { read("src/main/java/tv/own/owntv/features/aisubtitles/AiSubtitleBackupExtraSettings.kt") }

    /**
     * Live TV plays through LivePreviewEngine, which never writes OwnTVPlayer.currentMeta, so the
     * AI-subtitle manager learns the tuned channel ONLY through the explicit feed. The merge lost
     * the feed and per-channel memory died with it (the HUD toggle kept writing to the previous
     * channel's key).
     */
    @Test
    fun `every tune feeds the ai-subtitle manager and leaving live clears it`() {
        assertTrue(
            "tuned() must feed setActiveKey with the channel's pref key",
            liveViewModel.contains("aiSubtitles.setActiveKey(channelPrefKey(channel))"),
        )
        assertTrue(
            "stopPreview must clear the AI-subtitle channel context",
            liveViewModel.contains("aiSubtitles.setActiveKey(null)"),
        )
        assertTrue(
            "channelPrefKey must be the stable engine pin key, not the stream URL",
            liveViewModel.contains("enginePinKey("),
        )
    }

    /**
     * The generated caption is drawn by the overlay mounted in the shell, over both engines. If
     * the shell stops compositing it, the whole pipeline still "works" and shows nothing.
     */
    @Test
    fun `the shell mounts the ai-subtitle overlay`() {
        assertTrue(
            "OwnTVShell must composite AiSubtitleOverlay",
            shell.contains("AiSubtitleOverlay("),
        )
    }

    /**
     * The updater asks CoreBuildInfo.releaseRepo; the default is upstream's repo. If the fork
     * override disappears, devices silently check upstream for updates.
     */
    @Test
    fun `the updater polls this fork, not upstream`() {
        assertTrue(
            "OwnTVApp must override CoreBuildInfo.releaseRepo to this fork",
            app.contains("CoreBuildInfo.releaseRepo = \"texlahoma/OwnTV-AI\""),
        )
    }

    /**
     * Export and import must name the same JSON keys, or a field is carried out of a backup and
     * quietly dropped on restore (or vice versa). Parsed from both method bodies so the two drift
     * apart only when this test says so.
     */
    @Test
    fun `backup export and import name the same ai config keys`() {
        val exportFrom = backupExtra.indexOf("override suspend fun exportJson")
        val importFrom = backupExtra.indexOf("override suspend fun importJson")
        assertTrue(exportFrom >= 0 && importFrom > exportFrom)
        val exportBody = backupExtra.substring(exportFrom, importFrom)
        val importBody = backupExtra.substring(importFrom)
        val exported = Regex("""put\("([A-Za-z]+)""").findAll(exportBody).map { it.groupValues[1] }.toSet()
        val imported = Regex("""json\.(?:has|opt[A-Za-z]+)\("([A-Za-z]+)""").findAll(importBody)
            .map { it.groupValues[1] }.toSet()
        assertEquals("export keys the import never reads", exported - imported, emptySet<String>())
        assertEquals("import keys the export never writes", imported - exported, emptySet<String>())
    }
}
