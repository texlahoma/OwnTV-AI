package tv.own.owntv.core.live

import android.util.Log
import tv.own.owntv.core.database.entity.SourceEntity
import tv.own.owntv.core.model.SourceType
import tv.own.owntv.core.network.HttpClient
import tv.own.owntv.core.parser.M3uParser
import tv.own.owntv.core.parser.XtreamClient
import tv.own.owntv.core.stalker.StalkerAuthManager
import tv.own.owntv.core.stalker.StalkerClient
import tv.own.owntv.core.stalker.StreamUrlResolver
import tv.own.owntv.core.stalker.stalkerCredentials

/**
 * Finds a handful of playable URLs for a playlist that has **not been synced yet**.
 *
 * That constraint is the whole design. The measurement runs before the first sync writes a single
 * row, so that no channel exists for the user to be watching when the probe cuts a connection — and
 * so nothing has to be undone if the playlist turns out to be unusable. There is therefore no
 * database to read from, and each provider type has to be asked directly.
 *
 * Nothing here is persisted, and the lists fetched are deliberately tiny: one page from a portal,
 * an aborted read of an M3U, an incrementally-parsed prefix of an Xtream response.
 */
class ProbeChannelSource(
    private val http: HttpClient,
    private val xtream: XtreamClient,
    private val stalkerAuth: StalkerAuthManager,
    private val stalker: StalkerClient,
    /**
     * The same resolver playback uses. Not optional politeness — a hand-rolled `create_link` per
     * channel is *wrong* on the portals that embed a ready-to-play URL in the channel list, where
     * `create_link` blanks the stream id and answers 405. This class had that bug: on such a portal
     * every candidate failed, the probe was handed an empty list, and the user was told within a
     * second that the limit could not be worked out.
     */
    private val streamUrls: StreamUrlResolver,
) {

    /** Collect up to [count] distinct URLs, or fewer if the playlist has fewer to give. */
    suspend fun urlsFor(source: SourceEntity, count: Int = MAX_CANDIDATES): List<String> = runCatching {
        when (source.type) {
            SourceType.XTREAM -> xtreamUrls(source, count)
            SourceType.M3U -> m3uUrls(source, count)
            SourceType.STALKER -> stalkerUrls(source, count)
            SourceType.LOCAL_BACKUP -> emptyList()
        }
    }.onFailure { Log.w(TAG, "no probe channels for source ${source.id}: ${it.message}") }
        .getOrDefault(emptyList())

    /**
     * The panel's live list, parsed incrementally and abandoned once enough ids have gone by — the
     * same trick [XtreamClient.firstLiveStreamId] uses, because the full response can be hundreds of
     * megabytes.
     */
    private suspend fun xtreamUrls(source: SourceEntity, count: Int): List<String> {
        val ids = mutableListOf<String>()
        runCatching {
            xtream.streamLive(source, onItem = { item ->
                if (ids.size < count) ids += item.streamId
                if (ids.size >= count) throw StopCollecting()
            })
        }
        return ids.map { xtream.liveUrl(source, it) }
    }

    /**
     * One page of one genre from the portal, each channel's `cmd` turned into a real URL.
     *
     * Resolved here rather than inside the probe because an unused link is harmless, while
     * discovering mid-probe that a channel cannot be resolved is not — and resolved through
     * [StreamUrlResolver] rather than by calling `create_link` directly, because which of the two a
     * portal wants is a property of the portal, and that decision already exists in one place.
     */
    private suspend fun stalkerUrls(source: SourceEntity, count: Int): List<String> {
        val mac = StalkerClient.canonicalizeMac(source.mac.orEmpty()) ?: return emptyList()
        val creds = source.stalkerCredentials(mac)
        val session = stalkerAuth.sessionFor(creds)
        val genres = stalker.getGenres(session.apiBase, mac, session.token, creds.userAgent)
        Log.i(TAG, "portal offered ${genres.size} genre(s) for source ${source.id}")
        val urls = mutableListOf<String>()
        for (genre in genres) {
            if (urls.size >= count) break
            val page = runCatching {
                stalker.getLiveChannelsPage(session.apiBase, mac, session.token, creds.userAgent, genre.id, page = 1)
            }.getOrNull() ?: continue
            for (channel in page.items) {
                if (urls.size >= count) break
                runCatching { streamUrls.resolve(source, channel.cmd) }
                    .onFailure { Log.w(TAG, "probe could not resolve '${channel.name}': ${it.message}") }
                    .getOrNull()
                    ?.takeIf { it.startsWith(HTTP, ignoreCase = true) }
                    ?.let { urls += it }
            }
        }
        return urls
    }

    /**
     * The playlist file, read only as far as the first few entries and then abandoned.
     *
     * An M3U *is* the catalogue, and large ones run to hundreds of thousands of entries, so this
     * closes the connection the moment it has enough rather than downloading the file twice — once to
     * measure and once to sync. VOD-looking entries are skipped where possible: a live channel is the
     * closest thing to what the user will actually do with the connection.
     */
    private suspend fun m3uUrls(source: SourceEntity, count: Int): List<String> {
        val urls = mutableListOf<String>()
        runCatching {
            http.get(source.url, source.userAgent) { input ->
                M3uParser().parse(input) { entry ->
                    val looksVod = entry.type?.contains(VOD, ignoreCase = true) == true ||
                        entry.tvgType?.contains(VOD, ignoreCase = true) == true
                    if (!looksVod && entry.streamUrl.startsWith(HTTP, ignoreCase = true)) urls += entry.streamUrl
                    if (urls.size >= count) throw StopCollecting()
                }
            }
        }
        return urls
    }

    /** Thrown to abandon a streamed parse early. Never escapes this class. */
    private class StopCollecting : RuntimeException(null, null, false, false)

    private companion object {
        const val TAG = "ProbeChannelSource"

        /**
         * Enough for four streams plus a second attempt at each — the probe's own worst case — so it
         * never runs out of channels before it runs out of questions.
         */
        const val MAX_CANDIDATES = MAX_PROBE_STREAMS * 2

        const val VOD = "vod"
        const val HTTP = "http"
    }
}
