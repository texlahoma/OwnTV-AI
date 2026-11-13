package tv.own.owntv.core.content

/**
 * An episode's own title, without the show name and episode code many providers put in front of it
 * ("Solo Leveling - S01E03 - It's Like a Game" → "It's Like a Game"). The series page already says
 * which show and which episode it is, so the prefix only pushes the title out of its tile. Shared by
 * the TV and the phone series pages.
 */
object EpisodeTitles {
    private val code = Regex("""^(?:S\d{1,3}\s*E\d{1,4}|\d{1,3}x\d{1,4})\b""", RegexOption.IGNORE_CASE)
    private val separators = Regex("""^[\s\-–—:|.·]+""")

    /** The cleaned title, or null when nothing but the prefix (or a bare number) is left. */
    fun clean(name: String, seriesName: String): String? {
        var s = name.trim()
        val show = seriesName.trim()
        // The show name only when a separator or an episode code follows: "Lost Souls" in "Lost" stays.
        if (show.isNotEmpty() && s.startsWith(show, ignoreCase = true)) {
            val rest = s.substring(show.length).trimStart()
            if (rest.isEmpty() || separators.containsMatchIn(rest) || code.containsMatchIn(rest)) s = rest
        }
        s = s.replace(separators, "")
        s = s.replace(code, "").replace(separators, "").trim()
        return s.takeIf { it.isNotEmpty() && !it.all(Char::isDigit) }
    }
}
