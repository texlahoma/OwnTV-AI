package tv.own.owntv.features.aisubtitles

import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * THE fork seam registry. Every patch site this fork sews into upstream code is one line of
 * [MANIFEST] — id, repo, file, required marker — and this one test checks them ALL. An upstream
 * merge that rewrites a file and drops a patch site fails here, naming the manifest id, instead
 * of failing on somebody's television a week later.
 *
 * This replaces per-bug audits as the registry layer: a new patch site is ADDED to the manifest,
 * never a new bespoke test. The deeper seam tests (reachability enumerations, ordering, backup
 * symmetry) stay and hold their logic; the manifest holds existence.
 */
class SeamManifestTest {

    private fun manifestFile(): File {
        val candidates = listOf("tools/seam_manifest.tsv", "../tools/seam_manifest.tsv", "../../tools/seam_manifest.tsv")
        return candidates.map { File(it) }.firstOrNull { it.isFile }
            ?: error("seam_manifest.tsv not found next to the app module (cwd=${File(".").absolutePath})")
    }

    private fun coreRoot(): File =
        listOf("../OwnTV_Core", "../../OwnTV_Core").map { File(it) }.firstOrNull { it.isDirectory }
            ?: error("OwnTV_Core checkout not found next to the app module")

    @Test
    fun `every seam in the manifest is wired`() {
        val lines = manifestFile().readLines()
            .filter { it.isNotBlank() && !it.startsWith("#") }
        assertTrue("the seam manifest must not be empty", lines.size >= 10)
        val broken = mutableListOf<String>()
        for (line in lines) {
            val parts = line.split('\t').map { it.trim() }.filter { it.isNotEmpty() }
            if (parts.size != 4) {
                broken += "${parts.firstOrNull() ?: line.take(20)} — malformed line (needs id, repo, path, marker)"
                continue
            }
            val (repo, id, path, marker) = parts
            val root = when (repo) {
                "app" -> File("src/main/java/tv/own/owntv")
                "core" -> File(coreRoot(), "core/src/main/java/tv/own/owntv")
                "player" -> File(coreRoot(), "player-core/src/main/java/tv/own/owntv")
                else -> null
            }
            val file = root?.resolve(path)
            val text = file?.takeIf { it.isFile }?.readText()
            when {
                root == null -> broken += "$id — unknown repo '$repo'"
                text == null -> broken += "$id — file missing: $file"
                !text.contains(marker) -> broken += id
            }
        }
        assertTrue(
            if (broken.size == 1) "unwired seam: ${broken[0]}" else "unwired seams:\n  ${broken.joinToString("\n  ")}",
            broken.isEmpty(),
        )
    }
}
