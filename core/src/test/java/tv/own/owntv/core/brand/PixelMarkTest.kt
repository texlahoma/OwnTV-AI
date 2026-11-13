package tv.own.owntv.core.brand

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class PixelMarkTest {

    @Test
    fun sixSideDots() = assertEquals(6, PixelMark.dots.count { it.kind == PixelMark.Kind.SIDE })

    @Test
    fun triangleDotsSitInsideTheTriangleBox() {
        val tri = PixelMark.dots.filter { it.kind == PixelMark.Kind.TRIANGLE }
        assertTrue(tri.isNotEmpty())
        assertTrue(tri.all { it.x in 38f..72f && it.y in 30f..70f })
    }

    @Test
    fun noDotUnderThePowerLight() =
        assertTrue(PixelMark.dots.none { (it.x - 76) * (it.x - 76) + (it.y - 24) * (it.y - 24) < 40 })
}
