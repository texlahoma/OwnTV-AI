package tv.own.owntv.features.aisubtitles

import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.io.File

/**
 * Pins the claim [SubtitleGlossary]'s hotword lists make about themselves: every entry must be
 * encodable by the shipped model's own BPE table.
 *
 * The encoder pushes each hotword through the model's `bpe.vocab`, and a character the table does not
 * carry becomes `<unk>` — which does not merely spoil its own entry but measurably drags down the
 * whole list, so one accented name costs every other name its correction. That is why the lists are
 * curated ASCII rather than derived from [SubtitleGlossary]'s display names. The claim is only true
 * while somebody checks it, and the lists are edited most often (a transfer window moves the player
 * block), so this test reads the *shipped* vocabulary and fails the moment an edit introduces a
 * character the decoder cannot spell — catching what would otherwise surface only as a silent loss of
 * corrections on a real broadcast.
 *
 * The vocabulary is a build input inside the APK assets, read here from the module directory (the
 * Gradle unit-test working directory). If it is moved, the test skips rather than fails so a missing
 * asset never reads as a bad word list.
 */
class HotwordBpeTest {

    private val vocab = File("src/main/assets/ai/streaming-en/bpe.vocab")

    /** The characters the model's BPE table can spell, across all of its pieces. */
    private fun vocabCharacters(): Set<Char> {
        assumeTrue("shipped bpe.vocab not found at ${vocab.path}", vocab.isFile)
        val chars = mutableSetOf<Char>()
        vocab.forEachLine { line ->
            // "<piece>\t<score>"; the piece is the whole token before the TAB.
            line.substringBefore('\t').forEach { chars += it }
        }
        return chars
    }

    @Test
    fun `no hotword carries a character the model's bpe table cannot encode`() {
        val chars = vocabCharacters()
        for (sport in SubtitleGlossary.Sport.entries) {
            for (term in SubtitleGlossary.hotwordsFor(sport)) {
                for (c in term) {
                    // A space is a word break: SentencePiece marks its position with `▁`, which the
                    // table carries, so the space itself is never a piece and needs no coverage.
                    if (c == ' ') continue
                    assertTrue(
                        "'$term' (${sport.name}) carries '$c', which bpe.vocab cannot spell: it " +
                            "would encode as <unk> and drag the whole hotword list down",
                        c in chars,
                    )
                }
            }
        }
    }

    @Test
    fun `every hotword is ascii and slash-free`() {
        // ASCII because an accented character is exactly the <unk> case above, and slash-free
        // because '/' is the separator the sherpa-onnx hotword JNI splits the list on.
        for (sport in SubtitleGlossary.Sport.entries) {
            for (term in SubtitleGlossary.hotwordsFor(sport)) {
                assertTrue("'$term' (${sport.name}) is not ASCII", term.all { it.code in 0x20..0x7E })
                assertTrue("'$term' (${sport.name}) contains '/'", '/' !in term)
            }
        }
    }

    /**
     * The list that actually rides a live fixture is not any of the static lists above: a scoped
     * football list is REBUILT from the two clubs' squads ([SubtitleGlossary.recognitionHotwords]),
     * so a transferred player whose name the model cannot spell would only surface on a real
     * broadcast. Each side is walked one at a time — a fixture scopes to two, and this test needs
     * every club — and each entry is checked after the ` :score` tier suffix the parser reads is
     * stripped, since the suffix is not part of the word.
     */
    @Test
    fun `every scoped squad hotword is encodable and ascii too`() {
        val chars = vocabCharacters()
        for (team in SubtitleGlossary.Team.entries) {
            val scoped = SubtitleGlossary.recognitionHotwords(
                SubtitleGlossary.Sport.FOOTBALL,
                setOf(team),
            )
            for (raw in scoped.split("/")) {
                val term = raw.substringBefore(" :").trim()
                if (term.isEmpty()) continue
                assertTrue("'$term' (${team.name}) is not ASCII", term.all { it.code in 0x20..0x7E })
                assertTrue("'$term' (${team.name}) contains '/'", '/' !in term)
                for (c in term) {
                    if (c == ' ') continue
                    assertTrue(
                        "'$term' (${team.name} squad) carries '$c', which bpe.vocab cannot spell: " +
                            "it would encode as <unk> and drag the whole hotword list down",
                        c in chars,
                    )
                }
            }
        }
    }
}