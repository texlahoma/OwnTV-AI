package tv.own.owntv.core.trending

import android.os.SystemClock
import android.util.Log
import java.util.UUID
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import tv.own.owntv.core.database.dao.MovieDao
import tv.own.owntv.core.database.dao.SeriesDao
import tv.own.owntv.core.database.dao.SourceDao
import tv.own.owntv.core.database.dao.TrendingDao
import tv.own.owntv.core.database.entity.TrendingAttemptStatus
import tv.own.owntv.core.database.entity.TrendingItemEntity
import tv.own.owntv.core.database.entity.TrendingSnapshotEntity
import tv.own.owntv.core.database.entity.TrendingSnapshotStatus
import tv.own.owntv.core.database.entity.MetadataCacheEntity
import tv.own.owntv.core.metadata.MetadataProvider
import tv.own.owntv.core.metadata.MetadataRepository
import tv.own.owntv.core.metadata.MetadataType
import tv.own.owntv.core.metadata.TrendingCandidate
import tv.own.owntv.core.metadata.TrendingFeedPage
import tv.own.owntv.core.model.MediaType
import tv.own.owntv.core.repository.EpisodeLoad
import tv.own.owntv.core.repository.SeriesRepository
import tv.own.owntv.core.sync.SyncContentTypes
import tv.own.owntv.core.settings.SettingsRepository
import tv.own.owntv.core.model.HomeRow

