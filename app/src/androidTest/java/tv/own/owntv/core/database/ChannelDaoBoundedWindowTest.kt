package tv.own.owntv.core.database

import android.content.Context
import androidx.room.Room
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import tv.own.owntv.core.database.dao.ChannelDao
import tv.own.owntv.core.database.entity.ChannelEntity
import tv.own.owntv.core.model.MediaType
import tv.own.owntv.core.model.SourceType

/**
 * Behavioral tests for the bounded window queries added for direct-tune's zap-list rebuild.
 * These verify the (sortOrder, id) cursor predicate matches the ORDER BY so no channel slips
 * through the gap when sortOrder ties — the regression the DAO was fixed for.
 */
@RunWith(AndroidJUnit4::class)
class ChannelDaoBoundedWindowTest {
    private val context: Context = InstrumentationRegistry.getInstrumentation().targetContext
    private lateinit var db: OwnTVDatabase
    private lateinit var dao: ChannelDao

    @Before
    fun setUp() {
        db = Room.inMemoryDatabaseBuilder(context, OwnTVDatabase::class.java)
            .allowMainThreadQueries()
            .build()
        dao = db.channelDao()
        runBlocking {
            db.sourceDao().seedSources(
                listOf(
                    SourceSeed(id = 10, name = "Provider A"),
                    SourceSeed(id = 20, name = "Provider B"),
                ),
            )
            db.categoryDao().seedCategories(
                listOf(
                    CategorySeed(id = 100, sourceId = 10, mediaType = MediaType.LIVE, name = "Live", sortOrder = 0),
                    CategorySeed(id = 200, sourceId = 20, mediaType = MediaType.LIVE, name = "Live", sortOrder = 0),
                ),
            )
        }
    }

    @After
    fun tearDown() {
        db.close()
    }

    @Test
    fun findByNumber_returnsAllMatchingChannels_acrossSources() = runBlocking {
        seedCategory(
            categoryId = 100, sourceId = 10,
            channels = listOf(
                ChannelSpec(id = 1L, name = "A-News",   number = 5, sortOrder = 0),
                ChannelSpec(id = 2L, name = "A-Sports", number = 5, sortOrder = 1),  // duplicate number
            ),
        )
        seedCategory(
            categoryId = 200, sourceId = 20,
            channels = listOf(
                ChannelSpec(id = 3L, name = "B-News",   number = 5, sortOrder = 0),
            ),
        )

        val results = dao.findByNumber(listOf(10L, 20L), 5)
        assertEquals(3, results.size)
        assertEquals(setOf(1L, 2L, 3L), results.map { it.id }.toSet())
    }

    @Test
    fun findByNumber_returnsEmptyForUnknownNumber() = runBlocking {
        seedCategory(
            categoryId = 100, sourceId = 10,
            channels = listOf(
                ChannelSpec(id = 1L, name = "A-News",   number = 5, sortOrder = 0),
            ),
        )
        assertEquals(emptyList<ChannelEntity>(), dao.findByNumber(listOf(10L), 99))
    }

    /**
     * findByNumber must return duplicates in a stable, deterministic order so the resolver's
     * single-match fast path is meaningful. The ORDER BY is (sourceId, sortOrder, name, id).
     */
    @Test
    fun findByNumber_duplicateNumber_returnsInDeterministicOrder() = runBlocking {
        seedCategory(
            categoryId = 100, sourceId = 10,
            channels = listOf(
                ChannelSpec(id = 1L, name = "Bravo", number = 7, sortOrder = 0),
                ChannelSpec(id = 2L, name = "Alpha", number = 7, sortOrder = 0),  // same sortOrder, name sorts first
                ChannelSpec(id = 3L, name = "Alpha", number = 7, sortOrder = 0),  // same name + sortOrder → id tiebreaker
                ChannelSpec(id = 4L, name = "Charlie", number = 7, sortOrder = 1),
            ),
        )

        val results = dao.findByNumber(listOf(10L), 7)
        assertEquals(listOf(2L, 3L, 1L, 4L), results.map { it.id })
    }

