package tv.own.owntv.core.metadata

/**
 * Builds image.tmdb.org URLs from TMDB relative paths. Images load directly from TMDB's free
 * CDN — no API key, never through the Worker/proxy — so quota only ever sees small JSON.
 */
object MetadataImages {

    /** Poster URL (default w500 — crisp on a TV detail pane). Null-safe: blank/null path → null. */
    fun poster(path: String?, size: String = "w500"): String? =
        path?.takeIf { it.isNotBlank() }?.let { "${TmdbProvider.IMAGE_BASE}/$size${it.ensureLeadingSlash()}" }

    /** Backdrop URL (default w780). */
    fun backdrop(path: String?, size: String = "w780"): String? =
        path?.takeIf { it.isNotBlank() }?.let { "${TmdbProvider.IMAGE_BASE}/$size${it.ensureLeadingSlash()}" }

    /** Cast profile photo (default w185 — TMDB's portrait size, ample for a small card on a TV). */
    fun profile(path: String?, size: String = "w185"): String? =
        path?.takeIf { it.isNotBlank() }?.let { "${TmdbProvider.IMAGE_BASE}/$size${it.ensureLeadingSlash()}" }

    /** Title/logo URL (default w500). */
    fun logo(path: String?, size: String = "w500"): String? =
        path?.takeIf { it.isNotBlank() }?.let { "${TmdbProvider.IMAGE_BASE}/$size${it.ensureLeadingSlash()}" }

    private fun String.ensureLeadingSlash(): String = if (startsWith("/")) this else "/$this"
}
