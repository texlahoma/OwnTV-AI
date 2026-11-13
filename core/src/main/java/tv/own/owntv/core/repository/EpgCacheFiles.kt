package tv.own.owntv.core.repository

/**
 * Naming and eligibility rules for the tee'd XMLTV cache files, extracted so they can be tested
 * directly.
 *
 * The download is tee'd to `epg_<storeId>.xmltv.tmp` and renamed to `epg_<storeId>.xmltv` only after
 * the parse finishes cleanly. That rename is the *only* thing that makes a file eligible here — so a
 * guide truncated by a network drop, a killed process, or a cancelled sync can never be served to a
 * later smart-match as though it were the complete feed.
 */

/** Suffix of a promoted, complete cache. */
const val EPG_CACHE_SUFFIX = ".xmltv"

/** Suffix of a download still in flight (or abandoned by a killed process). */
const val EPG_CACHE_TEMP_SUFFIX = ".xmltv.tmp"

private const val EPG_CACHE_PREFIX = "epg_"

/**
 * Whether a cache file may be read back for incremental channel matching.
 *
 * A `.xmltv.tmp` fails on the suffix check: it is a partial download by definition, and the only way
 * a file loses that suffix is the post-`parseCompleted` rename.
 */
fun isUsableEpgCache(name: String, length: Long, lastModified: Long, now: Long, ttlMs: Long): Boolean {
    if (!name.startsWith(EPG_CACHE_PREFIX)) return false
    if (name.endsWith(EPG_CACHE_TEMP_SUFFIX) || !name.endsWith(EPG_CACHE_SUFFIX)) return false
    if (length <= 0L) return false
    val age = now - lastModified
    // A future timestamp (clock change, restored file) is treated as stale rather than eternally fresh.
    return age in 0 until ttlMs
}

/** Whether an abandoned temp file is old enough to be safe to delete — see [isUsableEpgCache]. */
fun isOrphanedEpgTempCache(name: String, lastModified: Long, now: Long, ttlMs: Long): Boolean {
    if (!name.startsWith(EPG_CACHE_PREFIX) || !name.endsWith(EPG_CACHE_TEMP_SUFFIX)) return false
    // Deliberately not `!in 0 until ttlMs`: a fresh temp file may belong to another source syncing
    // right now, and deleting it would silently cost that sync its cache.
    return now - lastModified > ttlMs
}

/** The store id encoded in a promoted cache file name, or null if it isn't one. */
fun epgCacheStoreId(name: String): Long? {
    if (!name.startsWith(EPG_CACHE_PREFIX) || !name.endsWith(EPG_CACHE_SUFFIX)) return null
    if (name.endsWith(EPG_CACHE_TEMP_SUFFIX)) return null
    return name.removePrefix(EPG_CACHE_PREFIX).removeSuffix(EPG_CACHE_SUFFIX).toLongOrNull()
}
