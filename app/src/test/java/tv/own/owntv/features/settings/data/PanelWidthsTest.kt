package tv.own.owntv.features.settings.data

import androidx.compose.ui.unit.dp
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import tv.own.owntv.core.settings.PanelShares
import tv.own.owntv.core.settings.PanelWidthLimits
import tv.own.owntv.core.settings.balanceToTotal

class PanelWidthsTest {

    @Test
    fun `zero is valid only for the third panel when total is 100`() {
        assertTrue(PanelShares(category = 40, list = 60, preview = 0).isValid)
        assertFalse(PanelShares(category = 0, list = 80, preview = 20).isValid)
        assertFalse(PanelShares(category = 20, list = 70, preview = 0).isValid)
        assertFalse(PanelShares(category = 15, list = 80, preview = 5).isValid)
    }

    @Test
    fun `balancing preserves a hidden third panel`() {
        val balanced = balanceToTotal(PanelShares(category = 35, list = 55, preview = 0))

        assertEquals(0, balanced.preview)
        assertEquals(100, balanced.total)
        assertTrue(balanced.category >= PanelWidthLimits.MIN)
        assertTrue(balanced.list >= PanelWidthLimits.MIN)
    }

    @Test
    fun `balancing never lets category or list become zero`() {
        val balanced = balanceToTotal(PanelShares(category = 0, list = 90, preview = 10))

        assertEquals(100, balanced.total)
        assertTrue(balanced.category >= PanelWidthLimits.MIN)
        assertTrue(balanced.list >= PanelWidthLimits.MIN)
    }

    @Test
    fun `hidden preview reserves divider and its two surrounding gaps`() {
        val widths = computePanelWidths(
            shares = PanelShares(category = 40, list = 60, preview = 0),
            total = 1_000.dp,
        )

        assertEquals(0f, widths.preview.value, 0f)
        assertEquals(975f, widths.category.value + widths.list.value, 0.01f)
    }

    @Test
    fun `visible preview reserves divider and all three column gaps`() {
        val widths = computePanelWidths(
            shares = PanelShares(category = 20, list = 50, preview = 30),
            total = 1_000.dp,
        )

        assertEquals(963f, widths.category.value + widths.list.value + widths.preview.value, 0.01f)
        assertTrue(widths.preview.value > 0f)
    }
}
