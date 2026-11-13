package tv.own.owntv.core.database.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import tv.own.owntv.core.database.entity.SubtitleCacheEntity
import tv.own.owntv.core.database.entity.SubtitleLinkEntity
import tv.own.owntv.core.database.entity.SubtitleSelectionEntity
import tv.own.owntv.core.database.entity.SubtitleTimingEntity

/** A cached subtitle joined with the content it's linked to — for restore-on-replay and delete lists. */
data class LinkedSubtitle(
    val cacheId: Long,
    val contentKey: String,
    val contentTitle: String,
    val mediaType: String,
    val language: String?,
    val languageName: String?,
    val releaseName: String?,
    val format: String?,
    val fileName: String,
    val cachedPath: String,
    val openSubFileId: Long?,
    val addedAt: Long,
)

/** Data access for the external-subtitle tables. */
@Dao
interface SubtitleDao {

    // --- cache (device-level shared files) ---

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertCache(entry: SubtitleCacheEntity): Long

    /** Cache-first lookup: an existing download for this exact OpenSubtitles file → skip /download. */
    @Query("SELECT * FROM subtitle_cache WHERE openSubFileId = :fileId LIMIT 1")
    suspend fun findByOpenSubFileId(fileId: Long): SubtitleCacheEntity?

    @Query("SELECT * FROM subtitle_cache WHERE id = :id LIMIT 1")
    suspend fun cacheById(id: Long): SubtitleCacheEntity?

    /** Local-file dedupe: re-importing the same file reuses the managed copy. */
    @Query("SELECT * FROM subtitle_cache WHERE source = 'LOCAL' AND fileName = :fileName LIMIT 1")
    suspend fun findLocalByFileName(fileName: String): SubtitleCacheEntity?

    @Query("UPDATE subtitle_cache SET lastUsedAt = :now WHERE id = :id")
    suspend fun touchCache(id: Long, now: Long)

    @Query("DELETE FROM subtitle_cache WHERE id = :id")
    suspend fun deleteCache(id: Long)

    // --- selection (remembered active subtitle per profile + content) ---

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertSelection(selection: SubtitleSelectionEntity)

    @Query("SELECT * FROM subtitle_selection WHERE profileId = :profileId AND contentKey = :contentKey LIMIT 1")
    suspend fun selection(profileId: Long, contentKey: String): SubtitleSelectionEntity?

    // --- timing (per profile + content + exact subtitle) ---

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertTiming(timing: SubtitleTimingEntity)

    @Query("SELECT offsetMs FROM subtitle_timing WHERE profileId = :profileId AND contentKey = :contentKey AND subtitleKey = :subtitleKey LIMIT 1")
    suspend fun timingOffset(profileId: Long, contentKey: String, subtitleKey: String): Int?

    // --- links (cached subtitle ↔ movie/episode) ---

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertLink(link: SubtitleLinkEntity)

    /** All subtitles a profile downloaded for one movie/episode — restore-on-replay + per-item delete. */
    @Query(
        "SELECT c.id AS cacheId, l.contentKey AS contentKey, l.contentTitle AS contentTitle, " +
            "l.mediaType AS mediaType, c.language AS language, c.languageName AS languageName, " +
            "c.releaseName AS releaseName, c.format AS format, c.fileName AS fileName, " +
            "c.cachedPath AS cachedPath, c.openSubFileId AS openSubFileId, l.addedAt AS addedAt " +
            "FROM subtitle_link l JOIN subtitle_cache c ON c.id = l.cacheId " +
            "WHERE l.profileId = :profileId AND l.contentKey = :contentKey ORDER BY l.addedAt DESC",
    )
    suspend fun linksForContent(profileId: Long, contentKey: String): List<LinkedSubtitle>

    /** All subtitles a profile downloaded for a media type — the delete screen's Movies/Series list. */
    @Query(
        "SELECT c.id AS cacheId, l.contentKey AS contentKey, l.contentTitle AS contentTitle, " +
            "l.mediaType AS mediaType, c.language AS language, c.languageName AS languageName, " +
            "c.releaseName AS releaseName, c.format AS format, c.fileName AS fileName, " +
            "c.cachedPath AS cachedPath, c.openSubFileId AS openSubFileId, l.addedAt AS addedAt " +
            "FROM subtitle_link l JOIN subtitle_cache c ON c.id = l.cacheId " +
            "WHERE l.profileId = :profileId AND l.mediaType = :mediaType ORDER BY l.contentTitle, c.languageName",
    )
    suspend fun linksForType(profileId: Long, mediaType: String): List<LinkedSubtitle>

    /** Other profiles still referencing this cached file — deletes are profile-scoped, so the shared
     *  file is only removed once the LAST profile lets go of it. */
    @Query("SELECT COUNT(*) FROM subtitle_link WHERE cacheId = :cacheId AND profileId != :profileId")
    suspend fun linkCountForCacheOtherProfiles(cacheId: Long, profileId: Long): Int

    @Query("DELETE FROM subtitle_link WHERE profileId = :profileId AND cacheId = :cacheId")
    suspend fun deleteLinksForProfile(profileId: Long, cacheId: Long)

    /** Clears this profile's remembered selection of a cached sub it just deleted (the FK SET_NULL
     *  only fires when the shared cache row itself is removed, which a profile-scoped delete may not do). */
    @Query("UPDATE subtitle_selection SET cacheId = NULL WHERE profileId = :profileId AND cacheId = :cacheId")
    suspend fun clearSelectionsForCache(profileId: Long, cacheId: Long)

    /** cacheIds linked to a media type — used to delete their files before clearing the rows. */
    @Query("SELECT DISTINCT cacheId FROM subtitle_link WHERE profileId = :profileId AND mediaType = :mediaType")
    suspend fun cacheIdsForType(profileId: Long, mediaType: String): List<Long>

    /** Drop only this profile's links of ONE media type. "Delete all movie subtitles" must not take a
     *  file's series links with it — the same cached file can be linked to both. */
    @Query("DELETE FROM subtitle_link WHERE profileId = :profileId AND cacheId = :cacheId AND mediaType = :mediaType")
    suspend fun deleteLinksForProfileAndType(profileId: Long, cacheId: Long, mediaType: String)

    /** This profile's remaining links to a cached file, across every media type. */
    @Query("SELECT COUNT(*) FROM subtitle_link WHERE cacheId = :cacheId AND profileId = :profileId")
    suspend fun linkCountForCache(cacheId: Long, profileId: Long): Int

    // --- backup & restore (BackupManager) ---
    //
    // Whole-table reads, used only when writing a backup file. They are deliberately unfiltered: the
    // manager scopes rows to the ticked profiles and their sources, because it is the only place that
    // knows which those are.

    @Query("SELECT * FROM subtitle_cache")
    suspend fun allCacheOnce(): List<SubtitleCacheEntity>

    @Query("SELECT * FROM subtitle_selection")
    suspend fun allSelectionsOnce(): List<SubtitleSelectionEntity>

    @Query("SELECT * FROM subtitle_timing")
    suspend fun allTimingsOnce(): List<SubtitleTimingEntity>

    @Query("SELECT * FROM subtitle_link")
    suspend fun allLinksOnce(): List<SubtitleLinkEntity>
}
