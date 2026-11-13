package tv.own.owntv.core.content

import tv.own.owntv.core.database.entity.EpisodeEntity

/**
 * The order a show is watched in: the regular seasons in turn, then the Specials (season 0) after the
 * last of them. Sorting by season number put the Specials first, so Play on a show nobody had started
 * opened a special from years later instead of S1 E1 — invisible until Specials actually arrived from
 * Xtream panels. Every "what plays next" / "where does it start" answer in both apps uses this.
 *
 * The season tabs follow the user's Episode order setting instead (Specials last either way).
 */
object EpisodeOrder {

    /** A season's place in the watching order: Specials after every regular season. */
    private fun rank(season: Int): Int = if (season == 0) Int.MAX_VALUE else season

    /** Episodes in watching order. */
    val episodes: Comparator<EpisodeEntity> =
        compareBy<EpisodeEntity> { rank(it.seasonNumber) }.thenBy { it.episodeNumber }

    /** The season a show opens on: its first regular season, or the Specials when that is all it has. */
    fun openingSeason(seasons: Iterable<Int>): Int? = seasons.minByOrNull(::rank)

    /** The season watched after [season] — skipping any gap in the numbering — or null after the last. */
    fun seasonAfter(seasons: Iterable<Int>, season: Int): Int? =
        seasons.filter { rank(it) > rank(season) }.minByOrNull(::rank)
}
