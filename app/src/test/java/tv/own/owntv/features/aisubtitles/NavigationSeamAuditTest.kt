package tv.own.owntv.features.aisubtitles

import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * Navigation seams, held structurally. The first generation of seam audits checked that a
 * component EXISTS in the source; three shipped bugs were all of the form "exists but can never
 * be reached" — a settings row parked in a group slice whose page never renders it, a HUD dialog
 * wired only to a hidden remote shortcut, and a canonical control that an exhaustive `when` can
 * legally dump into `-> Unit`. Presence checks cannot see any of that. These checks hold the
 * wiring itself: for every registration there must be a rendering, and for every dialog there
 * must be a button.
 */
class NavigationSeamAuditTest {

    private fun readApp(path: String): String =
        File("src/main/java/tv/own/owntv/$path").readText()

    private fun readCore(relative: String): String =
        listOf("../OwnTV_Core", "../../OwnTV_Core")
            .map { File("$it/core/src/main/java/tv/own/owntv/$relative") }
            .firstOrNull { it.isFile }?.readText()
            ?: error("core source not found next to the app module: $relative")

    private val settingsScreen: String by lazy { readApp("features/shell/components/SettingsScreen.kt") }
    private val moreScreen: String by lazy { readApp("features/more/MoreScreen.kt") }
    private val hudChrome: String by lazy { readApp("player/PlayerHudChrome.kt") }
    private val playerHud: String by lazy { readApp("player/PlayerHud.kt") }
    private val playerControls: String by lazy { readCore("core/player/PlayerControls.kt") }

    /**
     * Every Settings sub-screen must be openable from the settings UI itself, not only through
     * search: each tab needs at least one `open(SettingsTab.X)` call site in SettingsScreen.
     * A tab reachable by nothing is a page users are never told about.
     *
     * BACKUP and LOCAL_SYNC are the deliberate exception: they are places, not preferences, and
     * the More screen hosts them directly — the audit then holds THAT instead.
     */
    @Test
    fun `every settings tab has an open site`() {
        val tabs = Regex("""private enum class SettingsTab \{([^}]+)\}""").find(settingsScreen)
            ?.groupValues?.get(1)
            ?.split(',')?.map { it.trim() }
            ?.filter { it.isNotEmpty() && it != "ROOT" }
            ?: error("SettingsTab enum not found in SettingsScreen.kt")
        val hostedByMore = setOf("BACKUP", "LOCAL_SYNC")
        assertTrue(
            "More screen must host BackupScreen and LocalSyncScreen directly",
            moreScreen.contains("BackupScreen(") && moreScreen.contains("LocalSyncScreen("),
        )
        val dead = tabs.filter { tab ->
            tab !in hostedByMore && !settingsScreen.contains("open(SettingsTab.$tab)")
        }
        assertTrue("settings tabs with no way to open them: $dead", dead.isEmpty())
    }

    /**
     * A group page that bypasses the root slice (the video-player groups render their own rows)
     * must still render the slice rows parked under it. The WATCHING page once dropped the
     * AI-subtitles row exactly this way: the row existed, its group existed, the page never drew
     * it — and search was the only door.
     */
    @Test
    fun `watching group page renders its slice rows before the player sections`() {
        val videoBranch = settingsScreen.substringAfter("group.video != null -> {")
            .substringBefore("group == SettingsGroup.PROFILE")
        assertTrue(
            "the video group branch must render the WATCHING slice rows themselves",
            videoBranch.contains("if (group == SettingsGroup.WATCHING) selectedRows.forEachIndexed"),
        )
        // ...and they must come first, above the player sections, or the row hides below the fold.
        val playerRows = videoBranch.indexOf("VideoPlayerGroupRows(")
        val sliceRows = videoBranch.indexOf("selectedRows.forEachIndexed")
        assertTrue("WATCHING slice rows must render above the player sections", sliceRows in 0 until playerRows)
    }

    /**
     * Every HUD dialog must be openable by a visible button in the bottom bar. The AI-subtitle
     * panel once lived only behind a hidden remote shortcut: the dialog existed, the panel
     * existed, and not one button led to either.
     */
    @Test
    fun `every hud dialog has a button in the bottom bar`() {
        val variants = Regex("""enum class HudDialog \{([^}]+)\}""").find(playerHud)
            ?.groupValues?.get(1)
            ?.split(',')?.map { it.trim() }
            ?.filter { it.isNotEmpty() && it != "NONE" }
            ?: error("HudDialog enum not found in PlayerHud.kt")
        // JUMP_BACK and SLEEP_TIMER open through their own callbacks rather than onOpenDialog;
        // everything else must be reachable straight from the bar.
        val throughCallback = setOf("JUMP_BACK", "SLEEP_TIMER")
        val unreachable = variants.filter { v ->
            v !in throughCallback && !hudChrome.contains("onOpenDialog(HudDialog.$v)")
        }
        assertTrue("HUD dialogs no visible button opens: $unreachable", unreachable.isEmpty())
    }

    /**
     * Every television-visible control must have a real branch in the bottom bar. The exhaustive
     * `when` only forces SOME branch — `-> Unit` is a branch, and it is how a control can join
     * the canonical list while staying invisible. MOBILE-only controls are legitimately absent;
     * MATCH is deliberately TV-hidden and asserts as an explicit `-> Unit` branch.
     */
    @Test
    fun `every on-tv control is rendered by the bottom bar`() {
        val entries = Regex("""^    ([A-Z_]+)\(ControlCluster\.[A-Z]+, ControlHost\.([A-Z_]+),""", RegexOption.MULTILINE)
            .findAll(playerControls)
            .map { it.groupValues[1] to it.groupValues[2] }
            .toList()
        assertTrue("PlayerControl enum not parsed from PlayerControls.kt", entries.size >= 10)
        val invisible = entries.filter { (name, host) ->
            host != "MOBILE_ONLY" && !hudChrome.contains("PlayerControl.$name ->")
        }
        assertTrue("controls on TV with no rendering branch: $invisible", invisible.isEmpty())
    }
}
