package tv.own.owntv.core.subtitles

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import org.json.JSONObject
import tv.own.owntv.core.database.entity.EpisodeEntity
import tv.own.owntv.core.database.entity.MovieEntity
import tv.own.owntv.core.database.entity.SeriesEntity
import tv.own.owntv.core.metadata.TitleNormalizer
import tv.own.owntv.core.settings.SettingsRepository
import tv.own.owntv.player.OwnTVPlayer

/**
 * The bridge between "what movie/episode is playing right now" and the OpenSubtitles feature.
 * The movie/series play paths set a [Context] here; live/other playback clears
 * it — so the player's ADD SUBTITLES entry only ever appears for movies and episodes.
 *
 * The search screen (in the player HUD overlay) reads [current] and calls [search] / [apply]; on
 * apply the downloaded subtitle is attached to the running mpv player and remembered for resume.
 */
class NoActiveProfileException : IllegalStateException()
class NoCurrentItemException : IllegalStateException()

class SubtitleController(
    private val repository: SubtitleRepository,
    private val accounts: OpenSubtitlesAccountManager,
    private val settings: SettingsRepository,
    private val player: OwnTVPlayer,
) {
    /** Identity of the item currently playing, enough to pre-fill an OpenSubtitles search. */
    data class Context(
        val profileId: Long,
        val contentKey: String,
        val isEpisode: Boolean,
        val title: String,
        val year: Int?,
        val season: Int?,
        val episode: Int?,
        /** Movie TMDB id, or the series' parent TMDB id for an episode. Null → query search. */
        val tmdbId: Long?,
        /** Local media file when playing an OwnTV download — enables the moviehash enhancer. */
        val localFilePath: String? = null,
    ) {
        val mediaType: String get() = if (isEpisode) "SERIES" else "MOVIE"

    }

    private val _current = MutableStateFlow<Context?>(null)
    val current: StateFlow<Context?> = _current.asStateFlow()

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)

    // Timing memory: the player reports the active subtitle as "path:<file>" (external) or
    // "emb:<ordinal>:<lang>" (embedded); external paths map to their stable subtitleKey via this map,
    // filled on apply/restore. [activeSubKey] is what a user timing change is persisted under.
    private val pathToKey = HashMap<String, String>()
    private var activeSubKey: String? = null

    // The engine label assigned to each attached file, so a re-attach reuses it rather than
    // generating a fresh (numbered) one — see [rawTrackLabel].
    private val pathToLabel = HashMap<String, String>()

    init {
        // Re-list a title's previously downloaded subtitles whenever its file finishes loading.
        // They're attached but not selected — the user re-picks.
        player.onVodFileLoaded = { restoreForCurrentItem() }
        // When the active subtitle changes, apply ITS remembered offset (never another release's);
        // when the user adjusts timing, persist it under the exact subtitle identity.
        player.onActiveSubtitleChanged = { identity -> applyRememberedTiming(identity) }
        player.onSubtitleDelayUserChange = { offsetMs -> persistTiming(offsetMs) }
    }

    private fun applyRememberedTiming(identity: String?) {
        scope.launch {
            val ctx = _current.value ?: return@launch
            val key = identity?.let { if (it.startsWith("path:")) pathToKey[it.removePrefix("path:")] else it }
            activeSubKey = key
            val offset = key?.let {
                runCatching { repository.timingOffset(ctx.profileId, ctx.contentKey, it) }.getOrNull()
            } ?: 0
            player.applySubtitleDelay(offset)
        }
    }

    private fun persistTiming(offsetMs: Int) {
        val ctx = _current.value ?: return
        val key = activeSubKey ?: return
        scope.launch {
            runCatching { repository.setTimingOffset(ctx.profileId, ctx.contentKey, key, offsetMs) }
        }
    }

    fun setMovie(profileId: Long, movie: MovieEntity, tmdbId: Long?, localFilePath: String? = null) {
        if (profileId < 0) return clear()
        _current.value = Context(
            profileId = profileId,
            contentKey = movieKey(movie),
            isEpisode = false,
            title = movie.name,
            year = movie.year,
            season = null,
            episode = null,
            tmdbId = tmdbId,
            localFilePath = localFilePath,
        )
    }

    fun setEpisode(profileId: Long, show: SeriesEntity, episode: EpisodeEntity, parentTmdbId: Long?, localFilePath: String? = null) {
        if (profileId < 0) return clear()
        _current.value = Context(
            profileId = profileId,
            contentKey = episodeKey(show, episode),
            isEpisode = true,
            title = show.name,
            year = show.year,
            season = episode.seasonNumber,
            episode = episode.episodeNumber,
            tmdbId = parentTmdbId,
            localFilePath = localFilePath,
        )
    }

    fun clear() { _current.value = null }

    /** True when the active profile can search/download (signed in to OpenSubtitles). */
    fun isSignedIn(): Boolean {
        val pid = _current.value?.profileId ?: return false
        return accounts.session(pid) != null
    }

    /** In-player sign-in for the "account needed" flow — same path as the Settings screen, so the
     *  user lands back in the search that started it. Throws for the sign-in-failed dialog. */
    suspend fun signIn(username: String, password: String, staySignedIn: Boolean) {
        val pid = _current.value?.profileId ?: activeProfile()
        check(pid >= 0) { NoActiveProfileException() }
        accounts.signIn(pid, username, password, staySignedIn)
    }

    /** Cleaned-up title to prefill "Edit search" with — what the search itself uses, not the raw name. */
    val searchTitle: String
        get() = _current.value?.let { TitleNormalizer.normalize(it.title).query.ifBlank { it.title } }.orEmpty()

    /**
     * Language codes to restrict an OpenSubtitles search to; empty = every language.
     *
     * Driven by the dedicated Settings → OpenSubtitles filter, which is OFF by default — so out of the
     * box a search shows everything OpenSubtitles has for the title and the user picks. Deliberately
     * NOT the player's `preferredSubLang`: that one auto-selects an embedded track and offers a short
     * fixed language list, so borrowing it silently hid online results people never asked to hide.
     */
    suspend fun preferredLanguages(): List<String> {
        if (!settings.subSearchFilterEnabled.first()) return emptyList()
        return settings.subSearchLanguages.first()
            .split(',')
            .mapNotNull { it.trim().takeIf(String::isNotBlank)?.let(::toTwoLetter) }
            .distinct()
    }

    /**
     * Run an OpenSubtitles search for the current item. [languages] filters by
     * language code(s); [editedQuery] (non-null) overrides the metadata match with a free-text query
     * ("Edit search"). Returns the raw OpenSubtitles response for the results screen to parse.
     *
     * TMDB is an OPTIONAL precision boost here, never a gate. A known tmdb_id is the strongest match
     * OpenSubtitles accepts, so it's tried first — but if it yields nothing (stale/wrong match, or the
     * id simply isn't on OpenSubtitles) the search is retried by title, so the user always ends up
     * seeing whatever OpenSubtitles actually has for the title. TMDB enrichment being off, failed or
     * mis-matched must never cost the user their subtitles.
     */
    suspend fun search(languages: List<String>, editedQuery: String? = null): JSONObject {
        val ctx = _current.value ?: return JSONObject()
        val useQuery = editedQuery?.takeIf { it.isNotBlank() }
        // Computed once and shared by both attempts — hashing a multi-GB file twice would be wasteful.
        val moviehash = if (ctx.localFilePath != null && useQuery == null) {
            kotlinx.coroutines.withTimeoutOrNull(2_000) {
                kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
                    MovieHash.compute(tv.own.owntv.core.storage.MediaTarget.of(player.appContext, ctx.localFilePath))
                }
            }
        } else {
            null
        }

        val byTmdbId = useQuery == null && ctx.tmdbId != null
        // A FAILED id search falls back exactly like an empty one. Only the empty case used to, so a
        // network blip or a 5xx on the id attempt surfaced as "no subtitles" for a title OpenSubtitles
        // has — the very outcome the fallback exists to prevent.
        val first = try {
            repository.search(buildQuery(ctx, languages, useQuery, byTmdbId, moviehash))
        } catch (e: java.io.IOException) {
            if (!byTmdbId) throw e
            android.util.Log.w("OpenSubtitles", "subtitle search by TMDB id failed — retrying by title", e)
            null
        }
        if (first != null && (!byTmdbId || first.resultCount() > 0)) return first
        return repository.search(buildQuery(ctx, languages, useQuery = null, byTmdbId = false, moviehash))
    }

    /**
     * One OpenSubtitles query map. [byTmdbId] picks the id match; otherwise it's a title search using
     * the NORMALIZED title — the raw provider name ("EN| The Tourist (2010) [4K]") is full of prefixes
     * and quality tags that match nothing, which is the same cleanup TMDB lookups already do.
     */
    private fun buildQuery(
        ctx: Context,
        languages: List<String>,
        useQuery: String?,
        byTmdbId: Boolean,
        moviehash: String?,
    ): Map<String, String> {
        val q = HashMap<String, String>()
        if (languages.isNotEmpty()) q["languages"] = languages.joinToString(",")
        moviehash?.let { q["moviehash"] = it }

        val normalized = TitleNormalizer.normalize(ctx.title)
        val titleQuery = useQuery ?: normalized.query.takeIf { it.isNotBlank() } ?: ctx.title

        if (ctx.isEpisode) {
            q["type"] = "episode"
            ctx.season?.let { q["season_number"] = it.toString() }
            ctx.episode?.let { q["episode_number"] = it.toString() }
            if (byTmdbId) q["parent_tmdb_id"] = ctx.tmdbId.toString() else q["query"] = titleQuery
        } else {
            q["type"] = "movie"
            if (byTmdbId) {
                q["tmdb_id"] = ctx.tmdbId.toString()
            } else {
                q["query"] = titleQuery
                // Only send a year we're confident in — a wrong one filters out every real result.
                (ctx.year ?: normalized.year.takeIf { useQuery == null })?.let { q["year"] = it.toString() }
            }
        }
        return q
    }

    private fun JSONObject.resultCount(): Int = optJSONArray("data")?.length() ?: 0

    /**
     * Download the chosen result (cache-first), attach it to the running player, and
     * remember it for this profile + item. [onQuota] delivers the provider's post-download
     * remaining/reset for the allowance display. Throws upward for the error dialogs.
     */
    suspend fun apply(
        fileId: Long,
        language: String?,
        languageName: String?,
        releaseName: String?,
        hearingImpaired: Boolean,
        onQuota: (remaining: Int?, resetTime: String?) -> Unit = { _, _ -> },
    ) {
        val ctx = _current.value ?: throw NoCurrentItemException()
        val resolved = repository.downloadAndCache(
            profileId = ctx.profileId,
            fileId = fileId,
            language = language,
            languageName = languageName,
            releaseName = releaseName,
            hearingImpaired = hearingImpaired,
            onQuota = onQuota,
        )
        pathToKey[resolved.path] = resolved.subtitleKey // timing identity, before the attach fires
        player.addExternalSubtitle(
            path = resolved.path,
            title = rawTrackLabel(resolved, labelsInUse()),
            lang = resolved.language,
            source = resolved.playerSource(),
        )
        repository.rememberSelection(ctx.profileId, ctx.contentKey, resolved.cacheId)
        // Persist raw provider title only; episode wording is resolved by the Compose delete screen.
        repository.linkDownload(ctx.profileId, ctx.contentKey, resolved.cacheId, ctx.mediaType, ctx.title)
    }

    /**
     * Attach a user-picked local subtitle file to the running player: managed
     * UTF-8 copy via the repository, then the same remember/link flow as an OpenSubtitles download
     * so it re-lists on replay and shows in the delete surfaces. No account or network needed.
     */
    suspend fun applyLocal(file: java.io.File) {
        val ctx = _current.value ?: throw NoCurrentItemException()
        val resolved = repository.importLocalFile(file)
        pathToKey[resolved.path] = resolved.subtitleKey // timing identity, before the attach fires
        player.addExternalSubtitle(
            path = resolved.path,
            title = rawTrackLabel(resolved, labelsInUse()),
            lang = resolved.language,
            source = resolved.playerSource(),
        )
        repository.rememberSelection(ctx.profileId, ctx.contentKey, resolved.cacheId)
        // Persist raw provider title only; episode wording is resolved by the Compose delete screen.
        repository.linkDownload(ctx.profileId, ctx.contentKey, resolved.cacheId, ctx.mediaType, ctx.title)
    }

    /**
     * The player engine receives only raw subtitle metadata — no translated words, so a locale
     * switch cannot strand one in engine state (see [SubtitleTrackLabel]).
     *
     * [taken] must hold every label already in the player's track list, the file's own embedded
     * tracks included, plus the ones handed out earlier in the same batch: the label is the identity
     * every external-subtitle lookup matches on, so a duplicate silently redirects timing changes and
     * track selection to the wrong file.
     */
    private fun rawTrackLabel(s: SubtitleRepository.ResolvedSubtitle, taken: Set<String>): String =
        // A file keeps the label it was first given. Without this, an engine-toggle carry-over that
        // re-attached the sub before the replay restore ran would see its own label in [taken], get a
        // numbered variant, and attach the same file a second time under a split identity.
        pathToLabel.getOrPut(s.path) {
            SubtitleTrackLabel.build(
                prefix = if (s.source == SubtitleRepository.SOURCE_LOCAL) {
                    SubtitleTrackLabel.PREFIX_LOCAL
                } else {
                    SubtitleTrackLabel.PREFIX_OPENSUB
                },
                language = s.languageName ?: s.language,
                release = s.releaseName,
                fallbackKey = s.subtitleKey,
                taken = taken,
            )
        }

    /** Labels already spoken for in the running player — embedded tracks and earlier attachments. */
    private fun labelsInUse(): MutableSet<String> = player.textTracks().mapTo(HashSet()) { it.label }

    private fun SubtitleRepository.ResolvedSubtitle.playerSource(): tv.own.owntv.player.ExternalSubtitleSource =
        if (source == SubtitleRepository.SOURCE_LOCAL) {
            tv.own.owntv.player.ExternalSubtitleSource.LOCAL
        } else {
            tv.own.owntv.player.ExternalSubtitleSource.OPENSUBTITLES
        }

    /** Re-attach (unselected) every subtitle previously downloaded for the current item, on file load. */
    private fun restoreForCurrentItem() {
        val ctx = _current.value ?: return
        scope.launch {
            val subs = runCatching { repository.restoreForContent(ctx.profileId, ctx.contentKey) }.getOrNull().orEmpty()
            if (subs.isEmpty()) return@launch
            subs.forEach { pathToKey[it.path] = it.subtitleKey } // timing identities
            // Labels accumulate across the batch, not just against the player: these are all attached
            // in one pass, so the player's list can't yet tell the second Korean sub about the first.
            val taken = labelsInUse()
            player.restoreExternalSubtitles(
                subs.map { s ->
                    OwnTVPlayer.ExternalSub(
                        path = s.path,
                        title = rawTrackLabel(s, taken).also(taken::add),
                        lang = s.language,
                        source = s.playerSource(),
                    )
                },
            )
        }
    }

    // --- delete surfaces (settings screen + per-item long-press) ---

    /** Downloaded subtitles for a media type ("MOVIE"/"SERIES"), active profile — the delete screen. */
    suspend fun downloadsForType(mediaType: String): List<tv.own.owntv.core.database.dao.LinkedSubtitle> {
        val pid = activeProfile(); if (pid < 0) return emptyList()
        return repository.linkedForType(pid, mediaType)
    }

    /** Downloaded subtitles for a specific movie (per-item long-press popup). */
    suspend fun downloadsForMovie(movie: MovieEntity): List<tv.own.owntv.core.database.dao.LinkedSubtitle> {
        val pid = activeProfile(); if (pid < 0) return emptyList()
        return repository.linkedForContent(pid, movieKey(movie))
    }

    /** Downloaded subtitles for a specific episode (per-item long-press popup). */
    suspend fun downloadsForEpisode(show: SeriesEntity, ep: EpisodeEntity): List<tv.own.owntv.core.database.dao.LinkedSubtitle> {
        val pid = activeProfile(); if (pid < 0) return emptyList()
        return repository.linkedForContent(pid, episodeKey(show, ep))
    }

    /** Profile-scoped: drops the ACTIVE profile's copy; the shared file survives
     *  while any other profile still references it. */
    suspend fun deleteCached(cacheId: Long) {
        val pid = activeProfile(); if (pid >= 0) repository.deleteCached(pid, cacheId)
    }

    suspend fun deleteAllForType(mediaType: String) {
        val pid = activeProfile(); if (pid >= 0) repository.deleteAllForType(pid, mediaType)
    }

    suspend fun deleteAllDownloads() {
        deleteAllForType("MOVIE"); deleteAllForType("SERIES")
    }

    private suspend fun activeProfile(): Long = settings.activeProfileId.first()

    private fun movieKey(movie: MovieEntity) = "movie:${movie.sourceId}:${movie.remoteId ?: movie.name}"

    private fun episodeKey(show: SeriesEntity, ep: EpisodeEntity) =
        "episode:${show.sourceId}:${show.remoteId ?: show.name}:S${ep.seasonNumber}E${ep.episodeNumber}"

    /**
     * OwnTV stores ISO-639-2 (e.g. "eng"); OpenSubtitles wants 2-letter codes (e.g. "en").
     *
     * Covers every language OwnTV itself ships in, so a user whose app is in Czech or Swedish gets the
     * same filtering an English user does. Both the bibliographic and terminological 639-2 forms are
     * accepted where they differ (`ces`/`cze`, `deu`/`ger`, …) because tracks and playlists use either.
     * Norwegian Bokmål maps to "no": that is the code OpenSubtitles carries, not "nb".
     */
    private fun toTwoLetter(code: String): String? = when (val c = code.lowercase()) {
        "eng" -> "en"; "spa" -> "es"; "fra", "fre" -> "fr"; "deu", "ger" -> "de"; "ita" -> "it"
        "por" -> "pt"; "nld", "dut" -> "nl"; "rus" -> "ru"; "ara" -> "ar"; "hin" -> "hi"
        "zho", "chi" -> "zh"; "jpn" -> "ja"; "kor" -> "ko"; "tur" -> "tr"
        "ben" -> "bn"; "ces", "cze" -> "cs"; "dan" -> "da"; "mal" -> "ml"
        "nob", "nor" -> "no"; "pol" -> "pl"; "swe" -> "sv"
        // Already an OpenSubtitles code: "el", or a region-qualified one like "pt-br" / "zh-cn".
        else -> c.takeIf { it.length == 2 || REGION_CODE.matches(it) }
    }

    private companion object {
        /** OpenSubtitles' region-qualified language codes, e.g. "pt-br", "zh-cn". */
        private val REGION_CODE = Regex("""[a-z]{2}-[a-z]{2}""")
    }
}
