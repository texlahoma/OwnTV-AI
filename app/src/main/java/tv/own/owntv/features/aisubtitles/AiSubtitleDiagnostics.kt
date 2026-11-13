package tv.own.owntv.features.aisubtitles

import android.util.Log
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.ConcurrentHashMap

/**
 * Session diagnostic sink for the AI subtitles: **one row per CLOSED sentence** and a table of the
 * capitalised words the recogniser produced that the names table does not know, both written to
 * app-private files so a real session's mistakes can be pulled off the device after the fact
 * (`adb pull /data/data/<pkg>/files/ai-subtitles/`).
 *
 * This exists because the pipeline's counters can say *that* a name came back wrong — the Chinese line
 * shows it — but never *where*: whether the recogniser heard the name, whether the glossary repair
 * mangled it, or whether the translator chose a rendering. A row carries all three stages
 * ([raw], [corrected], [chinese]) beside the [scene] and [teams] in force, which is what makes a
 * full-name failure diagnosable at all.
 *
 * The second file is the forward-looking half: a live broadcast names players the table has never
 * heard, and every one of them is a capitalised word that is not a hotword, a club or a squad member.
 * Counting those words over a session ranks exactly which name to add next, instead of guessing from a
 * handful of noticed mistakes.
 *
 * Threading: [recordSentence] runs on the translation worker alone (the publish path), [noteWord] on
 * the ASR worker alone, and [flush] on the stop path after both are joined. The word map is a
 * [ConcurrentHashMap] so the ASR worker never contends with a reader; the files are appended by one
 * thread each, so no lock is needed around the writes.
 */
internal class AiSubtitleDiagnostics(root: File) {

    private val dir = File(root, "ai-subtitles").apply { mkdirs() }
    private val stamp = SimpleDateFormat("yyyyMMdd-HHmmss", Locale.US).format(Date())

    /** One tab-separated row per closed sentence; the file a session is diagnosed from. */
    val sentencesFile = File(dir, "sentences-$stamp.tsv")

    /** Capitalised words the table does not know, counted over the session; names worth adding next. */
    val unknownNamesFile = File(dir, "capitalised-unknown-$stamp.tsv")

    private val capitalisedUnknown = ConcurrentHashMap<String, Int>()

    private val timeFormat = SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss.SSS", Locale.US)

    init {
        runCatching {
            sentencesFile.writeText(
                "time\tscene\tteams\traw_recognition\tcorrected\tchinese\n",
            )
        }
    }

    /**
     * One closed sentence: what the recogniser heard ([raw]), what the glossary made of it
     * ([corrected]), and the Chinese that finally reached the screen ([chinese]), with the [scene] and
     * [teams] the decoder was biased toward for it.
     */
    fun recordSentence(raw: String, corrected: String, chinese: String, scene: String, teams: String) {
        val row = listOf(
            timeFormat.format(Date()),
            scene,
            teams,
            clean(raw),
            clean(corrected),
            clean(chinese),
        ).joinToString("\t")
        runCatching { sentencesFile.appendText(row + "\n") }
            .onFailure { Log.w(TAG, "sentence log write failed: ${it.message}") }
    }

    /**
     * [word] as recognised. Counted only when it looks like a proper name the table does not carry:
     * capitalised (so not an ordinary lowercase word), not an all-caps acronym, not a common English
     * word that merely happened to start a sentence, and not already a hotword, a club or a squad
     * member ([SubtitleGlossary.knows]).
     */
    fun noteWord(word: String) {
        val w = nameToken(word)
        if (w.length < 2) return
        if (!w[0].isUpperCase()) return
        // An all-caps token (`VAR`, `BBC`) is an acronym, not a name the table is missing.
        if (w.all { !it.isLetter() || it.isUpperCase() }) return
        if (w.lowercase() in COMMON_WORDS) return
        if (SubtitleGlossary.knows(w)) return
        capitalisedUnknown.merge(w, 1, Int::plus)
    }

    /**
     * The bare name inside a recognised token. The recogniser's words carry their sentence
     * punctuation and possessives (`Salah.`, `Salah,`, `Saka's`), while the names table carries the
     * bare name — so matching the token verbatim counted every sentence-final mention of a name the
     * table already knows as an "unknown", and the ranked list the file exists to produce was
     * dominated by names that need no adding. Strips the non-name edges, then a possessive suffix,
     * and leaves interior punctuation alone so `O'Sullivan` and `Alexander-Arnold` stay whole.
     */
    private fun nameToken(word: String): String {
        var w = word.trim().trim { !it.isLetterOrDigit() }
        w = when {
            w.endsWith("'s") || w.endsWith("’s") -> w.dropLast(2)
            w.endsWith("'") || w.endsWith("’") -> w.dropLast(1)
            else -> w
        }
        return w
    }

    /** Write the ranked capitalised-unknown counts out; called once, at session stop. */
    fun flush() {
        if (capitalisedUnknown.isEmpty()) return
        val ranked = capitalisedUnknown.entries.sortedByDescending { it.value }
        runCatching {
            unknownNamesFile.writeText(
                ranked.joinToString("\n", postfix = "\n") { "${it.key}\t${it.value}" },
            )
        }.onFailure { Log.w(TAG, "capitalised-unknown write failed: ${it.message}") }
    }

    private fun clean(text: String): String =
        text.replace('\t', ' ').replace('\r', ' ').replace('\n', ' ').trim()

    private companion object {
        const val TAG = "AiSubtitle"

        /**
         * Lower-case words that a sentence-initial capital makes look like a name. Without this the
         * count is dominated by `The` / `And` on every line, which is exactly the noise that would
         * bury the player names the file exists to surface.
         */
        val COMMON_WORDS: Set<String> =
            "the a an and but or so if of to in on at for with from by as is are was were be been being has have had do does did will would can could shall should may might must not no yes this that these those he she it they we you i his her its their our your my me him them us there here what when where who whom which why how now then just well oh okay yeah all one two out up off over back again still".split(' ').toSet()
    }
}