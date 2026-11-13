package tv.own.owntv.core.live

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import tv.own.owntv.core.database.entity.EpgProgrammeEntity

class GuideSlotTest {

    private fun p(start: Long, stop: Long, title: String) =
        EpgProgrammeEntity(sourceId = 1, epgChannelId = "a", startMs = start, stopMs = stop, title = title)

    private val day = listOf(p(0, 100, "A"), p(100, 200, "B"), p(250, 300, "C"))

    @Test
    fun nowAndNext() {
        val slot = guideSlotAt(day, 50)!!
        assertEquals("A", slot.now?.title)
        assertEquals("B", slot.next?.title)
    }

    @Test
    fun aProgrammeEndingExactlyNowIsNotOn() = assertEquals("B", guideSlotAt(day, 100)?.now?.title)

    @Test
    fun aGapHasOnlyTheNext() {
        val slot = guideSlotAt(day, 220)!!
        assertNull(slot.now)
        assertEquals("C", slot.next?.title)
    }

    @Test
    fun afterTheLastProgrammeThereIsNothing() = assertNull(guideSlotAt(day, 400))

    @Test
    fun emptyRowIsNothing() = assertNull(guideSlotAt(emptyList(), 0))
}
