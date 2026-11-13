package tv.own.owntv.core.recording

import tv.own.owntv.core.database.entity.EpgProgrammeEntity
import tv.own.owntv.core.database.entity.RecordingEntity
import tv.own.owntv.core.model.RecordingStatus

/**
 * "Record every showing of this title on this channel" — deciding *which* showings.
 *
 * Arithmetic and string work only, so every rule here is testable without a guide, a database or a
 * clock. [RecordingManager] does the scheduling.
 */
object RecordingRuleMatcher {

    /**
     * The form two programme titles are compared in.
     *
     * A guide is not consistent with itself: the same programme is `The News`, `The News (HD)`,
     * `THE NEWS`, `The News - Episode 4` and `The News [S2 E4]` across a week of XMLTV from one
     * provider. Folding is what makes "every showing" mean what the user meant rather than what the
     * provider happened to type.
     *
     * What is removed, and why each one:
     *
     * - **Case and surrounding space** — the cheapest and most common difference.
     * - **A trailing bracketed tag** — `(HD)`, `[S2 E4]`, `(Repeat)`, `(2024)`. These describe *this
     *   showing*, which is the opposite of what a title-match should key on.
     * - **A trailing episode suffix** after a dash or colon — `The News - Episode 4`. Keeping it
     *   would make every episode its own rule, which is precisely not "every showing".
     * - **Runs of whitespace and punctuation** — `The  News`, `The News.`, `The News:`.
     *
     * Deliberately **not** fuzzy. No edit distance, no substring matching: a rule that quietly starts
     * recording *Newsnight* because the user asked for *The News* is worse than one that misses a
     * showing, because the user finds out when the disk is full.
     */
    fun fold(title: String): String {
        var text = title.trim()
        // Repeatedly, because providers stack them: "The News (HD) [S2 E4]".
        while (true) {
            val stripped = TRAILING_TAG.replace(text, "").trim()
            if (stripped == text || stripped.isEmpty()) break
            text = stripped
        }
        text = EPISODE_SUFFIX.replace(text, "").trim()
        return text
            .lowercase()
            // Apostrophes are removed rather than turned into a space, so `Marvel's` and `Marvels`
            // fold together. Every other punctuation mark becomes a space, because there it really
            // does separate words — `Law & Order` must not become `laworder`.
            .replace(APOSTROPHES, "")
            .replace(PUNCTUATION, " ")
            .replace(WHITESPACE, " ")
            .trim()
    }

    /** Do these two titles name the same programme, as far as a series rule is concerned? */
    fun sameProgramme(a: String, b: String): Boolean {
        val left = fold(a)
        return left.isNotEmpty() && left == fold(b)
    }

    /**
     * Which of [programmes] this rule should create a timer for.
     *
     * Three things are excluded, and the order matters less than the fact that all three are:
     *
     * - **Anything that has already finished.** A rule is a standing instruction about the future;
     *   the archive is what catch-up recording is for.
     * - **Anything already in [existing]** — matched on `(channelId, programmeStartMs)`, the same
     *   identity the table's unique index uses. This is the de-duplication series recording needs, and it is
     *   what stops every guide refresh from re-scheduling the same week.
     * - **Anything whose title does not fold to the rule's key.** A guide row on the right channel
     *   at the right time is still the wrong programme if it is not the one asked for.
     *
     * A showing the user **cancelled by hand** stays excluded, because a cancelled row is still in
     * `existing`. That is deliberate: telling the app "not this one" and having it come back on the
     * next guide refresh would make the rule impossible to live with.
     */
    fun showingsToSchedule(
        titleKey: String,
        channelId: Long,
        programmes: List<EpgProgrammeEntity>,
        existing: List<RecordingEntity>,
        now: Long,
    ): List<EpgProgrammeEntity> {
        if (titleKey.isBlank()) return emptyList()
        val taken = existing
            .filter { it.channelId == channelId }
            .mapTo(HashSet()) { it.programmeStartMs }
        return programmes.filter { programme ->
            programme.stopMs > now &&
                programme.startMs !in taken &&
                fold(programme.title) == titleKey
        }
    }

    /**
     * Rows this rule created that are still to come — what has to be cancelled when the user turns
     * the rule off. A showing already recorded, or being recorded now, is kept: the user asked to
     * stop recording *future* showings, not to throw away last week's.
     */
    fun pendingFor(ruleId: Long, recordings: List<RecordingEntity>): List<RecordingEntity> =
        recordings.filter { it.ruleId == ruleId && it.status == RecordingStatus.SCHEDULED }

    /** `(HD)`, `[S2 E4]`, `(Repeat)` at the very end. */
    private val TRAILING_TAG = Regex("""[\[(][^\[\]()]*[\])]\s*$""")

    /** `- Episode 4`, `: Part 2`, `- Ep. 12` at the very end. */
    private val EPISODE_SUFFIX = Regex(
        """\s*[-–—:]\s*(?:episode|ep\.?|part|pt\.?)\s*\d+\s*$""",
        RegexOption.IGNORE_CASE,
    )

    /** Straight and curly apostrophes, and the prime some feeds use for one. */
    private val APOSTROPHES = Regex("""['‘’ʼ′]""")

    private val PUNCTUATION = Regex("""[^\p{L}\p{N}]+""")
    private val WHITESPACE = Regex("""\s+""")
}
