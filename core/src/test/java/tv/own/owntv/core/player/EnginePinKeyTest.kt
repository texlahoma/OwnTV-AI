package tv.own.owntv.core.player

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * The per-item engine pin used to be keyed on the stream URL, which a Stalker portal mints
 * fresh (and single-use) on every play, so a pin never matched on the next open. The key must depend
 * only on identity the provider keeps stable.
 */
class EnginePinKeyTest {

    @Test
    fun `key is stable across sessions that resolve different stream urls`() {
        // Same channel, two Stalker sessions: the resolved URL differs, the pin key must not.
        val first = enginePinKey(sourceId = 7, mediaType = "LIVE", remoteId = "1234")
        val second = enginePinKey(sourceId = 7, mediaType = "LIVE", remoteId = "1234")
        assertEquals(first, second)
        assertEquals("7:LIVE:1234", first)
    }

    @Test
    fun `key separates sources, media types and items`() {
        val base = enginePinKey(1, "MOVIE", "42")
        assertNotEquals(base, enginePinKey(2, "MOVIE", "42"))
        assertNotEquals(base, enginePinKey(1, "EPISODE", "42"))
        assertNotEquals(base, enginePinKey(1, "MOVIE", "43"))
    }

    @Test
    fun `no provider id means no stable key, so callers keep the legacy url key`() {
        assertNull(enginePinKey(1, "MOVIE", null))
        assertNull(enginePinKey(1, "MOVIE", ""))
        assertNull(enginePinKey(1, "MOVIE", "   "))
    }
}
