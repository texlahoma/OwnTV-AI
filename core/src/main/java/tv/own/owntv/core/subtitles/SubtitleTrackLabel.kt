package tv.own.owntv.core.subtitles

/**
 * Builds the engine label for an attached external subtitle.
 *
 * The label is not decoration: every lookup that matters keys off it — which file a timing offset
 * shifts, which track is re-selected after a re-prepare, whether mpv's re-list treats a subtitle as
 * already attached. Two subtitles sharing a label are therefore indistinguishable to the player,
 * which is exactly how three same-language downloads used to collapse onto the first one.
 *
 * So a label must satisfy two things at once:
 *
 *  1. **Unique among ALL text tracks**, the file's own embedded ones included. The `OS_`/`LOCAL_`
 *     prefix puts our labels in a namespace a muxed track is very unlikely to occupy, but "unlikely"
 *     is not "cannot" — a media file is free to contain a track literally named `OS_Korean`. So the
 *     prefix is for the user's eyes and [build] still checks against the labels already present,
 *     numbering a collision away.
 *  2. **Informative**, because someone downloading a second subtitle in a language they already have
 *     is doing it precisely to get a different release — the first was out of sync or mistimed. The
 *     release name is the only thing that tells those apart.
 *
 * Deliberately locale-free (see the label rule on `rawTrackLabel`): the prefix is a fixed token, the
 * language and release names come from the provider, and the collision suffix is a bare number. A
 * label therefore never carries a translated word into engine state, where a locale switch could
 * strand it.
 */
object SubtitleTrackLabel {

    const val PREFIX_OPENSUB = "OS"
    const val PREFIX_LOCAL = "LOCAL"

    /** Beyond this a release name is cut — TV rows ellipsize, and the tail is the informative half. */
    private const val MAX_RELEASE_CHARS = 30

    private const val SEPARATOR = " · "

    /**
     * The label for one subtitle: `OS_Korean · WEB-DL.NF`, or `OS_Korean 2` when there is no release
     * name to show. [taken] is every label already in the player's track list plus the ones assigned
     * earlier in this same batch; the result is guaranteed absent from it.
     *
     * [fallbackKey] is used only when the provider gave neither a language nor a release name, so a
     * label is never empty (an empty label matches every unlabelled embedded track).
     */
    fun build(
        prefix: String,
        language: String?,
        release: String?,
        fallbackKey: String,
        taken: Set<String>,
    ): String {
        val name = language?.takeIf { it.isNotBlank() }
            ?: release?.takeIf { it.isNotBlank() }?.let(::trimRelease)
            ?: fallbackKey
        val base = "${prefix}_$name"
        // The release name is dropped when it IS the name, so it can't be printed twice.
        val withRelease = release
            ?.takeIf { it.isNotBlank() && language?.isNotBlank() == true }
            ?.let { base + SEPARATOR + trimRelease(it) }
            ?: base
        return disambiguate(withRelease, taken)
    }

    /** `OS_Korean` → `OS_Korean 2` → `OS_Korean 3` … until one is free. */
    private fun disambiguate(label: String, taken: Set<String>): String {
        if (label !in taken) return label
        var n = 2
        while ("$label $n" in taken) n++
        return "$label $n"
    }

    /**
     * Keep the END of a long release name. `The.Tourist.2010.1080p.BluRay.x264-YIFY` opens with the
     * title the user already knows and closes with the part that distinguishes it from the other two
     * downloads, so cutting the head loses nothing and cutting the tail loses everything. The cut
     * lands on a token boundary when there is one nearby, to avoid slicing mid-word.
     */
    private fun trimRelease(release: String): String {
        val r = release.trim()
        if (r.length <= MAX_RELEASE_CHARS) return r
        val tail = r.substring(r.length - MAX_RELEASE_CHARS)
        val boundary = tail.indexOfFirst { it == '.' || it == ' ' || it == '-' || it == '_' }
        return "…" + if (boundary in 0..8) tail.substring(boundary + 1) else tail
    }
}