class TrendingRepository(
    private val sourceDao: SourceDao,
    private val movieDao: MovieDao,
    private val seriesDao: SeriesDao,
    private val trendingDao: TrendingDao,
    private val metadataProvider: MetadataProvider,
    private val metadataRepository: MetadataRepository,
    private val settings: SettingsRepository,
    private val seriesRepository: SeriesRepository,
    private val schedule: TrendingScheduleStore,
) {
    suspend fun recordUnexpectedFailure(sourceId: Long): TrendingRefreshOutcome.PreservedFailure =
        preserveFailure(
            sourceId = sourceId,
            stage = "unexpected error",
            language = settings.metadataConfig().resolvedLanguage,
            startedAt = SystemClock.elapsedRealtime(),
        )

    /**
     * Rebuilds the Now Trending row for one playlist.
     *
     * Runs after every catalog sync, but only the *matching* stage runs every time — the TMDB fetch is
     * gated by [TrendingScheduleStore]. Adding 500 films therefore shows up in the row the same day,
     * while the trending list itself is re-downloaded once every few days. [force] is the
     * maintainer-only override behind `BuildConfig.DEV_TOOLS`.
     */
    suspend fun refresh(
        sourceId: Long,
        force: Boolean = false,
        onProgress: (TrendingRefreshProgress) -> Unit = {},
    ): TrendingRefreshOutcome {
        val totalStarted = SystemClock.elapsedRealtime()
        val config = settings.metadataConfig()
        if (!config.enabled) return TrendingRefreshOutcome.SkippedProviderMode
        val source = sourceDao.getById(sourceId) ?: return TrendingRefreshOutcome.SourceMissing
        val trendingVisible = sourceDao.profileIdsForSource(sourceId).any { profileId ->
            HomeRow.TRENDING !in settings.homeConfig(profileId).first().hidden
        }
        if (!trendingVisible) return TrendingRefreshOutcome.SkippedHidden
        val enabled = SyncContentTypes.enabledFor(source)
        if (!enabled.movies && !enabled.series) {
            val completedAt = System.currentTimeMillis()
            trendingDao.writeBelowThreshold(
                TrendingSnapshotEntity(
                    sourceId = sourceId,
                    status = TrendingSnapshotStatus.BELOW_THRESHOLD,
                    metadataLanguage = config.resolvedLanguage,
                    refreshedAt = completedAt,
                    candidateFetchedAt = 0,
                    generationId = UUID.randomUUID().toString(),
                    itemCount = 0,
                    matchedItemCount = 0,
                    lastAttemptAt = completedAt,
                    lastAttemptStatus = TrendingAttemptStatus.BELOW_THRESHOLD,
                    failureStage = "no VOD content",
                ),
            )
            return TrendingRefreshOutcome.NoVodScope
        }

        val language = config.resolvedLanguage
        val now = System.currentTimeMillis()
        // Deliberately NOT filtered by metadata language. Candidates fetched under another language
        // still match perfectly well (matching scores mostly against original titles, and the provider
        // catalog is in the provider's language either way), so a language change is not worth an
        // out-of-turn round of calls. The row picks the new language up at the next scheduled fetch.
        val storedRaw = schedule.candidates()
        // An empty list for a type this playlist actually carries cannot be matched against, so it
        // counts as "nothing stored" rather than quietly producing a row with no films in it.
        val stored = storedRaw?.takeIf {
            (!enabled.movies || it.movies.isNotEmpty()) && (!enabled.series || it.series.isNotEmpty())
        }
        val state = trendingDao.getState(sourceId)
        // Deliberately the snapshot status, not the last attempt: a failed attempt leaves the working
        // snapshot in place, so it must not re-open the gate on every sync and retry the same broken
        // call ten times a day. It waits for the normal deadline and re-matches from disk meanwhile.
        val alreadyBuilt = state != null && state.status != TrendingSnapshotStatus.NEVER_BUILT
        val dueAt = schedule.schedule(sourceId)?.dueAt ?: 0L
        // The gate guards the network, not the rebuild. A playlist that has never produced a row, or
        // whose stored candidates are missing, is built immediately — waiting days for a first
        // Trending row would just look broken.
        val gateOpen = force || stored == null || !alreadyBuilt || now >= dueAt
        // ...and even then, a sibling playlist that already downloaded today's list covers this one.
        val useNetwork = gateOpen &&
            (force || stored == null || now - stored.fetchedAt >= TrendingScheduleStore.SAME_DAY_WINDOW_MS)

        Log.i(
            TAG,
            "sourceId=$sourceId gate open=$gateOpen network=$useNetwork force=$force built=$alreadyBuilt " +
                "dueInHours=${if (dueAt == 0L) "never" else "${(dueAt - now) / 3_600_000}"} " +
                "storedAgeHours=${stored?.let { (now - it.fetchedAt) / 3_600_000 } ?: -1} " +
                // A mismatch here is expected, not a fault: it just means the metadata language moved
                // and the row is still on the candidates from before, waiting for its deadline.
                "lang='$language' storedLang='${storedRaw?.language ?: "-"}' snapLang='${state?.metadataLanguage ?: "-"}'",
        )

        onProgress(TrendingRefreshProgress.Fetching)
        val fetchStarted = SystemClock.elapsedRealtime()
        val movieFeed = TrendingFeed()
        val seriesFeed = TrendingFeed()
        val candidateFetchedAt: Long
        // The language the candidate titles are actually in, which after a metadata language change is
        // not the same as the one now configured. Recorded rather than assumed so a mixed-language row
        // is obvious in the log instead of looking like a bug.
        val candidateLanguage: String
        var storeDirty = false
        if (useNetwork) {
            // Page 1 only. Matching walks candidates in rank order, so page 2 can only matter when
            // page 1 leaves slots open — it is fetched below, on demand.
            //
            // Both media types are fetched even when this playlist carries only one of them: the
            // stored list is shared with every other playlist, and a half-filled store would send the
            // next one straight back to the network. Only a type this source needs can fail the build.
            val (moviePageOne, seriesPageOne) = coroutineScope {
                val movies = async { metadataProvider.trendingPage(MetadataType.MOVIE, 1) }
                val series = async { metadataProvider.trendingPage(MetadataType.TV, 1) }
                movies.await() to series.await()
            }
            if (enabled.movies && moviePageOne == null) return preserveFailure(sourceId, "movie candidates", language, totalStarted)
            if (enabled.series && seriesPageOne == null) return preserveFailure(sourceId, "TV candidates", language, totalStarted)
            // An optional type whose fetch failed keeps whatever was already on disk.
            moviePageOne?.let { movieFeed.add(it) } ?: storedRaw?.let { movieFeed.restoreMovies(it) }
            seriesPageOne?.let { seriesFeed.add(it) } ?: storedRaw?.let { seriesFeed.restoreSeries(it) }
            candidateFetchedAt = now
            candidateLanguage = language
            storeDirty = true
        } else {
            val cached = checkNotNull(stored)
            movieFeed.restoreMovies(cached)
            seriesFeed.restoreSeries(cached)
            candidateFetchedAt = cached.fetchedAt
            candidateLanguage = cached.language
        }
        var fetchMs = SystemClock.elapsedRealtime() - fetchStarted
        var movieCandidates = movieFeed.candidates
        var seriesCandidates = seriesFeed.candidates
        onProgress(TrendingRefreshProgress.CandidatesReceived(movieCandidates.size, seriesCandidates.size))

        /** Downloads the one page beyond what is held, for a type whose matching came up short. */
        suspend fun loadNextPage(feed: TrendingFeed, type: MetadataType): Boolean {
            val started = SystemClock.elapsedRealtime()
            val page = metadataProvider.trendingPage(type, feed.pagesLoaded + 1)
            fetchMs += SystemClock.elapsedRealtime() - started
            if (page == null) return false
            feed.add(page)
            storeDirty = true
            return true
        }

        val movieBackfillTotal = if (enabled.movies) movieDao.trendingMetadataBackfillCount(sourceId) else 0
        val seriesBackfillTotal = if (enabled.series) seriesDao.trendingMetadataBackfillCount(sourceId) else 0
        val backfillTotal = movieBackfillTotal + seriesBackfillTotal
        var backfillProcessed = 0
        onProgress(TrendingRefreshProgress.PreparingCatalog(0, backfillTotal))
        val preparationStarted = SystemClock.elapsedRealtime()
        val backfilledMovies = if (enabled.movies) backfillMovies(sourceId) { processed ->
            backfillProcessed = processed
            onProgress(TrendingRefreshProgress.PreparingCatalog(backfillProcessed, backfillTotal))
        } else 0
        val backfilledSeries = if (enabled.series) backfillSeries(sourceId) { processed ->
            onProgress(TrendingRefreshProgress.PreparingCatalog(backfillProcessed + processed, backfillTotal))
        } else 0
        val preparationMs = SystemClock.elapsedRealtime() - preparationStarted
        if (backfilledMovies + backfilledSeries > 0) {
            Log.i(TAG, "sourceId=$sourceId provider metadata backfilled movies=$backfilledMovies series=$backfilledSeries ms=$preparationMs")
        }

        var movieMatchCount = 0
        var seriesMatchCount = 0

        suspend fun matchMovies(candidates: List<TrendingCandidate>): TrendingMatchResult {
            movieMatchCount = 0
            onProgress(TrendingRefreshProgress.MatchingMovies(0, 0, candidates.size, TrendingMatcher.MAX_PER_MEDIA_TYPE))
            return TrendingMatcher.matchMedia(
                candidates = candidates,
                mediaType = MediaType.MOVIE,
                preferredLanguage = config.resolvedLanguage,
                limit = TrendingMatcher.MAX_PER_MEDIA_TYPE,
                exactLookup = { movieDao.trendingExact(sourceId, it) },
                ftsLookup = { query, limit -> movieDao.trendingFts(sourceId, query, limit) },
            ) { checked, match ->
                if (match != null) movieMatchCount++
                onProgress(
                    TrendingRefreshProgress.MatchingMovies(
                        checked = checked,
                        matched = movieMatchCount,
                        candidates = candidates.size,
                        target = TrendingMatcher.MAX_PER_MEDIA_TYPE,
                    ),
                )
            }
        }

        suspend fun matchSeries(candidates: List<TrendingCandidate>, target: Int): TrendingMatchResult {
            seriesMatchCount = 0
            onProgress(TrendingRefreshProgress.MatchingSeries(0, 0, candidates.size, target))
            return TrendingMatcher.matchMedia(
                candidates = candidates,
                mediaType = MediaType.SERIES,
                preferredLanguage = config.resolvedLanguage,
                limit = target,
                exactLookup = { seriesDao.trendingExact(sourceId, it) },
                ftsLookup = { query, limit -> seriesDao.trendingFts(sourceId, query, limit) },
            ) { checked, match ->
                if (match != null) seriesMatchCount++
                onProgress(
                    TrendingRefreshProgress.MatchingSeries(
                        checked = checked,
                        matched = seriesMatchCount,
                        candidates = candidates.size,
                        target = target,
                    ),
                )
            }
        }

        var movieResult =
            if (enabled.movies) matchMovies(movieCandidates) else TrendingMatchResult(emptyList(), 0, 0, 0)
        // Page 2 holds strictly lower-ranked candidates, so it can only add selections the quota left room
        // for. Once the limit is reached the extra call would change nothing and is skipped.
        if (
            enabled.movies &&
            movieResult.selections.size < TrendingMatcher.MAX_PER_MEDIA_TYPE &&
            movieFeed.hasUnloadedPage
        ) {
            if (!loadNextPage(movieFeed, MetadataType.MOVIE)) {
                return preserveFailure(sourceId, "movie candidates", language, totalStarted)
            }
            movieCandidates = movieFeed.candidates
            movieResult = matchMovies(movieCandidates)
        }

        val seriesTarget = if (movieResult.selections.size >= BALANCED_TARGET) {
            BALANCED_TARGET
        } else {
            TrendingMatcher.MAX_TOTAL - movieResult.selections.size
        }
        var seriesResult = if (enabled.series && seriesTarget > 0) {
            matchSeries(seriesCandidates, seriesTarget)
        } else TrendingMatchResult(emptyList(), 0, 0, 0)
        if (
            enabled.series &&
            seriesTarget > 0 &&
            seriesResult.selections.size < seriesTarget &&
            seriesFeed.hasUnloadedPage
        ) {
            if (!loadNextPage(seriesFeed, MetadataType.TV)) {
                return preserveFailure(sourceId, "TV candidates", language, totalStarted)
            }
            seriesCandidates = seriesFeed.candidates
            seriesResult = matchSeries(seriesCandidates, seriesTarget)
        }

        // Persisted before the below-threshold exit: the download happened either way, and the next
        // playlist to come due should reuse it rather than repeat it.
        if (storeDirty) {
            schedule.storeCandidates(
                TrendingScheduleStore.Candidates(
                    language = candidateLanguage,
                    fetchedAt = candidateFetchedAt,
                    movies = movieFeed.candidates,
                    series = seriesFeed.candidates,
                    movieTotalPages = movieFeed.totalPages,
                    seriesTotalPages = seriesFeed.totalPages,
                    moviePagesLoaded = movieFeed.pagesLoaded,
                    seriesPagesLoaded = seriesFeed.pagesLoaded,
                ),
            )
        }

        val selections = TrendingMatcher.assemble(movieResult.selections, seriesResult.selections)
        val completedAt = System.currentTimeMillis()
        val generationId = UUID.randomUUID().toString()
        if (selections.size < TrendingDao.MIN_ELIGIBLE_ITEMS) {
            val writeStarted = SystemClock.elapsedRealtime()
            trendingDao.writeBelowThreshold(
                TrendingSnapshotEntity(
                    sourceId = sourceId,
                    status = TrendingSnapshotStatus.BELOW_THRESHOLD,
                    metadataLanguage = candidateLanguage,
                    refreshedAt = completedAt,
                    candidateFetchedAt = candidateFetchedAt,
                    generationId = generationId,
                    itemCount = 0,
                    matchedItemCount = selections.size,
                    lastAttemptAt = completedAt,
                    lastAttemptStatus = TrendingAttemptStatus.BELOW_THRESHOLD,
                ),
            )
            // Short retry rather than a full span: too few matches usually means the catalog is still
            // filling in, and matching re-runs for free on every sync in between anyway.
            if (gateOpen) schedule.setRetry(sourceId, completedAt + BELOW_THRESHOLD_RETRY_MS)
            logResult(sourceId, movieCandidates.size, seriesCandidates.size, movieResult, seriesResult, selections.size, "below-threshold", fetchMs, preparationMs, 0, SystemClock.elapsedRealtime() - writeStarted, totalStarted)
            return TrendingRefreshOutcome.Replaced(itemCount = selections.size, eligible = false)
        }

        // Provider season inventory is deliberately lazy for Xtream/Stalker. Load it only for the
        // final Trending series, using the normal stable-ID merge so history and resume stay attached.
        // M3U is already populated during sync, and the repository turns this into a cheap cache hit.
        val selectedSeries = selections.filter { it.variant.item.mediaType == MediaType.SERIES }
        onProgress(TrendingRefreshProgress.LoadingProviderSeasons(0, selectedSeries.size))
        selectedSeries.forEachIndexed { index, selection ->
            val seriesId = selection.variant.item.id
            val loaded = runCatching {
                seriesDao.getSeriesById(seriesId)?.let { seriesRepository.loadEpisodes(it) } == EpisodeLoad.Ready
            }.onFailure {
                Log.w(TAG, "sourceId=$sourceId provider season load failed seriesId=$seriesId", it)
            }.getOrDefault(false)
            val seasonCount = runCatching { seriesDao.storedSeasonCount(seriesId) }.getOrDefault(0)
            Log.i(
                TAG,
                "sourceId=$sourceId provider seasons checked=${index + 1}/${selectedSeries.size} " +
                    "seriesId=$seriesId loaded=$loaded seasons=$seasonCount",
            )
            onProgress(TrendingRefreshProgress.LoadingProviderSeasons(index + 1, selectedSeries.size))
        }

        onProgress(TrendingRefreshProgress.Enriching(selections.size))
        val enrichmentStarted = SystemClock.elapsedRealtime()
        val semaphore = Semaphore(ENRICHMENT_CONCURRENCY)
        // Goes through the metadata cache, not straight to the provider: these ids are re-selected on
        // most rebuilds, and the detail screen caches the same rows, so a warm cache makes this step
        // free instead of ten calls every time.
        //
        // A cache miss may only reach the network on a run the gate already let through. The free
        // re-match that happens on every other sync must stay at zero calls end to end — otherwise
        // anything that empties the cache (changing the metadata language does exactly that) would
        // hand the next resync a fresh round of detail downloads through the back door.
        //
        // When the gate is OPEN these can reach the network, and up to MAX_TOTAL of them fired
        // three-at-a-time lands ~10 requests inside a couple of seconds — enough to trip the edge
        // rate-limit rule, which counts per IP over a 10 s window. So a network-allowed run is paced
        // one call at a time with a gap between them; the whole step then spreads over ~12 s instead
        // of ~2 s. This runs in a background worker once every 5-8 days, so the extra seconds are
        // invisible, and staying under the edge limit is worth far more than finishing sooner.
        //
        // A gate-shut run touches only the cache, so it keeps the original fast concurrent path.
        val details: List<Pair<TrendingSelection, MetadataCacheEntity?>> = if (gateOpen) {
            selections.map { selection ->
                val detail = metadataRepository.cachedDetails(
                    selection.candidate.tmdbId,
                    selection.candidate.type,
                    allowNetwork = true,
                )
                delay(ENRICHMENT_PACING_MS)
                selection to detail
            }
        } else {
            coroutineScope {
                selections.map { selection ->
                    async {
                        selection to semaphore.withPermit {
                            metadataRepository.cachedDetails(
                                selection.candidate.tmdbId,
                                selection.candidate.type,
                                allowNetwork = false,
                            )
                        }
                    }
                }.awaitAll()
            }
        }
        val enrichmentMs = SystemClock.elapsedRealtime() - enrichmentStarted
        val detailFailures = details.count { it.second == null }
        // Expected, not a fault, when the gate is shut: a cache miss simply falls back to the fields the
        // trending feed already carried. Only a miss on a network-allowed run means a call went wrong.
        if (detailFailures > 0) Log.w(TAG, "sourceId=$sourceId detail fallback count=$detailFailures networkAllowed=$gateOpen")

        val items = details.mapIndexed { position, (selection, detail) ->
            val candidate = selection.candidate
            val variant = selection.variant
            TrendingItemEntity(
                sourceId = sourceId,
                position = position,
                tmdbId = candidate.tmdbId,
                mediaType = variant.item.mediaType,
                trendingRank = candidate.trendingRank,
                providerItemId = variant.item.id,
                providerRemoteId = variant.item.remoteId,
                providerStableKey = variant.stableKey,
                providerRawName = variant.item.name,
                canonicalTitle = variant.canonicalTitle,
                providerLanguage = variant.language,
                advertisedQuality = variant.quality.label,
                advertisedCapabilities = variant.capabilities.takeIf { it.isNotEmpty() }?.joinToString(" • "),
                localizedTitle = candidate.localizedTitle,
                originalTitle = candidate.originalTitle,
                year = detail?.year ?: candidate.year,
                overview = detail?.overview ?: candidate.overview,
                posterPath = detail?.posterPath ?: candidate.posterPath,
                backdropPath = detail?.backdropPath ?: candidate.backdropPath,
                rating = detail?.rating ?: candidate.rating,
                trailerKey = detail?.trailerKey,
                generationId = generationId,
                refreshedAt = completedAt,
            )
        }
        onProgress(TrendingRefreshProgress.Publishing(items.size))
        val writeStarted = SystemClock.elapsedRealtime()
        trendingDao.replaceSnapshot(
            TrendingSnapshotEntity(
                sourceId = sourceId,
                status = TrendingSnapshotStatus.ELIGIBLE,
                metadataLanguage = candidateLanguage,
                refreshedAt = completedAt,
                candidateFetchedAt = candidateFetchedAt,
                generationId = generationId,
                itemCount = items.size,
                matchedItemCount = items.size,
                lastAttemptAt = completedAt,
                lastAttemptStatus = TrendingAttemptStatus.SUCCESS,
            ),
            items,
        )
        val writeMs = SystemClock.elapsedRealtime() - writeStarted
        // Only a run that was allowed through the gate books the next one; the free re-match runs in
        // between must not keep pushing the deadline away.
        val nextFetchAt = if (gateOpen) schedule.rollDeadline(sourceId, completedAt) else dueAt
        logResult(sourceId, movieCandidates.size, seriesCandidates.size, movieResult, seriesResult, items.size, "published", fetchMs, preparationMs, enrichmentMs, writeMs, totalStarted)
        Log.i(TAG, "sourceId=$sourceId nextFetchInDays=${((nextFetchAt - completedAt).coerceAtLeast(0)) / TrendingScheduleStore.DAY_MS}")
        return TrendingRefreshOutcome.Replaced(itemCount = items.size, eligible = true)
    }

    private suspend fun backfillMovies(sourceId: Long, onProgress: (Int) -> Unit): Int {
        var count = 0
        while (true) {
            val rows = movieDao.trendingMetadataBackfill(sourceId, BACKFILL_BATCH)
            if (rows.isEmpty()) return count
            movieDao.updateAll(rows)
            count += rows.size
            onProgress(count)
        }
    }

    private suspend fun backfillSeries(sourceId: Long, onProgress: (Int) -> Unit): Int {
        var count = 0
        while (true) {
            val rows = seriesDao.trendingMetadataBackfill(sourceId, BACKFILL_BATCH)
            if (rows.isEmpty()) return count
            seriesDao.updateSeries(rows)
            count += rows.size
            onProgress(count)
        }
    }

    private suspend fun preserveFailure(sourceId: Long, stage: String, language: String, startedAt: Long): TrendingRefreshOutcome.PreservedFailure {
        val attemptAt = System.currentTimeMillis()
        trendingDao.recordFailure(
            TrendingSnapshotEntity(
                sourceId = sourceId,
                status = TrendingSnapshotStatus.NEVER_BUILT,
                metadataLanguage = language,
                refreshedAt = 0,
                candidateFetchedAt = 0,
                generationId = "",
                itemCount = 0,
                lastAttemptAt = attemptAt,
                lastAttemptStatus = TrendingAttemptStatus.FAILED,
                failureStage = stage,
            ),
            stage,
        )
        Log.w(TAG, "Preserve old snapshot sourceId=$sourceId failedStage=$stage totalMs=${SystemClock.elapsedRealtime() - startedAt}")
        return TrendingRefreshOutcome.PreservedFailure(stage)
    }

    private fun logResult(
        sourceId: Long,
        movieCandidates: Int,
        seriesCandidates: Int,
        movies: TrendingMatchResult,
        series: TrendingMatchResult,
        finalCount: Int,
        decision: String,
        fetchMs: Long,
        preparationMs: Long,
        enrichmentMs: Long,
        writeMs: Long,
        startedAt: Long,
    ) {
        Log.i(TAG, "sourceId=$sourceId candidates=$movieCandidates/$seriesCandidates matches=${movies.selections.size}/${series.selections.size} final=$finalCount decision=$decision")
        Log.i(TAG, "sourceId=$sourceId timing fetchMs=$fetchMs preparationMs=$preparationMs exactMs=${movies.exactLookupMs + series.exactLookupMs} ftsMs=${movies.ftsLookupMs + series.ftsLookupMs} ftsFallbacks=${movies.ftsFallbacks + series.ftsFallbacks} enrichmentMs=$enrichmentMs snapshotWriteMs=$writeMs totalMs=${SystemClock.elapsedRealtime() - startedAt}")
    }

    companion object {
        private const val TAG = "TrendingRepository"
        private const val BALANCED_TARGET = 5
        private const val BACKFILL_BATCH = 1_000
        private const val ENRICHMENT_CONCURRENCY = 3

        /**
         * Gap between network-allowed detail calls in the enrichment step, so a Trending rebuild
         * cannot burst past the edge rate-limit rule (counted per IP over a 10 s window). At
         * MAX_TOTAL = 10 items this spreads the step across roughly 12 seconds.
         */
        private const val ENRICHMENT_PACING_MS = 1_200L
        /** How long a below-threshold playlist waits before trying a fresh trending list. */
        private const val BELOW_THRESHOLD_RETRY_MS = 12L * 60 * 60 * 1000
    }
}

