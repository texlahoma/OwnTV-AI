package tv.own.owntv.core.database

import androidx.room.useReaderConnection
import androidx.test.ext.junit.runners.AndroidJUnit4
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import tv.own.owntv.core.database.entity.EpgProgrammeEntity

/**
 * The Sports hub's two guide reads: what they return, and that the slot read seeks the
 * `(startMs, stopMs)` index rather than the `stopMs` one — the difference between a few thousand rows
 * and every future programme in the guide.
 */
@RunWith(AndroidJUnit4::class)
class SportsGuideDaoTest {

    private lateinit var db: OwnTVDatabase

    @Before
    fun setUp() {
        db = ownTVTestDatabase()
    }

    @After
    fun tearDown() = db.close()

    @Test
    fun theSlotReadReturnsProgrammesStartingInTheWindowWithoutSynopsis() = runBlocking {
        db.epgDao().upsertProgrammes(
            listOf(
                programme("sky.de", 1_000, "Bundesliga: Bayern – Köln", "Long synopsis"),
                programme("dazn1", 5_000, "Darts"),
                programme("bbc1", 50_000, "News"), // after the window
            ),
        )

        val rows = db.epgDao().sportsProgrammes(from = 0, to = 10_000, minStop = 0, limit = 100)

        assertEquals(listOf("sky.de", "dazn1"), rows.sortedBy { it.startMs }.map { it.epgChannelId })
        val descriptions = db.epgDao().programmeDescriptions(rows.map { it.id })
        assertEquals(listOf("Long synopsis"), descriptions.map { it.description })
    }

    @Test
    fun theSlotReadUsesTheStartIndex() = runBlocking {
        val plan = db.useReaderConnection { conn ->
            conn.usePrepared(
                "EXPLAIN QUERY PLAN SELECT id, epgChannelId, startMs, stopMs, title, categories FROM epg_programmes " +
                    "WHERE startMs >= ? AND startMs < ? AND +stopMs > ? LIMIT ?",
            ) { st ->
                st.bindLong(1, 0); st.bindLong(2, 10_000); st.bindLong(3, 0); st.bindLong(4, 100)
                buildList { while (st.step()) add(st.getText(3)) }.joinToString(" | ")
            }
        }
        assertTrue(plan, plan.contains("index_epg_programmes_startMs_stopMs"))
    }

    private fun programme(key: String, start: Long, title: String, description: String? = null) =
        EpgProgrammeEntity(sourceId = 1, epgChannelId = key, startMs = start, stopMs = start + 7_200_000, title = title, description = description)
}
