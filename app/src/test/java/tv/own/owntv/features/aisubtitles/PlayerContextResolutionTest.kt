package tv.own.owntv.features.aisubtitles

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The cross-sentence half of the shared-surname resolution: a bare `Wilson` can now be attributed
 * from what the commentary said a moment ago, not only from a club named in the same sentence. The
 * order of evidence is the contract — a fresh mention outranks a stale memory, and nothing is
 * guessed when the evidence does not single out one man.
 */
class PlayerContextResolutionTest {

    private val fixture = setOf(SubtitleGlossary.Team.BRENTFORD, SubtitleGlossary.Team.LEEDS)

    @Test
    fun `a recently heard whole name resolves a bare shared surname`() {
        assertEquals(
            "Callum Wilson drives forward",
            SubtitleGlossary.resolveSurnames(
                "Wilson drives forward",
                fixture,
                rememberedNames = mapOf("wilson" to "Callum Wilson"),
            ),
        )
        assertEquals(
            "Harry Wilson drives forward",
            SubtitleGlossary.resolveSurnames(
                "Wilson drives forward",
                fixture,
                rememberedNames = mapOf("wilson" to "Harry Wilson"),
            ),
        )
    }

    @Test
    fun `the club named in the sentence outranks the remembered name`() {
        // The sentence itself says Leeds, so it must win even though the last whole name heard was
        // the Brentford Wilson.
        assertEquals(
            "Harry Wilson drives forward for Leeds",
            SubtitleGlossary.resolveSurnames(
                "Wilson drives forward for Leeds",
                fixture,
                rememberedNames = mapOf("wilson" to "Callum Wilson"),
            ),
        )
    }

    @Test
    fun `a recently named club resolves it when no whole name was heard`() {
        assertEquals(
            "Callum Wilson drives forward",
            SubtitleGlossary.resolveSurnames(
                "Wilson drives forward",
                fixture,
                recentClubs = setOf(SubtitleGlossary.Team.BRENTFORD),
            ),
        )
        assertEquals(
            "Harry Wilson drives forward",
            SubtitleGlossary.resolveSurnames(
                "Wilson drives forward",
                fixture,
                recentClubs = setOf(SubtitleGlossary.Team.LEEDS),
            ),
        )
    }

    @Test
    fun `both clubs being recent leaves the surname alone`() {
        assertEquals(
            "Wilson drives forward",
            SubtitleGlossary.resolveSurnames(
                "Wilson drives forward",
                fixture,
                recentClubs = setOf(SubtitleGlossary.Team.BRENTFORD, SubtitleGlossary.Team.LEEDS),
            ),
        )
    }

    @Test
    fun `a remembered name outside the fixture proves nothing`() {
        assertEquals(
            "Wilson drives forward",
            SubtitleGlossary.resolveSurnames(
                "Wilson drives forward",
                fixture,
                rememberedNames = mapOf("wilson" to "Someone Else"),
            ),
        )
    }

    @Test
    fun `no context means the conservative answer stays conservative`() {
        assertEquals(
            "Wilson drives forward",
            SubtitleGlossary.resolveSurnames("Wilson drives forward", fixture),
        )
        // A surname that only one scoped player carries needs no resolution either way.
        assertTrue(
            SubtitleGlossary.resolveSurnames("Saka drives forward", setOf(SubtitleGlossary.Team.ARSENAL))
                == "Saka drives forward",
        )
    }
}