/**
 * The Trending pages held for one media type during a refresh, whichever way they arrived — restored
 * from [TrendingScheduleStore], downloaded, or both (a stored page 1 topped up with a fresh page 2).
 */
private class TrendingFeed {
    private val pages = mutableListOf<TrendingFeedPage>()

    var candidates: List<TrendingCandidate> = emptyList()
        private set
    var totalPages: Int = 0
        private set
    var pagesLoaded: Int = 0
        private set

    fun add(page: TrendingFeedPage, loaded: Int = page.page) {
        pages += page
        totalPages = maxOf(totalPages, page.totalPages)
        pagesLoaded = maxOf(pagesLoaded, loaded)
        candidates = TrendingFeedPage.merge(pages)
    }

    fun restoreMovies(stored: TrendingScheduleStore.Candidates) =
        restore(stored.movies, stored.movieTotalPages, stored.moviePagesLoaded)

    fun restoreSeries(stored: TrendingScheduleStore.Candidates) =
        restore(stored.series, stored.seriesTotalPages, stored.seriesPagesLoaded)

    /** True when the provider has a page this refresh has not downloaded, within [MAX_PAGES]. */
    val hasUnloadedPage: Boolean get() = pagesLoaded in 1 until minOf(totalPages, MAX_PAGES)

