package tv.own.owntv.core.repository

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import tv.own.owntv.core.database.dao.SourceDao
import tv.own.owntv.core.database.entity.SourceEntity
import tv.own.owntv.core.model.MediaType
import tv.own.owntv.core.settings.SettingsRepository

/**
 * The set of sources the Browse screens should show right now: the active profile's linked sources,
 * narrowed to the user's chosen "active playlist" ([SettingsRepository.defaultSourceId]) when one is set.
 *
 * A default of `-1` (or a default that no longer belongs to the profile) means **All playlists** — the
 * full merged view. This is purely a *display* filter: every playlist still imports and stores all of its
 * content and EPG; this only decides which source ids the grids/guide read from.
 *
 * Per-section accessors ([liveSourceIds] / [movieSourceIds] / [seriesSourceIds]) further hide sources
 * whose enabledScope marks that section Off — cache stays on disk, rows just don't surface.
 */
data class ActiveProfileSources(val profileId: Long, val sources: List<SourceEntity>) {
    val sourceIds: List<Long> get() = sources.map { it.id }
    val liveSourceIds: List<Long> get() = sources.filter { it.syncLive }.map { it.id }
    val movieSourceIds: List<Long> get() = sources.filter { it.syncMovies }.map { it.id }
    val seriesSourceIds: List<Long> get() = sources.filter { it.syncSeries }.map { it.id }

    fun sourceIdsFor(type: MediaType): List<Long> = when (type) {
        MediaType.LIVE -> liveSourceIds
        MediaType.MOVIE -> movieSourceIds
        MediaType.SERIES, MediaType.EPISODE -> seriesSourceIds
    }
}

/**
 * Reactive [ActiveProfileSources] for the current profile + active-playlist filter. Emits again whenever
 * the profile, its linked sources, or the chosen default changes, so Browse refreshes live.
 */
@Suppress("OPT_IN_USAGE")
fun activeProfileSources(
    settings: SettingsRepository,
    sourceDao: SourceDao,
): Flow<ActiveProfileSources> =
    settings.activeProfileId
        .flatMapLatest { pid ->
            if (pid < 0) {
                flowOf(ActiveProfileSources(pid, emptyList()))
            } else {
                combine(sourceDao.observeForProfile(pid), settings.defaultSourceId) { srcs, defaultId ->
                    val filtered = if (defaultId > 0 && srcs.any { it.id == defaultId }) {
                        srcs.filter { it.id == defaultId }
                    } else {
                        srcs
                    }
                    filtered.forEach(::seedSessionLimit)
                    ActiveProfileSources(pid, filtered)
                }
            }
        }
        .distinctUntilChanged()

/**
 * Tell the playback layer about a provider that allows only one stream at a time, using the value the
 * Xtream panel reported at sync (`user_info.max_connections`).
 *
 * Without this the app can only *learn* the limit by failing a tune and reading the 458 back, which means
 * one misbehaving zap after every app start. The learned cache is session-only by design (it is a guess);
 * the synced number is a fact, so it is safe to state up front.
 *
 * Hung off the shared source flow because that is the one place every screen — Home included — already
 * passes through, and it costs a set-add per source. It never *clears* the flag: a panel caught refusing
 * a second session stays flagged for the session even if it later reports a bigger number.
 */
private fun seedSessionLimit(s: SourceEntity) {
    if (s.maxConnections == 1 && s.url.isNotBlank()) {
        tv.own.owntv.core.player.LiveSessionLimit.singleSession(s.url)
    }
}

/**
 * One-shot version of [activeProfileSources] for imperative code paths: the [profileId]'s linked source
 * ids, narrowed to the active-playlist filter when one is set (else all). Empty when the profile has none.
 *
 * When [section] is set, only sources with that section's enabledScope flag On are returned.
 */
suspend fun activeSourceIds(
    settings: SettingsRepository,
    sourceDao: SourceDao,
    profileId: Long,
    section: MediaType? = null,
): List<Long> {
    val all = sourceDao.observeForProfile(profileId).first()
    val defaultId = settings.defaultSourceId.first()
    val filtered = if (defaultId > 0 && all.any { it.id == defaultId }) {
        all.filter { it.id == defaultId }
    } else {
        all
    }
    val scoped = when (section) {
        MediaType.LIVE -> filtered.filter { it.syncLive }
        MediaType.MOVIE -> filtered.filter { it.syncMovies }
        MediaType.SERIES, MediaType.EPISODE -> filtered.filter { it.syncSeries }
        null -> filtered
    }
    return scoped.map { it.id }
}
