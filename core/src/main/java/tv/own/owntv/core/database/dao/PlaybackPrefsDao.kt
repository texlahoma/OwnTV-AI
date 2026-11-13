package tv.own.owntv.core.database.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Transaction
import kotlinx.coroutines.flow.Flow
import tv.own.owntv.core.database.entity.PlaybackPrefsEntity

/**
 * Per-item zoom / volume / audio delay the player remembers for one profile. See [PlaybackPrefsEntity] for why the
 * key is the stable content key rather than a Room id (no re-sync relink needed).
 *
 * The three values are written independently — changing the zoom must not wipe a remembered volume —
 * so each has its own upsert that preserves the other columns.
 */
@Dao
interface PlaybackPrefsDao {
    @Query("SELECT * FROM playback_prefs WHERE profileId = :profileId AND contentKey = :contentKey LIMIT 1")
    suspend fun get(profileId: Long, contentKey: String): PlaybackPrefsEntity?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(row: PlaybackPrefsEntity)

    /** Remember [zoomMode] (null = "follow the global default") without touching the volume. */
    @Transaction
    suspend fun setZoom(profileId: Long, contentKey: String, zoomMode: String?) {
        val existing = get(profileId, contentKey)
        if (existing == null && zoomMode == null) return
        upsert(
            PlaybackPrefsEntity(
                profileId = profileId,
                contentKey = contentKey,
                zoomMode = zoomMode,
                volumeBoost = existing?.volumeBoost,
                audioDelayMs = existing?.audioDelayMs,
                aiSubtitleEnabled = existing?.aiSubtitleEnabled,
                aiSubtitleSourceLanguage = existing?.aiSubtitleSourceLanguage,
            ),
        )
    }

    /** Remember [volumeBoost] (null = "follow the global default") without touching the zoom. */
    @Transaction
    suspend fun setVolume(profileId: Long, contentKey: String, volumeBoost: Int?) {
        val existing = get(profileId, contentKey)
        if (existing == null && volumeBoost == null) return
        upsert(
            PlaybackPrefsEntity(
                profileId = profileId,
                contentKey = contentKey,
                zoomMode = existing?.zoomMode,
                volumeBoost = volumeBoost,
                audioDelayMs = existing?.audioDelayMs,
                aiSubtitleEnabled = existing?.aiSubtitleEnabled,
                aiSubtitleSourceLanguage = existing?.aiSubtitleSourceLanguage,
            ),
        )
    }

    /** Remember [audioDelayMs] (null = "follow the global default") without touching zoom or volume. */
    @Transaction
    suspend fun setAudioDelay(profileId: Long, contentKey: String, audioDelayMs: Int?) {
        val existing = get(profileId, contentKey)
        if (existing == null && audioDelayMs == null) return
        upsert(
            PlaybackPrefsEntity(
                profileId = profileId,
                contentKey = contentKey,
                zoomMode = existing?.zoomMode,
                volumeBoost = existing?.volumeBoost,
                audioDelayMs = audioDelayMs,
                aiSubtitleEnabled = existing?.aiSubtitleEnabled,
                aiSubtitleSourceLanguage = existing?.aiSubtitleSourceLanguage,
            ),
        )
    }

    /** Remember [aiSubtitleEnabled] (null = "follow the global switch") without touching the rest. */
    @Transaction
    suspend fun setAiSubtitleEnabled(profileId: Long, contentKey: String, aiSubtitleEnabled: Boolean?) {
        val existing = get(profileId, contentKey)
        if (existing == null && aiSubtitleEnabled == null) return
        upsert(
            PlaybackPrefsEntity(
                profileId = profileId,
                contentKey = contentKey,
                zoomMode = existing?.zoomMode,
                volumeBoost = existing?.volumeBoost,
                audioDelayMs = existing?.audioDelayMs,
                aiSubtitleEnabled = aiSubtitleEnabled,
                aiSubtitleSourceLanguage = existing?.aiSubtitleSourceLanguage,
            ),
        )
    }

    /** Remember [aiSubtitleSourceLanguage] (null = "follow the global source language") without touching the rest. */
    @Transaction
    suspend fun setAiSubtitleSourceLanguage(profileId: Long, contentKey: String, aiSubtitleSourceLanguage: String?) {
        val existing = get(profileId, contentKey)
        if (existing == null && aiSubtitleSourceLanguage == null) return
        upsert(
            PlaybackPrefsEntity(
                profileId = profileId,
                contentKey = contentKey,
                zoomMode = existing?.zoomMode,
                volumeBoost = existing?.volumeBoost,
                audioDelayMs = existing?.audioDelayMs,
                aiSubtitleEnabled = existing?.aiSubtitleEnabled,
                aiSubtitleSourceLanguage = aiSubtitleSourceLanguage,
            ),
        )
    }

    /** Everything, for Backup & Restore. */
    @Query("SELECT * FROM playback_prefs")
    suspend fun getAllOnce(): List<PlaybackPrefsEntity>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertAll(rows: List<PlaybackPrefsEntity>)

    // --- The two Settings escape hatches. Zoom and volume are reset independently: a user who wants
    // every film back at the default aspect has not asked to lose the levels they set on quiet ones.
    // Each clear nulls only its own column, then drops rows that no longer remember anything.

    @Query("UPDATE playback_prefs SET zoomMode = NULL")
    suspend fun clearZoomColumn()

    @Query("UPDATE playback_prefs SET volumeBoost = NULL")
    suspend fun clearVolumeColumn()

    @Query("UPDATE playback_prefs SET audioDelayMs = NULL")
    suspend fun clearAudioDelayColumn()

    @Query(
        "DELETE FROM playback_prefs WHERE zoomMode IS NULL AND volumeBoost IS NULL AND audioDelayMs IS NULL " +
            "AND aiSubtitleEnabled IS NULL AND aiSubtitleSourceLanguage IS NULL",
    )
    suspend fun dropEmptyRows()

    /** Forget every per-item zoom, keeping the per-item volumes. */
    @Transaction
    suspend fun clearZoom() {
        clearZoomColumn()
        dropEmptyRows()
    }

    /** Forget every per-item volume, keeping the per-item zoom modes. */
    @Transaction
    suspend fun clearVolume() {
        clearVolumeColumn()
        dropEmptyRows()
    }

    /** Forget every per-item audio delay, keeping the per-item zoom modes and volumes. */
    @Transaction
    suspend fun clearAudioDelay() {
        clearAudioDelayColumn()
        dropEmptyRows()
    }

    /** Live counts for the Settings rows' chips ("3 saved" / "None saved"). */
    @Query("SELECT COUNT(*) FROM playback_prefs WHERE zoomMode IS NOT NULL")
    fun observeZoomCount(): Flow<Int>

    @Query("SELECT COUNT(*) FROM playback_prefs WHERE volumeBoost IS NOT NULL")
    fun observeVolumeCount(): Flow<Int>

    @Query("SELECT COUNT(*) FROM playback_prefs WHERE audioDelayMs IS NOT NULL")
    fun observeAudioDelayCount(): Flow<Int>
}