    private fun restore(saved: List<TrendingCandidate>, total: Int, loaded: Int) {
        if (saved.isEmpty()) return
        // Rank order survives the round trip, so the stored list re-enters as a single synthetic page
        // and merges correctly with a later real page.
        add(TrendingFeedPage(page = 1, totalPages = maxOf(total, 1), candidates = saved), loaded = maxOf(loaded, 1))
    }

    private companion object {
        /** Beyond two pages the candidates rank far below anything the ten showcase slots can use. */
        const val MAX_PAGES = 2
    }
}

sealed interface TrendingRefreshOutcome {
    data object SkippedProviderMode : TrendingRefreshOutcome
    data object SkippedHidden : TrendingRefreshOutcome
    data object SourceMissing : TrendingRefreshOutcome
    data object NoVodScope : TrendingRefreshOutcome
    data class PreservedFailure(val stage: String) : TrendingRefreshOutcome
    data class Replaced(val itemCount: Int, val eligible: Boolean) : TrendingRefreshOutcome
}

sealed interface TrendingRefreshProgress {
    data object Fetching : TrendingRefreshProgress
    data class CandidatesReceived(val movies: Int, val series: Int) : TrendingRefreshProgress {
        val total: Int get() = movies + series
    }
    data class PreparingCatalog(val processed: Int, val total: Int) : TrendingRefreshProgress
    data class MatchingMovies(val checked: Int, val matched: Int, val candidates: Int, val target: Int) : TrendingRefreshProgress
    data class MatchingSeries(val checked: Int, val matched: Int, val candidates: Int, val target: Int) : TrendingRefreshProgress
    data class LoadingProviderSeasons(val processed: Int, val total: Int) : TrendingRefreshProgress
    data class Enriching(val itemCount: Int) : TrendingRefreshProgress
    data class Publishing(val itemCount: Int) : TrendingRefreshProgress
}
