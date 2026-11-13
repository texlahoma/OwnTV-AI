package tv.own.owntv.core.database.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Transaction
import kotlinx.coroutines.flow.Flow
import tv.own.owntv.core.database.entity.TrendingItemEntity
import tv.own.owntv.core.database.entity.TrendingAttemptStatus
import tv.own.owntv.core.database.entity.TrendingSnapshot
import tv.own.owntv.core.database.entity.TrendingSnapshotEntity
import tv.own.owntv.core.database.entity.TrendingSnapshotStatus
import tv.own.owntv.core.model.MediaType

@Dao
interface TrendingDao {
    @Query("SELECT * FROM trending_snapshots WHERE sourceId = :sourceId LIMIT 1")
    suspend fun getState(sourceId: Long): TrendingSnapshotEntity?

    @Query("SELECT * FROM trending_items WHERE sourceId = :sourceId ORDER BY position")
    suspend fun getItems(sourceId: Long): List<TrendingItemEntity>

    @Query("SELECT * FROM trending_items WHERE sourceId IN (:sourceIds) ORDER BY sourceId, position")
    suspend fun getItemsForSources(sourceIds: List<Long>): List<TrendingItemEntity>

    @Query("SELECT * FROM trending_items ORDER BY sourceId, position")
    fun observeAllItems(): Flow<List<TrendingItemEntity>>

    @Query("SELECT * FROM trending_snapshots WHERE sourceId IN (:sourceIds) ORDER BY sourceId")
    fun observeStatesForSources(sourceIds: List<Long>): Flow<List<TrendingSnapshotEntity>>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertState(state: TrendingSnapshotEntity)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertItems(items: List<TrendingItemEntity>)

    @Query("DELETE FROM trending_items WHERE sourceId = :sourceId")
    suspend fun deleteItems(sourceId: Long)

    @Query(
        "UPDATE trending_snapshots SET lastAttemptAt = :attemptAt, lastAttemptStatus = 'FAILED', " +
            "failureStage = :stage WHERE sourceId = :sourceId",
    )
    suspend fun markFailureExisting(sourceId: Long, attemptAt: Long, stage: String): Int

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insertStateIfMissing(state: TrendingSnapshotEntity): Long

    @Transaction
    suspend fun recordFailure(stateIfMissing: TrendingSnapshotEntity, stage: String) {
        val changed = markFailureExisting(stateIfMissing.sourceId, stateIfMissing.lastAttemptAt, stage)
        if (changed == 0) insertStateIfMissing(stateIfMissing)
    }

    @Transaction
    suspend fun getSnapshot(sourceId: Long): TrendingSnapshot? {
        val state = getState(sourceId) ?: return null
        return TrendingSnapshot(state, getItems(sourceId))
    }

    /** Commit point for a successful eligible refresh; source B is never touched. */
    @Transaction
    suspend fun replaceSnapshot(state: TrendingSnapshotEntity, items: List<TrendingItemEntity>) {
        validateReplacement(state, items)
        deleteItems(state.sourceId)
        upsertState(state)
        if (items.isNotEmpty()) insertItems(items)
    }

    /** A successful response with fewer than five matches replaces the old eligible snapshot. */
    @Transaction
    suspend fun writeBelowThreshold(state: TrendingSnapshotEntity) {
        require(state.status == TrendingSnapshotStatus.BELOW_THRESHOLD)
        require(state.itemCount == 0)
        require(state.lastAttemptStatus == TrendingAttemptStatus.BELOW_THRESHOLD)
        deleteItems(state.sourceId)
        upsertState(state)
    }

    private fun validateReplacement(state: TrendingSnapshotEntity, items: List<TrendingItemEntity>) {
        require(state.itemCount == items.size)
        require(state.lastAttemptStatus == TrendingAttemptStatus.SUCCESS)
        require(items.size <= MAX_TRENDING_ITEMS)
        require(items.map { it.position } == items.indices.toList())
        require(items.all { it.sourceId == state.sourceId && it.generationId == state.generationId })
        require(items.all { it.mediaType == MediaType.MOVIE || it.mediaType == MediaType.SERIES })
        require(items.count { it.mediaType == MediaType.MOVIE } <= MAX_ITEMS_PER_MEDIA_TYPE)
        require(items.count { it.mediaType == MediaType.SERIES } <= MAX_ITEMS_PER_MEDIA_TYPE)
        require(
            if (state.status == TrendingSnapshotStatus.ELIGIBLE) {
                items.size >= MIN_ELIGIBLE_ITEMS
            } else {
                items.isEmpty()
            },
        )
    }

    companion object {
        const val MIN_ELIGIBLE_ITEMS = 4
        const val MAX_ITEMS_PER_MEDIA_TYPE = 10
        const val MAX_TRENDING_ITEMS = 10
    }
}
