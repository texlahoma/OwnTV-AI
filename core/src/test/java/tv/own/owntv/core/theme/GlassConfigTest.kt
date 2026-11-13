package tv.own.owntv.core.theme

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test

class GlassConfigTest {

    @Test
    fun `Glass on keeps the value older versions and their backups read`() {
        // The seven surfaces the television stored as "on" before glass became one switch.
        assertEquals(0b111_1111, GlassConfig.ON_BITS)
    }

    @Test
    fun `glass starts off at half opacity`() {
        assertFalse(GlassConfig().enabled)
        assertEquals(0.5f, GlassConfig().alpha, 0f)
    }
}