    /**
     * Two sources with the same provider number → current-source wins. The resolver relies on
     * findByNumber returning the current-source row first when both sources are queried.
     */
    @Test
    fun findByNumber_crossSource_currentSourceWins() = runBlocking {
        seedCategory(
            categoryId = 100, sourceId = 10,
            channels = listOf(
                ChannelSpec(id = 1L, name = "SourceA-Ch42", number = 42, sortOrder = 0),
            ),
        )
        seedCategory(
            categoryId = 200, sourceId = 20,
            channels = listOf(
                ChannelSpec(id = 2L, name = "SourceB-Ch42", number = 42, sortOrder = 0),
            ),
        )

        // Querying both together: source 10 (current) comes first.
        val both = dao.findByNumber(listOf(10L, 20L), 42)
        assertEquals(listOf(1L, 2L), both.map { it.id })
    }

    @Test
    fun channelsAfterCategory_excludesTunedChannel_andStopsAtLimit() = runBlocking {
        // 8 channels in category 100; tuned channel is id=5 (sortOrder=4). Half-window=3 means
        // the next 3 strictly-after rows: id=6,7,8.
        seedCategory(
            categoryId = 100, sourceId = 10,
            channels = (1L..8L).map { i ->
                ChannelSpec(id = i, name = "Ch$i", number = i.toInt(), sortOrder = i.toInt() - 1)
            },
        )

        val after = dao.channelsAfterCategory(
            categoryId = 100, afterSortOrder = 4, afterId = 5L, limit = 3,
        )
        assertEquals(listOf(6L, 7L, 8L), after.map { it.id })
    }

    @Test
    fun channelsBeforeCategory_returnsInReverse_andExcludesTunedChannel() = runBlocking {
        // Tuned channel is id=5 (sortOrder=4). Half-window=3 means the 3 strictly-before rows
        // (id=4,3,2) in DESC order.
        seedCategory(
            categoryId = 100, sourceId = 10,
            channels = (1L..8L).map { i ->
                ChannelSpec(id = i, name = "Ch$i", number = i.toInt(), sortOrder = i.toInt() - 1)
            },
        )

        val before = dao.channelsBeforeCategory(
            categoryId = 100, beforeSortOrder = 4, beforeId = 5L, limit = 3,
        )
        assertEquals(listOf(4L, 3L, 2L), before.map { it.id })
    }

    /**
     * The exact regression: sortOrder ties. Three channels at sortOrder=5, ids 10/11/12; tuned
     * channel is id=11. After-query must return id=12 (strictly greater), never id=10 or 11 —
     * the old `(sortOrder > x)` predicate would have skipped id=12 entirely.
     */
    @Test
    fun channelsAfterCategory_handlesSortOrderTie_viaIdTiebreaker() = runBlocking {
        seedCategory(
            categoryId = 100, sourceId = 10,
            channels = listOf(
                ChannelSpec(id = 10L, name = "Tied-A", number = 1, sortOrder = 5),
                ChannelSpec(id = 11L, name = "Tuned",  number = 2, sortOrder = 5),  // tuned
                ChannelSpec(id = 12L, name = "Tied-B", number = 3, sortOrder = 5),  // must appear in AFTER
                ChannelSpec(id = 13L, name = "Tied-C", number = 4, sortOrder = 5),  // must appear in AFTER after id=12
            ),
        )

        val after = dao.channelsAfterCategory(
            categoryId = 100, afterSortOrder = 5, afterId = 11L, limit = 10,
        )
        assertEquals(listOf(12L, 13L), after.map { it.id })
    }

    @Test
    fun channelsBeforeCategory_handlesSortOrderTie_viaIdTiebreaker() = runBlocking {
        seedCategory(
            categoryId = 100, sourceId = 10,
            channels = listOf(
                ChannelSpec(id = 10L, name = "Tied-A", number = 1, sortOrder = 5),  // must appear in BEFORE
                ChannelSpec(id = 11L, name = "Tied-B", number = 2, sortOrder = 5),  // must appear in BEFORE
                ChannelSpec(id = 12L, name = "Tuned",  number = 3, sortOrder = 5),  // tuned
                ChannelSpec(id = 13L, name = "Tied-C", number = 4, sortOrder = 5),
            ),
        )

        val before = dao.channelsBeforeCategory(
            categoryId = 100, beforeSortOrder = 5, beforeId = 12L, limit = 10,
        )
        // DESC order: id=11 first, then id=10.
        assertEquals(listOf(11L, 10L), before.map { it.id })
    }

