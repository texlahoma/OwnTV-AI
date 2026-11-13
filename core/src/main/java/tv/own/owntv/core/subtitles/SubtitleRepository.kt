package tv.own.owntv.core.subtitles

import android.content.Context
import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONObject
import tv.own.owntv.core.database.dao.LinkedSubtitle
import tv.own.owntv.core.database.dao.SubtitleDao
import tv.own.owntv.core.database.entity.SubtitleCacheEntity
import tv.own.owntv.core.database.entity.SubtitleLinkEntity
import tv.own.owntv.core.database.entity.SubtitleSelectionEntity
import tv.own.owntv.core.database.entity.SubtitleTimingEntity
import java.io.File
import java.io.IOException

/**
 * Orchestrates external subtitles end to end: OpenSubtitles search, the
 * cache-first download flow, the managed on-disk cache, and the remembered
 * selection/timing per profile + content. The player layer calls [resolveForPlayback] to
 * get a ready-to-load local file path for a chosen or remembered subtitle.
 *
 * Quota rule: [downloadAndCache] only calls OpenSubtitles `/download` — which spends
 * one quota unit — when the exact file_id is NOT already cached on this device.
 */
class SubtitleRepository(
    context: Context,
    private val client: OpenSubtitlesClient,
    private val accounts: OpenSubtitlesAccountManager,
    private val okHttpClient: OkHttpClient,
    private val dao: SubtitleDao,
) {
    private val appContext = context.applicationContext
    private val cacheDir: File by lazy { File(appContext.filesDir, "subtitles").apply { mkdirs() } }

    /** A downloaded/cached subtitle ready to load into a player, plus its display identity. */
    data class ResolvedSubtitle(
        val cacheId: Long,
        val path: String,
        val language: String?,
        val languageName: String?,
        val releaseName: String?,
        val format: String?,
        val source: String,
        val openSubFileId: Long?,
    ) {
        /** Stable key for per-subtitle timing memory. */
        val subtitleKey: String
            get() = openSubFileId?.let { "opensub:$it" } ?: "local:$cacheId"
    }

    /** Raw OpenSubtitles search passthrough; the caller builds the query. */
    suspend fun search(query: Map<String, String>): JSONObject = client.search(query)

    /**
     * Cache-first OpenSubtitles download. Returns the cached, ready-to-load subtitle, or
     * throws for the caller's error handling. On a cache hit the file is verified to still exist
     * before skipping `/download`; a corrupt/missing cached file falls through to a fresh download.
     *
     * [onQuota] reports the provider's post-download `remaining`/`reset_time` so the caller can update
     * the allowance display — not invoked on a cache hit (no quota spent).
     */
    suspend fun downloadAndCache(
        profileId: Long,
        fileId: Long,
        language: String?,
        languageName: String?,
        releaseName: String?,
        hearingImpaired: Boolean,
        onQuota: (remaining: Int?, resetTime: String?) -> Unit = { _, _ -> },
    ): ResolvedSubtitle = withContext(Dispatchers.IO) {
        // Reuse an existing download for this exact file — no /download, no quota.
        dao.findByOpenSubFileId(fileId)?.let { cached ->
            if (File(cached.cachedPath).exists()) {
                dao.touchCache(cached.id, System.currentTimeMillis())
                return@withContext cached.toResolved()
            }
            Log.w(TAG, "cached subtitle file missing for file_id=$fileId — re-downloading")
            dao.deleteCache(cached.id)
        }

        val session = accounts.session(profileId)
            ?: throw IllegalStateException()
        val response = try {
            client.requestDownload(session.token, session.apiHost, fileId)
        } catch (e: OpenSubtitlesClient.ApiException) {
            if (e.code != 401) throw e
            // "Stay signed in" covered searches but never downloads: an expired token failed the download
            // outright with "session expired", even with a stored password that could renew it. One
            // attempt, through the same silent re-login the account screen uses.
            Log.i(TAG, "download token expired — attempting the silent re-login")
            val renewed = accounts.refreshUserInfo(profileId) ?: throw e
            client.requestDownload(renewed.token, renewed.apiHost, fileId)
        }
        val link = response.optString("link").takeIf { it.isNotBlank() }
            ?: throw IOException()
        onQuota(
            response.optInt("remaining", -1).takeIf { it >= 0 },
            response.optString("reset_time").takeIf { it.isNotBlank() },
        )
        val remoteName = response.optString("file_name").takeIf { it.isNotBlank() }
        val format = normalizeFormat((remoteName ?: link).substringAfterLast('.', "srt"))
        val bytes = fetch(link)
        val file = File(cacheDir, "opensub_${fileId}.$format")
        file.writeBytes(bytes)

        val entity = SubtitleCacheEntity(
            source = SOURCE_OPENSUB,
            openSubFileId = fileId,
            language = language,
            languageName = languageName,
            releaseName = releaseName ?: remoteName,
            format = format,
            hearingImpaired = hearingImpaired,
            fileName = remoteName ?: file.name,
            cachedPath = file.absolutePath,
            lastUsedAt = System.currentTimeMillis(),
        )
        val id = dao.insertCache(entity)
        entity.copy(id = id).toResolved()
    }

    /**
     * Imports a user-picked local subtitle file: normalises the charset to UTF-8
     * (Windows-125x/ISO-8859 files must render correctly), keeps a managed copy in the
     * subtitle cache dir (playback never depends on the original path staying alive), and
     * records a LOCAL cache row. Re-importing a file with the same name reuses the existing copy so
     * an engine switch or replay never piles up duplicates.
     */
    suspend fun importLocalFile(file: File): ResolvedSubtitle = withContext(Dispatchers.IO) {
        if (!file.isFile) throw IOException()
        dao.findLocalByFileName(file.name)?.let { existing ->
            if (File(existing.cachedPath).exists()) {
                dao.touchCache(existing.id, now())
                return@withContext existing.toResolved()
            }
            dao.deleteCache(existing.id)
        }

        val raw = file.readBytes()
        if (raw.isEmpty()) throw IOException()
        val utf8 = SubtitleCharset.toUtf8(raw)
        // The managed copy gets the NORMALIZED extension (".webvtt" → ".vtt"): both engines pick
        // their parser from the path extension, and only the canonical ones are mapped.
        val format = normalizeFormat(file.extension)
        val copy = File(cacheDir, "local_${now()}_${file.nameWithoutExtension}.$format")
        copy.writeBytes(utf8)

        val entity = SubtitleCacheEntity(
            source = SOURCE_LOCAL,
            openSubFileId = null,
            language = null,
            languageName = null,
            releaseName = file.name,
            format = format,
            fileName = file.name,
            cachedPath = copy.absolutePath,
            lastUsedAt = now(),
        )
        val id = dao.insertCache(entity)
        entity.copy(id = id).toResolved()
    }

    /** Remembers [cacheId] as the active subtitle for this profile + content. */
    suspend fun rememberSelection(profileId: Long, contentKey: String, cacheId: Long) {
        dao.upsertSelection(
            SubtitleSelectionEntity(profileId, contentKey, cacheId = cacheId, off = false, updatedAt = now()),
        )
    }

    /**
     * The remembered subtitle for this profile + content, ready to reload on resume, or null
     * when there's no external choice or the cached file is gone (caller falls back to auto-select).
     * Never triggers a network call.
     */
    suspend fun resolveForPlayback(profileId: Long, contentKey: String): ResolvedSubtitle? {
        val selection = dao.selection(profileId, contentKey) ?: return null
        val cacheId = selection.cacheId ?: return null
        val cached = dao.cacheById(cacheId) ?: return null
        if (!File(cached.cachedPath).exists()) return null
        dao.touchCache(cacheId, now())
        return cached.toResolved()
    }

    // --- content links (downloaded sub ↔ movie/episode) ---

    /** Record that [cacheId] was downloaded for this movie/episode (drives replay re-list + delete UI). */
    suspend fun linkDownload(profileId: Long, contentKey: String, cacheId: Long, mediaType: String, contentTitle: String) {
        dao.insertLink(SubtitleLinkEntity(profileId, contentKey, cacheId, mediaType, contentTitle, now()))
    }

    /**
     * Every subtitle this profile downloaded for [contentKey], ready to re-list in the player on replay.
     * Missing files are skipped (and their stale rows dropped). Never selects — the user re-picks.
     */
    suspend fun restoreForContent(profileId: Long, contentKey: String): List<ResolvedSubtitle> =
        withContext(Dispatchers.IO) {
            dao.linksForContent(profileId, contentKey).mapNotNull { link ->
                if (File(link.cachedPath).exists()) link.toResolved()
                else { dao.deleteCache(link.cacheId); null }
            }
        }

    /** This profile's downloaded subtitles for one item (per-item delete popup). */
    suspend fun linkedForContent(profileId: Long, contentKey: String): List<LinkedSubtitle> =
        dao.linksForContent(profileId, contentKey)

    /** This profile's downloaded subtitles for a media type (delete screen's Movies/Series list). */
    suspend fun linkedForType(profileId: Long, mediaType: String): List<LinkedSubtitle> =
        dao.linksForType(profileId, mediaType)

    /**
     * Delete one cached subtitle FOR ONE PROFILE (subtitle management is per
     * profile). This profile's links go; the shared file + cache row (and with them any other
     * profile's links/selections) are only removed when no other profile still references it.
     */
    suspend fun deleteCached(profileId: Long, cacheId: Long) = withContext(Dispatchers.IO) {
        dao.deleteLinksForProfile(profileId, cacheId)
        dao.clearSelectionsForCache(profileId, cacheId)
        if (dao.linkCountForCacheOtherProfiles(cacheId, profileId) == 0) {
            dao.cacheById(cacheId)?.let { runCatching { File(it.cachedPath).delete() } }
            dao.deleteCache(cacheId)
        }
    }

    /**
     * Delete every subtitle this profile downloaded for a media type (delete-all-movies / -series).
     *
     * Scoped to that media type on purpose. Reusing the per-subtitle delete dropped ALL of this
     * profile's links to each file, so a subtitle linked to both a movie and an episode disappeared from
     * the series list too — and the shared file was deleted underneath it.
     */
    suspend fun deleteAllForType(profileId: Long, mediaType: String) = withContext(Dispatchers.IO) {
        dao.cacheIdsForType(profileId, mediaType).forEach { cacheId ->
            dao.deleteLinksForProfileAndType(profileId, cacheId, mediaType)
            // Only once this profile holds no link of any type, and no other profile does either, is the
            // shared file/row actually gone.
            if (dao.linkCountForCache(cacheId, profileId) == 0) {
                dao.clearSelectionsForCache(profileId, cacheId)
                if (dao.linkCountForCacheOtherProfiles(cacheId, profileId) == 0) {
                    dao.cacheById(cacheId)?.let { runCatching { File(it.cachedPath).delete() } }
                    dao.deleteCache(cacheId)
                }
            }
        }
    }

    // --- timing ---

    suspend fun timingOffset(profileId: Long, contentKey: String, subtitleKey: String): Int =
        dao.timingOffset(profileId, contentKey, subtitleKey) ?: 0

    suspend fun setTimingOffset(profileId: Long, contentKey: String, subtitleKey: String, offsetMs: Int) {
        dao.upsertTiming(SubtitleTimingEntity(profileId, contentKey, subtitleKey, offsetMs, now()))
    }

    private fun fetch(url: String): ByteArray {
        val request = Request.Builder().url(url).build()
        okHttpClient.newCall(request).execute().use { resp ->
            if (!resp.isSuccessful) throw IOException()
            val body = resp.body
            if (body.contentLength() > MAX_SUBTITLE_BYTES) {
                throw IOException("subtitle too large: ${body.contentLength()} bytes")
            }
            // Read with a ceiling rather than whole: a chunked response declares no length at all.
            val sink = okio.Buffer()
            val source = body.source()
            while (sink.size <= MAX_SUBTITLE_BYTES) {
                if (source.read(sink, MAX_SUBTITLE_BYTES + 1 - sink.size) == -1L) break
            }
            if (sink.size > MAX_SUBTITLE_BYTES) throw IOException("subtitle exceeded $MAX_SUBTITLE_BYTES bytes")
            return sink.readByteArray()
        }
    }

    private fun LinkedSubtitle.toResolved() = ResolvedSubtitle(
        cacheId = cacheId,
        path = cachedPath,
        language = language,
        languageName = languageName,
        releaseName = releaseName,
        format = format,
        source = if (openSubFileId != null) SOURCE_OPENSUB else SOURCE_LOCAL,
        openSubFileId = openSubFileId,
    )

    private fun SubtitleCacheEntity.toResolved() = ResolvedSubtitle(
        cacheId = id,
        path = cachedPath,
        language = language,
        languageName = languageName,
        releaseName = releaseName,
        format = format,
        source = source,
        openSubFileId = openSubFileId,
    )

    private fun now() = System.currentTimeMillis()

    /** Canonical format extension: alias spellings collapse (".webvtt" → "vtt"), anything unknown
     *  falls back to "srt" (OpenSubtitles downloads are srt-converted server-side anyway). */
    private fun normalizeFormat(ext: String): String = when (val e = ext.lowercase().trim()) {
        "srt", "ass", "ssa", "vtt" -> e
        "webvtt" -> "vtt"
        else -> "srt"
    }

    companion object {
        private const val TAG = "OpenSubtitles"
        const val SOURCE_OPENSUB = "OPENSUB"
        const val SOURCE_LOCAL = "LOCAL"
        /** Ceiling for a downloaded subtitle. Subtitles are tens of kilobytes; the body used to be read
         *  whole with no limit, so a wrong or hostile link could pull an arbitrary file into memory on a
         *  2 GB TV. Generous enough for the largest real SRT/ASS. */
        private const val MAX_SUBTITLE_BYTES = 8L * 1024 * 1024
    }
}
