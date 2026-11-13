package tv.own.owntv.core.customize

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class CategoryRestoreTest {
    @Test
    fun `no saved order, or one that matches the order added, is nothing to clear`() {
        assertFalse(savedOrderDiffers(listOf(1L, 2L, 3L), emptyMap()))
        assertFalse(savedOrderDiffers(listOf(1L, 2L, 3L), mapOf(1L to 0, 2L to 1, 3L to 2)))
    }

    @Test
    fun `a reordered combined category has an order to clear`() {
        assertTrue(savedOrderDiffers(listOf(1L, 2L, 3L), mapOf(3L to 0, 1L to 1, 2L to 2)))
    }

    @Test
    fun `saved items lead and the rest follow in the order added`() {
        // 3 was saved first; 1 and 2 follow as added — that differs from 1, 2, 3.
        assertTrue(savedOrderDiffers(listOf(1L, 2L, 3L), mapOf(3L to 0)))
        // 1 was saved first, the rest follow as added — the same as 1, 2, 3.
        assertFalse(savedOrderDiffers(listOf(1L, 2L, 3L), mapOf(1L to 0)))
    }

    @Test
    fun `a plan is empty only when it has nothing to undo`() {
        assertTrue(CategoryRestorePlan().isEmpty)
        assertFalse(CategoryRestorePlan(hasOrder = true).isEmpty)
        assertFalse(CategoryRestorePlan(movedOut = setOf("k")).isEmpty)
    }
}