    @Test
    fun channelsAfterSource_spansAcrossCategories() = runBlocking {
        // Source 10 has two categories. Tuned channel in cat 100; AFTER must include channels
        // from cat 200 that have a higher sortOrder than the tuned channel.
        seedCategory(
            categoryId = 100, sourceId = 10,
            channels = listOf(
                ChannelSpec(id = 1L, name = "Cat100-A", number = 1, sortOrder = 0),
                ChannelSpec(id = 2L, name = "Cat100-B", number = 2, sortOrder = 1),  // tuned
            ),
        )
        seedCategory(
            categoryId = 200, sourceId = 10,
            channels = listOf(
                ChannelSpec(id = 3L, name = "Cat200-A", number = 3, sortOrder = 2),
                ChannelSpec(id = 4L, name = "Cat200-B", number = 4, sortOrder = 3),
            ),
        )

        val after = dao.channelsAfterSource(
            sourceId = 10, afterSortOrder = 1, afterId = 2L, limit = 10,
        )
        assertEquals(listOf(3L, 4L), after.map { it.id })
    }

    @Test
    fun channelsAfterSource_isolatesBySource() = runBlocking {
        // Two sources with channels at the same sortOrder. AFTER in source 10 must NOT include
        // channels from source 20.
        seedCategory(
            categoryId = 100, sourceId = 10,
            channels = listOf(
                ChannelSpec(id = 1L, name = "A-1", number = 1, sortOrder = 0),
                ChannelSpec(id = 2L, name = "A-2", number = 2, sortOrder = 1),  // tuned
                ChannelSpec(id = 3L, name = "A-3", number = 3, sortOrder = 2),
            ),
        )
        seedCategory(
            categoryId = 200, sourceId = 20,
            channels = listOf(
                ChannelSpec(id = 4L, name = "B-1", number = 1, sortOrder = 0),
                ChannelSpec(id = 5L, name = "B-2", number = 2, sortOrder = 1),
                ChannelSpec(id = 6L, name = "B-3", number = 3, sortOrder = 2),
            ),
        )

        val after = dao.channelsAfterSource(
            sourceId = 10, afterSortOrder = 1, afterId = 2L, limit = 10,
        )
        assertEquals(listOf(3L), after.map { it.id })
        // Sanity: the source-20 channels really do exist with the same sortOrders.
        val after20 = dao.channelsAfterSource(
            sourceId = 20, afterSortOrder = 1, afterId = 5L, limit = 10,
        )
        assertEquals(listOf(6L), after20.map { it.id })
    }

    @Test
    fun channelsBeforeSource_handlesSortOrderTie() = runBlocking {
        seedCategory(
            categoryId = 100, sourceId = 10,
            channels = listOf(
                ChannelSpec(id = 10L, name = "Tied-A", number = 1, sortOrder = 5),
                ChannelSpec(id = 11L, name = "Tied-B", number = 2, sortOrder = 5),
                ChannelSpec(id = 12L, name = "Tuned",  number = 3, sortOrder = 5),
                ChannelSpec(id = 13L, name = "Tied-C", number = 4, sortOrder = 5),
            ),
        )

        val before = dao.channelsBeforeSource(
            sourceId = 10, beforeSortOrder = 5, beforeId = 12L, limit = 10,
        )
        assertEquals(listOf(11L, 10L), before.map { it.id })
    }

