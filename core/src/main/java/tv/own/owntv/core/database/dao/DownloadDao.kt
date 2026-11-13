package tv.own.owntv.core.database.dao

import androidx.room.Dao
import androidx.room.Delete
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import kotlinx.coroutines.flow.Flow
import tv.own.owntv.core.database.entity.DownloadEntity
import tv.own.owntv.core.model.DownloadReason
import tv.own.owntv.core.model.DownloadStatus

/** Downloads (movies & series episodes only), per profile. */
@Dao
interface DownloadDao {
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(download: DownloadEntity): Long

    @Delete
    suspend fun delete(download: DownloadEntity)

    @Query("SELECT * FROM downloads WHERE id = :id")
    suspend fun getById(id: Long): DownloadEntity?

    @Query("SELECT * FROM downloads WHERE profileId = :profileId ORDER BY createdAt DESC")
    fun observeForProfile(profileId: Long): Flow<List<DownloadEntity>>

    /** Episode downloads belonging to one series (for the series poster-panel aggregate status strip). */
    @Query(
        "SELECT d.* FROM downloads d INNER JOIN episodes e ON e.id = d.itemId " +
            "WHERE d.mediaType = 'EPISODE' AND e.seriesId = :seriesId",
    )
    fun observeForSeries(seriesId: Long): Flow<List<DownloadEntity>>

    @Query("SELECT * FROM downloads WHERE status = :status ORDER BY createdAt ASC")
    suspend fun byStatus(status: DownloadStatus): List<DownloadEntity>

    /**
     * The download queue, oldest first. RUNNING counts as pending: a row is only left in that state
     * when the process died mid-transfer, and it resumes from the partial file.
     */
    @Query("SELECT * FROM downloads WHERE status IN ('QUEUED', 'RUNNING') ORDER BY createdAt ASC")
    suspend fun pending(): List<DownloadEntity>

    @Query("SELECT COUNT(*) FROM downloads WHERE profileId = :profileId")
    fun count(profileId: Long): Flow<Int>

    /** Every status change also sets the row's reason, so an old failure never outlives a retry. */
    @Query(
        "UPDATE downloads SET status = :status, downloadedBytes = :downloaded, totalBytes = :total, " +
            "updatedAt = :timestamp, reason = :reason, httpCode = :httpCode WHERE id = :id",
    )
    suspend fun updateProgress(
        id: Long, status: DownloadStatus, downloaded: Long, total: Long, timestamp: Long,
        reason: DownloadReason?, httpCode: Int?,
    )
}
