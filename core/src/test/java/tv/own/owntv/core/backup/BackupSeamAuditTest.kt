package tv.own.owntv.core.backup

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * The seams this fork sews into upstream's backup machinery, held by source-scanning the way
 * [BackupV25SportsTest] holds its block names. Each of these broke silently once — during the
 * core-1.0.71 merge — and compiling cleanly did not catch it: the checks below are what hold
 * them now.
 */
class BackupSeamAuditTest {

    private fun read(path: String): String {
        val file = File(path)
        assertTrue("expected to run from the core module, cwd=${File(".").absolutePath}", file.isFile)
        return file.readText()
    }

    private val backupManager: String by lazy { read("src/main/java/tv/own/owntv/core/backup/BackupManager.kt") }
    private val database: String by lazy { read("src/main/java/tv/own/owntv/core/database/OwnTVDatabase.kt") }
    private val dataModule: String by lazy { read("src/main/java/tv/own/owntv/core/di/DataModule.kt") }

    /**
     * The AI-translation config rides a standalone section, and a restore must apply it ONLY when
     * the user ticked it. The import call once ran unguarded — every other section gates on the
     * ticked set, and this one must keep doing the same.
     */
    @Test
    fun `aiTranslation restore is gated by the ticked sections`() {
        val gate = backupManager.indexOf("Section.AI_TRANSLATION in sections")
        val apply = backupManager.indexOf("extraSettings?.importJson")
        assertTrue("the sections gate is missing from BackupManager.kt", gate >= 0)
        assertTrue("the AI import must run inside the gate (gate before apply)", apply > gate)
    }

    /**
     * A `${'$'}` in a main-source string template renders the literal text "${…}" instead of the
     * value — a log that printed garbage for months. Escaping is legitimate in test fixtures that
     * embed DASH/HLS template dollars, never in shipped code.
     */
    @Test
    fun `no escaped-dollar templates in main sources`() {
        val offenders = File("src/main").walkTopDown()
            .filter { it.isFile && it.extension == "kt" }
            .filter { it.readText().contains("\${'\$'}") }
            .map { it.path }
            .toList()
        assertEquals("escaped-dollar template leaked into main sources", emptyList<String>(), offenders)
    }

    /**
     * The AI-subtitle memory columns exist only through their migration: an ALTER TABLE that is
     * written but not registered leaves every updating device on a broken schema, and the compile
     * cannot see the difference.
     */
    @Test
    fun `migration 53_54 is defined, registered and the version matches`() {
        assertTrue("MIGRATION_53_54 must be defined", database.contains("val MIGRATION_53_54 ="))
        assertTrue("MIGRATION_53_54 must be listed in the migrations array", database.contains("MIGRATION_53_54,"))
        assertTrue("database version must be 54", database.contains("version = 54,"))
    }

    /**
     * The app's AI-subtitle config reaches BackupManager through an optional hook resolved with
     * getOrNull in Koin. If the constructor stops taking it, or the module stops resolving it, the
     * AI section silently exports/restores nothing.
     */
    @Test
    fun `BackupManager keeps the optional extraSettings hook wired in Koin`() {
        assertTrue("BackupManager must accept extraSettings", backupManager.contains("extraSettings: BackupExtraSettings?"))
        val ctor = dataModule.indexOf("BackupManager(")
        assertTrue("DataModule must construct BackupManager", ctor >= 0)
        // The constructor's argument list runs to the end of its single { ... } block.
        val body = dataModule.substring(ctor, dataModule.indexOf("\n    }", ctor))
        assertTrue("BackupManager construction must resolve extraSettings with getOrNull()", body.contains("getOrNull()"))
    }
}
