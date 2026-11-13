package tv.own.owntv.features.aisubtitles

/**
 * What the commentary said a moment ago, for the cross-sentence half of the player disambiguation.
 *
 * A match introduces a player by his whole name once and then says only his surname for minutes.
 * When that surname is shared by two players on the pitch (`Wilson` on Brentford and Leeds), the
 * in-sentence rule in [SubtitleGlossary.resolveSurnames] has nothing to work with unless the same
 * sentence happens to name a club. This memory supplies the two kinds of evidence that survive the
 * sentence boundary:
 *
 *  - **whole names heard recently** — if only one of the men sharing a surname has been introduced
 *    in full inside [nameTtlMs], a later bare surname is taken to be him. If BOTH were introduced,
 *    the memory proves the ambiguity but cannot choose, so it offers nothing;
 *  - **clubs named recently** — when no whole name was heard, exactly one of the surname's clubs
 *    being mentioned inside [clubWindowMs] is the tiebreaker.
 *
 * Only the manager's ASR worker touches an instance, so it is deliberately not synchronised.
 * Decimals and durations are constructor parameters so tests can use short windows rather than
 * sleeping through the real ones.
 */
internal class SessionNameMemory(
    private val nameTtlMs: Long = NAME_TTL_MS,
    private val clubWindowMs: Long = CLUB_WINDOW_MS,
) {

    /** spoken surname (lower-case) -> whole name -> when it was heard. */
    private val names = HashMap<String, MutableMap<String, Long>>()

    /** Club -> when it was last named. */
    private val clubs = LinkedHashMap<SubtitleGlossary.Team, Long>()

    /** Forget everything — a new session, or an audio timeline jump. */
    fun clear() {
        names.clear()
        clubs.clear()
    }

    /**
     * Record every whole name of the currently scoped fixture that occurs in [text]. Only a whole
     * name is evidence: a bare surname is the thing this memory exists to resolve, not proof of who
     * it was.
     */
    fun remember(text: String, teams: Set<SubtitleGlossary.Team>, nowMs: Long) {
        for (full in SubtitleGlossary.fullNamesIn(text, teams)) {
            val surname = SubtitleGlossary.spokenSurname(full).lowercase()
            names.getOrPut(surname) { HashMap() }[full] = nowMs
        }
    }

    /**
     * Live whole-name evidence as `spoken surname -> whole name`, keeping only surnames that resolve
     * to exactly ONE man inside the TTL. Expired names are dropped here, and a surname with two
     * recently named players is omitted rather than guessed.
     */
    fun recentNames(nowMs: Long): Map<String, String> {
        val out = HashMap<String, String>()
        val bySurname = names.entries.iterator()
        while (bySurname.hasNext()) {
            val (surname, heard) = bySurname.next()
            val entries = heard.entries.iterator()
            while (entries.hasNext()) {
                if (nowMs - entries.next().value > nameTtlMs) entries.remove()
            }
            when (heard.size) {
                0 -> bySurname.remove()
                1 -> out[surname] = heard.keys.first()
                // Two or more recent whole names: the ambiguity is real, so nothing is offered.
            }
        }
        return out
    }

    /** Add the clubs [text] names to the recent-club memory. */
    fun noteClubs(text: String, nowMs: Long) {
        for (team in SubtitleGlossary.teamMentionHits(text).keys) clubs[team] = nowMs
    }

    /** Clubs named inside [clubWindowMs]; older mentions are no longer a tiebreaker. */
    fun recentClubs(nowMs: Long): Set<SubtitleGlossary.Team> {
        val entries = clubs.entries.iterator()
        while (entries.hasNext()) {
            if (nowMs - entries.next().value > clubWindowMs) entries.remove()
        }
        return clubs.keys.toSet()
    }

    companion object {
        /** A whole name heard within five minutes is still usable evidence for a bare surname. */
        const val NAME_TTL_MS = 300_000L

        /** A club mention is a tiebreaker for roughly three quarters of commentary time. */
        const val CLUB_WINDOW_MS = 45_000L
    }
}
