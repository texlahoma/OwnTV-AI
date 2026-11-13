package tv.own.owntv.core.database.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import tv.own.owntv.core.database.entity.MetadataCacheEntity
import tv.own.owntv.core.database.entity.MetadataMatchEntity

/** DAO for the TMDB enrichment cache. Both tables are pure caches. */
@Dao
interface MetadataDao {

    // --- local item → tmdb resolution (incl. negative cache) ---

    @Query("SELECT * FROM metadata_match WHERE localKey = :localKey LIMIT 1")
    suspend fun getMatch(localKey: String): MetadataMatchEntity?

    /**
     * Batch read — one query for a whole page of grid tiles instead of one per tile. Same reason as
     * [getCaches]: the poster fallback needs every visible item at once, not N suspend round trips.
     */
    @Query("SELECT * FROM metadata_match WHERE localKey IN (:keys)")
    suspend fun getMatches(keys: List<String>): List<MetadataMatchEntity>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertMatch(match: MetadataMatchEntity)

    @Query("DELETE FROM metadata_match WHERE localKey = :localKey")
    suspend fun deleteMatch(localKey: String)

    // --- resolved TMDB metadata ---

    @Query("SELECT * FROM metadata_cache WHERE key = :key LIMIT 1")
    suspend fun getCache(key: String): MetadataCacheEntity?

    /**
     * Batch read — one query for a whole season instead of one per episode. The episode grid needs
     * every tile at once, and doing that as N suspend round trips was visible as the pictures taking a
     * moment to appear on a switch back to a season already held.
     */
    @Query("SELECT * FROM metadata_cache WHERE key IN (:keys)")
    suspend fun getCaches(keys: List<String>): List<MetadataCacheEntity>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertCache(entity: MetadataCacheEntity)

    /** Batch write — a season bundle lands ~60 rows; one transaction beats 60. */
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertCaches(entities: List<MetadataCacheEntity>)

    @Query("DELETE FROM metadata_cache WHERE key = :key")
    suspend fun deleteCache(key: String)

    // --- maintenance (manual "clear metadata" / refresh) ---

    @Query("DELETE FROM metadata_cache")
    suspend fun clearCache()

    /**
     * Drop only the "searched, found nothing" rows, keeping the expensive title→tmdbId results. Used
     * whenever the conditions that produced a miss may have changed (metadata language switch, a fix to
     * the matcher) — otherwise a miss sticks around for its 7-day negative TTL.
     */
    @Query("DELETE FROM metadata_match WHERE tmdbId IS NULL")
    suspend fun clearNegativeMatches()

    // --- bounded eviction: both tables grow unbounded as the user browses a ~220k-item
    //     catalog. TTL delete rides the existing Index("updatedAt"); rows re-fetch on next focus.
    //     Run off the cold-start path (after a sync completes — ImportFinalizer.finalize). ---

    @Query("DELETE FROM metadata_cache WHERE updatedAt < :cutoff")
    suspend fun evictCacheOlderThan(cutoff: Long): Int

    @Query("DELETE FROM metadata_match WHERE updatedAt < :cutoff")
    suspend fun evictMatchesOlderThan(cutoff: Long): Int
}
