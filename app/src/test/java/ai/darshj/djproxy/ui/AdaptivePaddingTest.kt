package ai.darshj.djproxy.ui

import ai.darshj.djproxy.ui.adaptive.responsiveSidePadding
import androidx.compose.ui.unit.dp
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * The responsive-layout primitive that keeps the app from stretching edge-to-edge on the Fold7's
 * unfolded pane. Pure [androidx.compose.ui.unit.Dp] math — no Android framework — so it runs as a
 * plain JVM unit test.
 */
class AdaptivePaddingTest {

    @Test
    fun `phone width keeps the standard 20dp gutter`() {
        // Folded phone / LDPlayer — well under the 600dp cap, so no centering, zero regression.
        assertEquals(20.dp, responsiveSidePadding(availableWidth = 411.dp))
    }

    @Test
    fun `narrow width below cap plus two gutters still uses min gutter`() {
        // 600 + 20*2 = 640dp is the threshold; at/under it we must NOT grow the inset.
        assertEquals(20.dp, responsiveSidePadding(availableWidth = 640.dp))
    }

    @Test
    fun `unfolded wide pane centers the capped reading column`() {
        // Fold7 inner display (~840dp): inset = (840 - 600) / 2 = 120dp each side, centering a 600dp column.
        assertEquals(120.dp, responsiveSidePadding(availableWidth = 840.dp))
    }

    @Test
    fun `very wide pane grows the inset proportionally`() {
        assertEquals(300.dp, responsiveSidePadding(availableWidth = 1200.dp))
    }
}