    /**
     * Integration check for the direct-tune resolver: findByNumber returns all candidates in
     * a deterministic order, and resolveDirectTuneCandidate applies the zap-context tiebreaker
     * exactly once per (source, number) group. Hidden channels are filtered before resolution
     * (LiveViewModel does that), so we test with an unfiltered candidate set here.
     */
    @Test
    fun resolveDirectTune_integration_withDaoCandidates() = runBlocking {
        seedCategory(
            categoryId = 100, sourceId = 10,
            channels = listOf(
                ChannelSpec(id = 1L, name = "News-A",   number = 42, sortOrder = 0),
                ChannelSpec(id = 2L, name = "Sports-A", number = 42, sortOrder = 1),  // duplicate
                ChannelSpec(id = 3L, name = "Movies-A", number = 42, sortOrder = 2),  // duplicate
            ),
        )

        val candidates = dao.findByNumber(listOf(10L), 42)
            .map { it.id }

        // No zap context → ambiguous (3 candidates, none in zap).
        assertNull(tv.own.owntv.player.resolveDirectTuneCandidate(candidates, emptySet()))

        // Exactly one in zap context → that one wins.
        assertEquals(2L, tv.own.owntv.player.resolveDirectTuneCandidate(candidates, setOf(2L)))

        // Two in zap context → still ambiguous (multiple visible duplicates).
        assertNull(tv.own.owntv.player.resolveDirectTuneCandidate(candidates, setOf(1L, 2L)))
    }

    /**
     * Cross-source fallback integration: when the current source has no match, the other active
     * sources are queried and the resolver applies the same uniqueness/zap-context policy.
     */
    @Test
    fun resolveDirectTune_crossSource_fallbackPicksUniqueMatch() = runBlocking {
        seedCategory(
            categoryId = 100, sourceId = 10,
            channels = listOf(
                ChannelSpec(id = 1L, name = "SourceA-Other", number = 99, sortOrder = 0),
            ),
        )
        seedCategory(
            categoryId = 200, sourceId = 20,
            channels = listOf(
                ChannelSpec(id = 2L, name = "SourceB-Ch42", number = 42, sortOrder = 0),
            ),
        )

        // Current source (10) has no match → fallback to source 20 → unique match.
        val currentCandidates = dao.findByNumber(listOf(10L), 42)
        val fallbackCandidates = dao.findByNumber(listOf(20L), 42)
            .map { it.id }
        assertEquals(emptyList<ChannelEntity>(), currentCandidates)
        assertEquals(2L, tv.own.owntv.player.resolveDirectTuneCandidate(fallbackCandidates, emptySet()))
    }

    // --- helpers ---

    private suspend fun seedCategory(
        categoryId: Long,
        sourceId: Long,
        channels: List<ChannelSpec>,
    ) {
        dao.upsertAll(
            channels.map { spec ->
                ChannelEntity(
                    id = spec.id,
                    sourceId = sourceId,
                    categoryId = categoryId,
                    name = spec.name,
                    streamUrl = "https://example.test/${spec.id}",
                    number = spec.number,
                    sortOrder = spec.sortOrder,
                )
            },
        )
    }

    private data class ChannelSpec(val id: Long, val name: String, val number: Int, val sortOrder: Int)
}

// Tiny helpers so the test stays readable (avoid pulling in every DAO surface).
private suspend fun tv.own.owntv.core.database.dao.SourceDao.seedSources(seed: List<SourceSeed>) {
    for (s in seed) insert(s.toEntity())
}

private data class SourceSeed(val id: Long, val name: String) {
    fun toEntity() = tv.own.owntv.core.database.entity.SourceEntity(
        id = id, name = name, type = SourceType.XTREAM, url = "https://example.test/$id",
    )
}

private suspend fun tv.own.owntv.core.database.dao.CategoryDao.seedCategories(seed: List<CategorySeed>) {
    upsertAll(seed.map { it.toEntity() })
}

private data class CategorySeed(
    val id: Long, val sourceId: Long, val mediaType: MediaType, val name: String, val sortOrder: Int,
) {
    fun toEntity() = tv.own.owntv.core.database.entity.CategoryEntity(
        id = id, sourceId = sourceId, mediaType = mediaType, name = name, sortOrder = sortOrder,
    )
}