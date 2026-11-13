package tv.own.owntv.player

import android.content.Context
import java.io.File
import java.util.Locale

/**
 * Timestamp-shifted copies of external subtitle files, for subtitle-timing adjustment on the
 * ExoPlayer path. ExoPlayer has no subtitle-offset control, so the offset is
 * applied "at load": a shifted copy is generated and side-loaded in place of the original.
 * mpv needs none of this — it applies `sub-delay` natively.
 *
 * Sign convention: positive offset = subtitles shown LATER = timestamps increased.
 */
object SubtitleShift {

    /** Returns a copy of [source] with every cue timestamp moved by [offsetMs] (cached per
     *  source+offset in the app cache dir). Returns [source] unchanged on any parse/IO failure. */
    fun shiftedCopy(context: Context, source: File, offsetMs: Int): File {
        if (offsetMs == 0) return source
        return runCatching {
            val out = File(context.cacheDir, "subshift_${(source.path).hashCode()}_${offsetMs}_${source.name}")
            if (out.exists() && out.lastModified() >= source.lastModified()) return@runCatching out
            val text = source.readText()
            val shifted = when (source.extension.lowercase()) {
                "ass", "ssa" -> shiftAss(text, offsetMs)
                else -> shiftSrtVtt(text, offsetMs, dot = source.extension.lowercase() in setOf("vtt", "webvtt"))
            }
            out.writeText(shifted)
            out
        }.getOrDefault(source)
    }

    // SRT "00:00:20,000 --> 00:00:24,400" / VTT "00:00:20.000 --> 00:00:24.400" (only on cue lines).
    private val SRT_TIME = Regex("""(\d{2}):(\d{2}):(\d{2})[,.](\d{3})""")

    private fun shiftSrtVtt(text: String, offsetMs: Int, dot: Boolean): String =
        text.lineSequence().joinToString("\n") { line ->
            if (!line.contains("-->")) line
            else SRT_TIME.replace(line) { m ->
                val (h, min, s, ms) = m.destructured
                val total = (h.toLong() * 3_600_000 + min.toLong() * 60_000 + s.toLong() * 1_000 + ms.toLong() + offsetMs)
                    .coerceAtLeast(0)
                // Locale.ROOT, not the default locale: Java's Formatter localises %d digits, so on an
                // Arabic / Persian / Nepali device a bare format(...) writes Arabic-Indic or Devanagari
                // digits into the shifted SRT/VTT and the file becomes unparseable. Timestamps are a
                // wire format, always ASCII.
                String.format(
                    Locale.ROOT,
                    "%02d:%02d:%02d%c%03d",
                    total / 3_600_000, total / 60_000 % 60, total / 1_000 % 60,
                    if (dot) '.' else ',', total % 1_000,
                )
            }
        }

    // ASS/SSA "Dialogue: 0,0:00:20.00,0:00:22.00,Style,..." — times are H:MM:SS.cc (centiseconds).
    private val ASS_TIME = Regex("""(\d+):(\d{2}):(\d{2})\.(\d{2})""")

    private fun shiftAss(text: String, offsetMs: Int): String =
        text.lineSequence().joinToString("\n") { line ->
            if (!line.startsWith("Dialogue:")) line
            else ASS_TIME.replace(line) { m ->
                val (h, min, s, cs) = m.destructured
                val total = (h.toLong() * 3_600_000 + min.toLong() * 60_000 + s.toLong() * 1_000 + cs.toLong() * 10 + offsetMs)
                    .coerceAtLeast(0)
                // Same Locale.ROOT rationale as shiftSrtVtt: ASS timestamps are ASCII, never
                // localised digits.
                String.format(
                    Locale.ROOT,
                    "%d:%02d:%02d.%02d",
                    total / 3_600_000, total / 60_000 % 60, total / 1_000 % 60, total % 1_000 / 10,
                )
            }
        }
}
