package tv.own.owntv.core.storage

import tv.own.owntv.core.model.MediaType
import java.io.File

/**
 * The three folders OwnTV keeps inside the storage root the user chose.
 *
 * They existed before this class did — as the string `"Movies"` at two call sites in the television
 * and two more in the phone, and as `"Series/<show>/Season N"` built by hand in both. That is exactly
 * the shape a drift takes: rename one and a user's library quietly splits in two. **Core owns the
 * names now**, both apps ask for them, and recordings get `TV/` alongside them rather than a fourth
 * private convention.
 *
 * The names are deliberately **not** translated. They are folder names on the user's disk, shared
 * between two apps and read by file managers and media players that know nothing about locales; a
 * library that renamed itself when the phone's language changed would be a library in two halves.
 */
object MediaFolders {

    /** Live recordings (Feature A). */
    const val TV = "TV"

    /** Downloaded films. The name the television has written since downloads existed. */
    const val MOVIES = "Movies"

    /** Downloaded episodes, under `Series/<show>/Season N`. */
    const val SERIES = "Series"

    /**
     * Which of the three folders something of [type] belongs in — and therefore which tab of the
     * Downloads screen lists it.
     *
     * **An episode belongs under `Series`.** Both apps got this wrong in the same way and for the
     * same reason: the tab is *called* Series, so both wrote `mediaType == SERIES`, and a download is
     * never filed as `SERIES` — it is filed as `EPISODE`, because a single episode is what is being
     * fetched. The result was an episode that downloaded perfectly, showed its progress in the
     * status pill, wrote itself to the right folder on disk, and appeared in no list at all, before
     * or after it finished.
     *
     * It lives here, beside the folder names, so the answer is given once. That is the same reason
     * the names themselves moved here: the previous copy of this rule existed twice and drifted, and
     * a third app would have made it three times.
     */
    fun folderFor(type: MediaType): String = when (type) {
        MediaType.LIVE -> TV
        MediaType.MOVIE -> MOVIES
        MediaType.SERIES, MediaType.EPISODE -> SERIES
    }

    /**
     * Where one show's season goes, relative to the root: `Series/<show>/Season N`.
     *
     * [showName] is sanitised here rather than by the caller, because a show called `Marvel's What
     * If…?` is a perfectly ordinary title and a perfectly impossible directory name.
     */
    fun seasonDir(showName: String, seasonNumber: Int): String =
        "$SERIES/${StorageAccess.sanitize(showName)}/$SEASON_PREFIX $seasonNumber"

    /**
     * Create all three inside [root] if they are not there.
     *
     * Best-effort and silent: a folder that cannot be created is a storage problem the download or
     * recording itself will report in words, and creating folders is not the moment to raise it.
     */
    fun ensureIn(root: File) {
        listOf(TV, MOVIES, SERIES).forEach { name ->
            runCatching { File(root, name).mkdirs() }
        }
    }

    /**
     * `Season 3` — the folder inside a show's own. Left as its own constant because the downloads
     * screen reads these paths back to show a file's location.
     */
    const val SEASON_PREFIX = "Season"

    /**
     * Where a saved file sits, read back from its absolute path as a trail a person can follow:
     * `Movies › Interstellar`, or `Series › Game of Thrones › Season 6`.
     *
     * Anchored on one of the three folders named above, so the trail starts where the user's library
     * starts. Everything over that anchor is `Android/data/tv.own.owntv/files/OwnTV` or whichever
     * volume they chose, which tells them nothing about where to look. A file that is under none of
     * the three — restored by hand, or written before these names existed — falls back to its last
     * three segments, which is still more use than the whole path.
     *
     * [separator] is the app's own, because the character between the steps is a matter of
     * presentation and the string resource for it is already translated.
     *
     * A **document** URI — a file in a folder the user picked through the system picker — reads the
     * same way once it is decoded, because the document id of every provider with a filesystem
     * behind it ends in the file's own path: `…/document/primary%3AFilms%2FMovies%2Fx.mp4` is
     * `primary:Films/Movies/x.mp4`, and the volume name before the colon is dropped for exactly the
     * reason the `Android/data/…` prefix is dropped from a path — it says nothing about where to go
     * and look.
     */
    fun crumb(filePath: String?, separator: String): String? {
        val decoded = filePath?.takeIf { it.isNotBlank() }?.let {
            if (MediaTarget.isDocument(it)) documentPath(it) else it
        } ?: return null
        val parts = decoded.substringBeforeLast('/').split('/').filter { it.isNotBlank() }
        val anchor = parts.indexOfLast { it == TV || it == MOVIES || it == SERIES }
        val trail = if (anchor >= 0) parts.subList(anchor, parts.size) else parts.takeLast(3)
        return trail.joinToString(separator).ifBlank { null }
    }

    /**
     * The folder trail hiding inside a document URI: everything after the last `/document/`,
     * percent-decoded, with the volume name that precedes the colon removed.
     *
     * Kept deliberately dumb — no content resolver, no provider call — because [crumb] is read on a
     * list row while it scrolls, and it already has to survive a path it does not recognise.
     */
    private fun documentPath(uri: String): String =
        decode(uri.substringAfterLast(DOCUMENT_SEGMENT)).substringAfter(':')

    /** `%2F` → `/`, and the rest. Written out because core has no Android classes in unit tests. */
    private fun decode(value: String): String =
        runCatching { java.net.URLDecoder.decode(value, "UTF-8") }.getOrDefault(value)

    /** What separates a SAF tree's id from the document's own id. */
    private const val DOCUMENT_SEGMENT = "/document/"
}
