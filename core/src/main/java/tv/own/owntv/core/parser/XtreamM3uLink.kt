package tv.own.owntv.core.parser

import java.net.URI
import java.net.URLDecoder

/**
 * An "M3U" link that is really an Xtream account: `http://host:port/get.php?username=U&password=P&type=m3u_plus&output=ts`.
 *
 * Providers hand these out as "your M3U", but the playlist they export carries no movie/series tags
 * (the type is only in the stream path), so an M3U import files every film and episode under Live TV
 * and some panels refuse `get.php` outright (HTTP 884) while the same account works on
 * `player_api.php`. Importing the link as Xtream fixes both and brings the real catalogue with it.
 */
data class XtreamM3uLink(
    /** `scheme://host[:port]` plus any path before `/get.php` — what an Xtream source stores as its url. */
    val server: String,
    val username: String,
    val password: String,
    /** The link asked for HLS (`output=m3u8`/`hls`), so the Xtream source should too. */
    val preferHls: Boolean,
) {
    companion object {
        /** The link with any HTML-escaped `&amp;` undone — what a link copied from a web page carries. */
        fun clean(url: String): String = url.trim().replace("&amp;", "&", ignoreCase = true)

        /** The account inside [url], or null when it is not an Xtream `get.php` link. */
        fun parse(url: String): XtreamM3uLink? {
            val uri = runCatching { URI(clean(url)) }.getOrNull() ?: return null
            val scheme = uri.scheme?.lowercase()
            if (scheme != "http" && scheme != "https") return null
            val authority = uri.rawAuthority?.takeIf { it.isNotBlank() } ?: return null
            val path = uri.rawPath.orEmpty()
            if (!path.endsWith("/get.php", ignoreCase = true)) return null
            val params = uri.rawQuery.orEmpty().split('&').mapNotNull { pair ->
                val key = pair.substringBefore('=').takeIf { it.isNotBlank() } ?: return@mapNotNull null
                key.lowercase() to decode(pair.substringAfter('=', ""))
            }.toMap()
            val username = params["username"]?.takeIf { it.isNotBlank() } ?: return null
            val password = params["password"]?.takeIf { it.isNotBlank() } ?: return null
            val output = params["output"]?.lowercase()
            return XtreamM3uLink(
                server = "$scheme://$authority${path.dropLast("/get.php".length)}",
                username = username,
                password = password,
                preferHls = output == "m3u8" || output == "hls",
            )
        }

        private fun decode(value: String): String =
            runCatching { URLDecoder.decode(value, "UTF-8") }.getOrDefault(value)
    }
}
