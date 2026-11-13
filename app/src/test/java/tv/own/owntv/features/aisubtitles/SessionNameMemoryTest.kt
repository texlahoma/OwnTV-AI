package tv.own.owntv.features.aisubtitles

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Pins the session memory that lets a later bare surname be attributed from what the commentary said
 * earlier. It is deliberately narrow: one recent whole name is evidence, two are ambiguity, and an
 * expired one is nothing at all.
 */
class SessionNameMemoryTest {

    private val fixture = setOf(SubtitleGlossary.Team.BRENTFORD, SubtitleGlossary.Team.LEEDS)

    @Test
    fun `one recently heard whole name is offered for its surname`() {
        val memory = SessionNameMemory()
        memory.remember("Callum Wilson leads the line", fixture, nowMs = 1_000)

        assertEquals(mapOf("wilson" to "Callum Wilson"), memory.recentNames(nowMs = 2_000))
    }

    @Test
    fun `both men of a shared surname leave the memory with nothing to offer`() {
        val memory = SessionNameMemory()
        memory.remember("Callum Wilson and Harry Wilson", fixture, nowMs = 1_000)

        assertTrue(memory.recentNames(nowMs = 2_000).isEmpty())
    }

    @Test
    fun `an old whole name expires`() {
        val memory = SessionNameMemory(nameTtlMs = 1_000)
        memory.remember("Callum Wilson leads the line", fixture, nowMs = 0)

        assertTrue(memory.recentNames(nowMs = 1_500).isEmpty())
    }

    @Test
    fun `only whole names of the scoped fixture are remembered`() {
        val memory = SessionNameMemory()
        memory.remember("Callum Wilson leads the line", setOf(SubtitleGlossary.Team.ARSENAL), nowMs = 0)

        assertTrue(memory.recentNames(nowMs = 1).isEmpty())
    }

    @Test
    fun `a club mention is a tiebreaker only inside its window`() {
        val memory = SessionNameMemory(clubWindowMs = 1_000)
        memory.noteClubs("Brentford win a corner", nowMs = 0)

        assertEquals(setOf(SubtitleGlossary.Team.BRENTFORD), memory.recentClubs(nowMs = 500))
        assertTrue(memory.recentClubs(nowMs = 1_500).isEmpty())
    }

    @Test
    fun `clearing forgets names and clubs alike`() {
        val memory = SessionNameMemory()
        memory.remember("Callum Wilson leads the line", fixture, nowMs = 0)
        memory.noteClubs("Leeds answer through Harry Wilson", nowMs = 0)

        memory.clear()

        assertTrue(memory.recentNames(nowMs = 1).isEmpty())
        assertTrue(memory.recentClubs(nowMs = 1).isEmpty())
    }
}
